package http;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import exceptions.InvalidMethodException;

public class RequestParser {

    private enum State {
        PARSING_REQUEST_LINE,
        PARSING_HEADERS,
        PARSING_BODY_FIXED_LENGTH,
        PARSING_CHUNK_SIZE,
        PARSING_CHUNK_DATA,
        PARSING_CHUNK_TRAILER,
        PARSING_MULTIPART,
        COMPLETE,
        ERROR
    }

    private static final int MAX_REQUEST_LINE_LENGTH = 8 * 1024;
    private static final int MAX_HEADER_SIZE = 16 * 1024;
    private static final int MAX_URI_LENGTH = 4 * 1024;
    private static final int MAX_EMPTY_LINES = 10;
    private static final int STREAM_TO_DISK_THRESHOLD = 5 * 1024 * 1024;
    private final int maxBodySize;

    private State currentState;
    private HttpRequest httpRequest;
    private ByteArrayOutputStream accumulationBuffer;
    private ByteArrayOutputStream bodyStream;

    private int bodyBytesRead;
    private int expectedBodyLength;

    private int currentChunkSize;
    private int currentChunkBytesRead;

    private int emptyLinesSkipped;
    private String errorMessage;

    // Multipart parsing state
    private boolean isMultipart;
    private String multipartBoundary;
    private List<MultipartPart> multipartParts;
    private MultipartPart currentPart;
    private File currentPartTempFile;
    private FileOutputStream currentPartOutputStream;
    private ByteArrayOutputStream currentPartContent;
    private boolean parsingPartHeaders;
    private ByteArrayOutputStream partHeadersBuffer;

    // Raw binary streaming state
    private File bodyTempFile;
    private FileOutputStream bodyOutputStream;
    private boolean streamingBodyToDisk;

    public RequestParser(int maxBodySize) {
        this.maxBodySize = maxBodySize;
        this.accumulationBuffer = new ByteArrayOutputStream();

        reset();
    }

    public RequestParser() {
        this(10 * 1024 * 1024);
    }

    public void resetState() {
        currentState = State.PARSING_REQUEST_LINE;
        httpRequest = new HttpRequest();
        bodyBytesRead = 0;
        expectedBodyLength = 0;
        currentChunkSize = 0;
        currentChunkBytesRead = 0;
        emptyLinesSkipped = 0;
        errorMessage = null;
        bodyStream = null;

        // Cleanup multipart temp files
        try {
            if (currentPartOutputStream != null) {
                currentPartOutputStream.close();
            }
        } catch (IOException e) {
            // Ignore
        }
        if (currentPartTempFile != null && currentPartTempFile.exists()) {
            currentPartTempFile.delete();
        }
        if (multipartParts != null) {
            for (MultipartPart part : multipartParts) {
                if (part.getTempFile() != null && part.getTempFile().exists()) {
                    part.getTempFile().delete();
                }
            }
        }

        // Cleanup raw binary temp file
        try {
            if (bodyOutputStream != null) {
                bodyOutputStream.close();
            }
        } catch (IOException e) {
            // Ignore
        }
        if (bodyTempFile != null && bodyTempFile.exists()) {
            bodyTempFile.delete();
        }

        // Reset state
        isMultipart = false;
        multipartBoundary = null;
        multipartParts = null;
        currentPart = null;
        currentPartTempFile = null;
        currentPartOutputStream = null;
        currentPartContent = null;
        parsingPartHeaders = false;
        partHeadersBuffer = null;
        bodyTempFile = null;
        bodyOutputStream = null;
        streamingBodyToDisk = false;
    }

    public void reset() {
        resetState();
        accumulationBuffer.reset();
    }

    public ParsingResult parse(ByteBuffer buffer) {
        if (currentState == State.ERROR) {
            return ParsingResult.error(errorMessage);
        }

        byte[] newData = new byte[buffer.remaining()];
        buffer.get(newData);
        accumulationBuffer.write(newData, 0, newData.length);

        return parseAccumulatedData();
    }

    private ParsingResult parseAccumulatedData() {
        byte[] data = accumulationBuffer.toByteArray();
        int position = 0;

        while (position < data.length) {
            switch (currentState) {
                case PARSING_REQUEST_LINE:
                    // empty line skipped
                    while (position + 1 < data.length && data[position] == '\r' && data[position + 1] == '\n') {
                        emptyLinesSkipped++;
                        if (emptyLinesSkipped > MAX_EMPTY_LINES) {
                            return error("Too many empty lines before request line (max " + MAX_EMPTY_LINES + ")");
                        }
                        position += 2;
                    }

                    int endLinePos = findLineEnd(data, position);
                    if (endLinePos == -1) {
                        if (data.length >= MAX_REQUEST_LINE_LENGTH) {
                            errorMessage = "Request line exceeds maximum length of " + MAX_REQUEST_LINE_LENGTH;
                            return error(errorMessage);
                        }
                        return ParsingResult.needMoreData();
                    }

                    String requestLine = new String(data, position, endLinePos - position, StandardCharsets.UTF_8);

                    if (!parseRequestLine(requestLine)) {
                        currentState = State.ERROR;
                        return ParsingResult.error(errorMessage);
                    }

                    position = endLinePos + 2; // Skip \r\n
                    currentState = State.PARSING_HEADERS;
                    break;

                case PARSING_HEADERS:
                    int headersEnd = findHeadersEnd(data, position);
                    if (headersEnd == -1) {
                        if (data.length - position > MAX_HEADER_SIZE) {
                            return error("Headers exceed maximum size of " + MAX_HEADER_SIZE);
                        }

                        removeProcessedData(data, position);
                        return ParsingResult.needMoreData();
                    }

                    if (!parseHeaders(data, position, headersEnd)) {
                        currentState = State.ERROR;
                        return ParsingResult.error(errorMessage);
                    }

                    position = headersEnd + 4; // Skip \r\n\r\n

                    String error = httpRequest.getHeaders().validate();
                    if (error != null) {
                        return error("Header validation failed: " + error);
                    }

                    // Check for multipart/form-data
                    String contentType = httpRequest.getHeaders().get("Content-Type");
                    if (contentType != null && contentType.toLowerCase().startsWith("multipart/form-data")) {
                        multipartBoundary = extractBoundary(contentType);
                        if (multipartBoundary == null) {
                            return error("Missing boundary in multipart Content-Type");
                        }

                        // Check Content-Length if present
                        expectedBodyLength = httpRequest.getHeaders().getContentLength();
                        if (expectedBodyLength > maxBodySize) {
                            return error("Request body size " + expectedBodyLength +
                                    " exceeds maximum " + maxBodySize);
                        }

                        isMultipart = true;
                        multipartParts = new ArrayList<>();
                        parsingPartHeaders = true;
                        partHeadersBuffer = new ByteArrayOutputStream();
                        currentState = State.PARSING_MULTIPART;
                    } else if (httpRequest.getHeaders().isChunked()) {
                        currentState = State.PARSING_CHUNK_SIZE;
                    } else {
                        expectedBodyLength = httpRequest.getHeaders().getContentLength();

                        if (expectedBodyLength > maxBodySize) {
                            return error("Request body size " + expectedBodyLength +
                                    " exceeds maximum " + maxBodySize);
                        }

                        if (expectedBodyLength > STREAM_TO_DISK_THRESHOLD) {
                            // Stream large bodies to disk
                            try {
                                bodyTempFile = File.createTempFile("http-body-", ".tmp");
                                bodyTempFile.deleteOnExit();
                                bodyOutputStream = new FileOutputStream(bodyTempFile);
                                streamingBodyToDisk = true;
                                httpRequest.setBodyTempFile(bodyTempFile);
                            } catch (IOException e) {
                                return error("Failed to create temp file for large body: " + e.getMessage());
                            }
                        }

                        if (expectedBodyLength > 0) {
                            currentState = State.PARSING_BODY_FIXED_LENGTH;
                        } else {
                            currentState = State.COMPLETE;
                            removeProcessedData(data, position);
                            return ParsingResult.complete(httpRequest);
                        }
                    }
                    break;

                case PARSING_BODY_FIXED_LENGTH:
                    int remaining = expectedBodyLength - bodyBytesRead;
                    int availableBytes = data.length - position;
                    int bytesToRead = Math.min(remaining, availableBytes);

                    if (bytesToRead > 0) {
                        if (streamingBodyToDisk) {
                            try {
                                bodyOutputStream.write(data, position, bytesToRead);
                            } catch (IOException e) {
                                return error("Failed to write to temp file: " + e.getMessage());
                            }
                        } else {
                            if (httpRequest.getBody() == null) {
                                httpRequest.setBody(new byte[expectedBodyLength]);
                            }
                            System.arraycopy(data, position, httpRequest.getBody(), bodyBytesRead, bytesToRead);
                        }
                        bodyBytesRead += bytesToRead;
                        position += bytesToRead;
                    }

                    if (bodyBytesRead >= expectedBodyLength) {
                        if (streamingBodyToDisk) {
                            try {
                                bodyOutputStream.close();
                            } catch (IOException e) {
                                return error("Failed to close temp file: " + e.getMessage());
                            }
                        }
                        currentState = State.COMPLETE;
                        removeProcessedData(data, position);
                        return ParsingResult.complete(httpRequest);
                    } else {
                        removeProcessedData(data, position);
                        return ParsingResult.needMoreData();
                    }

                case PARSING_CHUNK_SIZE:
                    int chunkSizeLineEnd = findLineEnd(data, position);
                    if (chunkSizeLineEnd == -1) {
                        removeProcessedData(data, position);
                        return ParsingResult.needMoreData();
                    }

                    String chunkSizeLine = new String(data, position, chunkSizeLineEnd - position,
                            StandardCharsets.UTF_8).trim();

                    try {
                        int semicolonIndex = chunkSizeLine.indexOf(';');
                        if (semicolonIndex != -1) {
                            chunkSizeLine = chunkSizeLine.substring(0, semicolonIndex);
                        }

                        currentChunkSize = Integer.parseInt(chunkSizeLine.trim(), 16);

                        if (currentChunkSize < 0) {
                            return error("Invalid chunk size: " + currentChunkSize);
                        }
                    } catch (NumberFormatException e) {
                        return error("Invalid chunk size format: " + chunkSizeLine);
                    }

                    position = chunkSizeLineEnd + 2;

                    if (currentChunkSize == 0) {
                        currentState = State.PARSING_CHUNK_TRAILER;
                    } else {
                        currentState = State.PARSING_CHUNK_DATA;
                        currentChunkBytesRead = 0;
                    }
                    break;

                case PARSING_CHUNK_DATA:
                    int remainingChunkBytes = currentChunkSize - currentChunkBytesRead;
                    int availableChunkBytes = data.length - position;
                    int chunkBytesToRead = Math.min(remainingChunkBytes, availableChunkBytes);

                    if (chunkBytesToRead > 0) {
                        if (bodyStream == null) {
                            bodyStream = new ByteArrayOutputStream();
                        }
                        bodyStream.write(data, position, chunkBytesToRead);

                        currentChunkBytesRead += chunkBytesToRead;
                        bodyBytesRead += chunkBytesToRead;
                        position += chunkBytesToRead;

                        if (bodyBytesRead > maxBodySize) {
                            return error("Chunked body size exceeds maximum " + maxBodySize);
                        }
                    }

                    if (currentChunkBytesRead >= currentChunkSize) {
                        if (position + 2 > data.length) {
                            removeProcessedData(data, position);
                            return ParsingResult.needMoreData();
                        }

                        if (data[position] != '\r' || data[position + 1] != '\n') {
                            return error("Expected CRLF after chunk data");
                        }

                        position += 2;
                        currentState = State.PARSING_CHUNK_SIZE;
                    } else {
                        removeProcessedData(data, position);
                        return ParsingResult.needMoreData();
                    }
                    break;

                case PARSING_CHUNK_TRAILER:
                    if (position + 2 > data.length) {
                        removeProcessedData(data, position);
                        return ParsingResult.needMoreData();
                    }

                    if (data[position] != '\r' || data[position + 1] != '\n') {
                        return error("Expected CRLF after final chunk");
                    }

                    if (bodyBytesRead > 0) {
                        httpRequest.setBody(bodyStream.toByteArray());
                    }

                    currentState = State.COMPLETE;
                    removeProcessedData(data, position);
                    return ParsingResult.complete(httpRequest);

                case PARSING_MULTIPART:
                    return parseMultipartData(data, position);

                default:
                    return error("Invalid parser state: " + currentState);
            }
        }

        removeProcessedData(data, position);
        return ParsingResult.needMoreData();
    }

    private boolean parseRequestLine(String line) {
        if (line.length() > MAX_REQUEST_LINE_LENGTH) {
            errorMessage = "Request line exceeds maximum length of " + MAX_REQUEST_LINE_LENGTH;
            return false;
        }

        String[] parts = line.split(" ");
        if (parts.length != 3) {
            errorMessage = "Invalid request line format: " + line;
            return false;
        }

        try {
            httpRequest.setMethod(HttpMethod.fromString(parts[0]));
        } catch (InvalidMethodException e) {
            errorMessage = e.getMessage();
            return false;
        }

        String uri = parts[1];
        if (uri.length() > MAX_URI_LENGTH) {
            errorMessage = "URI exceeds maximum length of " + MAX_URI_LENGTH;
            return false;
        }

        int fragmentIndex = uri.indexOf('#');
        if (fragmentIndex != -1) {
            uri = uri.substring(0, fragmentIndex);
        }

        String rawPath;
        String rawQuery;
        int queryIndex = uri.indexOf('?');

        if (queryIndex == -1) {
            rawPath = uri;
            rawQuery = null;
        } else {
            rawPath = uri.substring(0, queryIndex);
            rawQuery = uri.substring(queryIndex + 1);
        }

        String decodedPath;
        try {
            decodedPath = URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            errorMessage = "Malformed URL encoding in path";
            return false;
        }

        if (decodedPath.contains("..")) {
            errorMessage = "Path traversal detected";
            return false;
        }

        httpRequest.setPath(decodedPath);
        httpRequest.setQueryString(rawQuery);

        String version = parts[2];
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            errorMessage = "Unsupported HTTP version: " + version;
            return false;
        }

        httpRequest.setHttpVersion(version);

        return true;
    }

    private boolean parseHeaders(byte[] data, int start, int end) {
        int position = start;

        String currentHeaderName = null;
        StringBuilder currentHeaderValue = new StringBuilder();

        while (position < end) {
            int lineEnd = findLineEnd(data, position);
            if (lineEnd == -1) {
                break;
            }

            String line = new String(data, position, lineEnd - position, StandardCharsets.UTF_8);

            if (line.length() > 0 && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                if (currentHeaderName == null) {
                    errorMessage = "Header continuation line without preceding header";
                    return false;
                }

                currentHeaderValue.append(' ').append(line.trim());
            } else {
                if (currentHeaderName != null) {
                    httpRequest.getHeaders().add(currentHeaderName, currentHeaderValue.toString());
                }

                int colonIndex = line.indexOf(':');
                if (colonIndex == -1) {
                    errorMessage = "Invalid header format (missing colon): " + line;
                    return false;
                }

                currentHeaderName = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();

                if (currentHeaderName.isEmpty()) {
                    errorMessage = "Empty header name";
                    return false;
                }

                currentHeaderValue = new StringBuilder(value);
            }

            position = lineEnd + 2;
        }

        if (currentHeaderName != null) {
            httpRequest.getHeaders().add(currentHeaderName, currentHeaderValue.toString());
        }

        return true;
    }

    private int findLineEnd(byte[] data, int startPos) {
        for (int i = startPos; i < data.length - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                return i;
            }
        }

        return -1;
    }

    private int findHeadersEnd(byte[] data, int startPos) {
        for (int i = startPos; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }

        return -1;
    }

    private void removeProcessedData(byte[] data, int startPos) {
        accumulationBuffer.reset();

        if (startPos < data.length) {
            accumulationBuffer.write(data, startPos, data.length - startPos);
        }
    }

    private ParsingResult error(String message) {
        this.errorMessage = message;
        this.currentState = State.ERROR;
        return ParsingResult.error(message);
    }

    private String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }

        String[] parts = contentType.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                String boundary = part.substring("boundary=".length());
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        return null;
    }

    private ParsingResult parseMultipartData(byte[] data, int startPos) {
        int position = startPos;

        while (position < data.length) {
            if (parsingPartHeaders) {
                int boundaryPos = findBoundary(data, position, multipartBoundary);

                if (boundaryPos == -1) {
                    removeProcessedData(data, position);
                    return ParsingResult.needMoreData();
                }

                if (isEndBoundary(data, boundaryPos, multipartBoundary)) {
                    httpRequest.setMultipartParts(multipartParts);
                    currentState = State.COMPLETE;
                    int endBoundaryLength = ("--" + multipartBoundary + "--").length();
                    removeProcessedData(data, boundaryPos + endBoundaryLength);
                    return ParsingResult.complete(httpRequest);
                }

                position = boundaryPos + multipartBoundary.length() + 2;

                if (position + 2 <= data.length && data[position] == '\r' && data[position + 1] == '\n') {
                    position += 2;
                }

                int partHeadersEnd = findHeadersEnd(data, position);
                if (partHeadersEnd == -1) {
                    removeProcessedData(data, position);
                    return ParsingResult.needMoreData();
                }

                currentPart = parsePartHeaders(data, position, partHeadersEnd);
                if (currentPart == null) {
                    return error("Failed to parse multipart part headers");
                }

                position = partHeadersEnd + 4;

                if (currentPart.isFile()) {
                    try {
                        currentPartTempFile = File.createTempFile("http-part-", ".tmp");
                        currentPartTempFile.deleteOnExit();
                        currentPartOutputStream = new FileOutputStream(currentPartTempFile);
                        currentPart.setTempFile(currentPartTempFile);
                    } catch (IOException e) {
                        return error("Failed to create temp file for multipart part: " + e.getMessage());
                    }
                } else {
                    currentPartContent = new ByteArrayOutputStream();
                }

                parsingPartHeaders = false;

            } else {
                int nextBoundaryPos = findBoundary(data, position, multipartBoundary);

                if (nextBoundaryPos == -1) {
                    int bytesToWrite = data.length - position;
                    if (bytesToWrite > 0) {
                        try {
                            if (currentPart.isFile()) {
                                currentPartOutputStream.write(data, position, bytesToWrite);
                            } else {
                                currentPartContent.write(data, position, bytesToWrite);
                            }
                            bodyBytesRead += bytesToWrite;

                            if (bodyBytesRead > maxBodySize) {
                                return error("Multipart body exceeds maximum size");
                            }
                        } catch (IOException e) {
                            return error("Failed to write multipart part content: " + e.getMessage());
                        }
                    }
                    removeProcessedData(data, data.length);
                    return ParsingResult.needMoreData();

                } else {
                    int contentEnd = nextBoundaryPos - 2;
                    if (contentEnd < position) {
                        contentEnd = position;
                    }

                    int bytesToWrite = contentEnd - position;
                    if (bytesToWrite > 0) {
                        try {
                            if (currentPart.isFile()) {
                                currentPartOutputStream.write(data, position, bytesToWrite);
                            } else {
                                currentPartContent.write(data, position, bytesToWrite);
                            }
                        } catch (IOException e) {
                            return error("Failed to write multipart part content: " + e.getMessage());
                        }
                    }

                    try {
                        if (currentPart.isFile()) {
                            currentPartOutputStream.close();
                        } else {
                            currentPart.setContent(currentPartContent.toByteArray());
                        }
                    } catch (IOException e) {
                        return error("Failed to close multipart part: " + e.getMessage());
                    }

                    multipartParts.add(currentPart);
                    currentPart = null;
                    currentPartOutputStream = null;
                    currentPartContent = null;

                    position = nextBoundaryPos;
                    parsingPartHeaders = true;
                }
            }
        }

        removeProcessedData(data, position);
        return ParsingResult.needMoreData();
    }

    private int findBoundary(byte[] data, int startPos, String boundary) {
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);

        for (int i = startPos; i <= data.length - boundaryBytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < boundaryBytes.length; j++) {
                if (data[i + j] != boundaryBytes[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private boolean isEndBoundary(byte[] data, int pos, String boundary) {
        byte[] endBoundaryBytes = ("--" + boundary + "--").getBytes(StandardCharsets.UTF_8);

        if (pos + endBoundaryBytes.length > data.length) {
            return false;
        }

        for (int i = 0; i < endBoundaryBytes.length; i++) {
            if (data[pos + i] != endBoundaryBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private MultipartPart parsePartHeaders(byte[] data, int start, int end) {
        MultipartPart part = new MultipartPart();
        int position = start;

        while (position < end) {
            int lineEnd = findLineEnd(data, position);
            if (lineEnd == -1) {
                break;
            }

            String line = new String(data, position, lineEnd - position, StandardCharsets.UTF_8);

            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String headerName = line.substring(0, colonIndex).trim().toLowerCase();
                String headerValue = line.substring(colonIndex + 1).trim();

                if (headerName.equals("content-disposition")) {
                    String name = extractParameter(headerValue, "name");
                    String filename = extractParameter(headerValue, "filename");
                    part.setName(name);
                    part.setFilename(filename);
                } else if (headerName.equals("content-type")) {
                    part.setContentType(headerValue);
                }
            }

            position = lineEnd + 2;
        }

        return part;
    }

    private String extractParameter(String header, String paramName) {
        if (header == null) {
            return null;
        }

        String[] parts = header.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith(paramName + "=")) {
                String value = part.substring(paramName.length() + 1);
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }
}
