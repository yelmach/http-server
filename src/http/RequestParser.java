package http;

import exceptions.InvalidMethodException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

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

    // Standard Body Buffers
    private ByteArrayOutputStream bodyStream;
    private File bodyTempFile;
    private FileOutputStream bodyOutputStream;
    private boolean streamingBodyToDisk;

    private int bodyBytesRead;
    private int expectedBodyLength;

    private int currentChunkSize;
    private int currentChunkBytesRead;

    private int emptyLinesSkipped;
    private String errorMessage;

    // Multipart parsing state
    private String multipartBoundary;
    private MultipartParser multipartParser;
    private boolean isChunkedMultipart; // Flag to track mixed mode

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

        if (multipartParser != null) {
            multipartParser.cleanup();
        }

        try {
            if (bodyOutputStream != null)
                bodyOutputStream.close();
        } catch (IOException e) {
            // Ignore
        }

        // Clean up temp file only if we are resetting due to error/timeout
        if (currentState == State.ERROR && bodyTempFile != null && bodyTempFile.exists()) {
            bodyTempFile.delete();
        }

        multipartBoundary = null;
        multipartParser = null;
        bodyTempFile = null;
        bodyOutputStream = null;
        streamingBodyToDisk = false;
        isChunkedMultipart = false;
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
                    while (position + 1 < data.length && data[position] == '\r' && data[position + 1] == '\n') {
                        emptyLinesSkipped++;
                        if (emptyLinesSkipped > MAX_EMPTY_LINES)
                            return error("Too many empty lines");
                        position += 2;
                    }
                    int endLinePos = findLineEnd(data, position);
                    if (endLinePos == -1) {
                        if (data.length >= MAX_REQUEST_LINE_LENGTH)
                            return error("Request line too large");
                        return ParsingResult.needMoreData();
                    }
                    String requestLine = new String(data, position, endLinePos - position, StandardCharsets.UTF_8);
                    if (!parseRequestLine(requestLine))
                        return ParsingResult.error(errorMessage);
                    position = endLinePos + 2;
                    currentState = State.PARSING_HEADERS;
                    break;

                case PARSING_HEADERS:
                    int headersEnd = findHeadersEnd(data, position);
                    if (headersEnd == -1) {
                        if (data.length - position > MAX_HEADER_SIZE)
                            return error("Headers too large");
                        removeProcessedData(data, position);
                        return ParsingResult.needMoreData();
                    }
                    if (!parseHeaders(data, position, headersEnd))
                        return ParsingResult.error(errorMessage);
                    position = headersEnd + 4;

                    if (httpRequest.getHeaders().validate() != null)
                        return error(httpRequest.getHeaders().validate());

                    String contentType = httpRequest.getHeaders().get("Content-Type");
                    boolean isMultipart = contentType != null && contentType.startsWith("multipart/form-data");
                    boolean isChunked = httpRequest.getHeaders().isChunked();

                    if (isMultipart) {
                        multipartBoundary = extractBoundary(contentType);
                        if (multipartBoundary == null)
                            return error("Missing multipart boundary");
                        multipartParser = new MultipartParser(multipartBoundary, maxBodySize);
                    }

                    if (isChunked) {
                        // Priority 1: Chunked Encoding (Transport Layer)
                        currentState = State.PARSING_CHUNK_SIZE;
                        if (isMultipart) {
                            isChunkedMultipart = true; // Flag to pipe data later
                        }
                    } else if (isMultipart) {
                        // Priority 2: Standard Multipart (Identity Encoding)
                        expectedBodyLength = httpRequest.getHeaders().getContentLength();
                        if (expectedBodyLength > maxBodySize)
                            return error("Body too large");
                        currentState = State.PARSING_MULTIPART;
                    } else {
                        // Priority 3: Standard Fixed Length
                        expectedBodyLength = httpRequest.getHeaders().getContentLength();
                        if (expectedBodyLength > maxBodySize)
                            return error("Body too large");

                        if (expectedBodyLength > STREAM_TO_DISK_THRESHOLD) {
                            try {
                                switchToDiskMode();
                            } catch (IOException e) {
                                return error(e.getMessage());
                            }
                        }

                        if (expectedBodyLength > 0)
                            currentState = State.PARSING_BODY_FIXED_LENGTH;
                        else {
                            currentState = State.COMPLETE;
                            removeProcessedData(data, position);
                            return ParsingResult.complete(httpRequest);
                        }
                    }
                    break;

                case PARSING_BODY_FIXED_LENGTH:
                    int remaining = expectedBodyLength - bodyBytesRead;
                    int toRead = Math.min(remaining, data.length - position);

                    if (toRead > 0) {
                        if (streamingBodyToDisk) {
                            try {
                                bodyOutputStream.write(data, position, toRead);
                            } catch (IOException e) {
                                return error("Disk write failed");
                            }
                        } else {
                            if (httpRequest.getBody() == null)
                                httpRequest.setBody(new byte[expectedBodyLength]);
                            System.arraycopy(data, position, httpRequest.getBody(), bodyBytesRead, toRead);
                        }
                        bodyBytesRead += toRead;
                        position += toRead;
                    }

                    if (bodyBytesRead >= expectedBodyLength) {
                        if (streamingBodyToDisk)
                            try {
                                bodyOutputStream.close();
                            } catch (IOException e) {
                            }
                        currentState = State.COMPLETE;
                        removeProcessedData(data, position);
                        return ParsingResult.complete(httpRequest);
                    } else {
                        removeProcessedData(data, position);
                        return ParsingResult.needMoreData();
                    }

                case PARSING_CHUNK_SIZE:
                    int chunkLineEnd = findLineEnd(data, position);
                    if (chunkLineEnd == -1) {
                        removeProcessedData(data, position);
                        return ParsingResult.needMoreData();
                    }

                    String line = new String(data, position, chunkLineEnd - position, StandardCharsets.UTF_8).trim();
                    try {
                        int semi = line.indexOf(';');
                        if (semi != -1)
                            line = line.substring(0, semi);
                        currentChunkSize = Integer.parseInt(line.trim(), 16);
                    } catch (NumberFormatException e) {
                        return error("Invalid chunk size");
                    }
                    position = chunkLineEnd + 2;

                    if (currentChunkSize == 0)
                        currentState = State.PARSING_CHUNK_TRAILER;
                    else {
                        currentState = State.PARSING_CHUNK_DATA;
                        currentChunkBytesRead = 0;
                    }
                    break;

                case PARSING_CHUNK_DATA:
                    int chunkLeft = currentChunkSize - currentChunkBytesRead;
                    int chunkRead = Math.min(chunkLeft, data.length - position);

                    if (chunkRead > 0) {
                        if (bodyBytesRead + chunkRead > maxBodySize)
                            return error("Chunked body too large");

                        // Branch A: Chunked Multipart (Pipe decoded bytes to MultipartParser)
                        if (isChunkedMultipart) {
                            // We must copy data because parse() expects a buffer it can read from
                            byte[] chunkData = new byte[chunkRead];
                            System.arraycopy(data, position, chunkData, 0, chunkRead);

                            // Feed to Multipart Parser
                            ParsingResult mpResult = multipartParser.parse(chunkData, 0, httpRequest);
                            if (mpResult.isError())
                                return mpResult;
                            // Note: Multipart might not be complete yet, we just feed it chunks
                        }
                        // Branch B: Standard Chunked (Buffer to RAM/Disk)
                        else {
                            if (!streamingBodyToDisk && (bodyBytesRead + chunkRead > STREAM_TO_DISK_THRESHOLD)) {
                                try {
                                    switchToDiskMode();
                                    if (bodyStream != null) {
                                        bodyOutputStream.write(bodyStream.toByteArray());
                                        bodyStream = null;
                                    }
                                } catch (IOException e) {
                                    return error("Disk switch failed");
                                }
                            }

                            if (streamingBodyToDisk) {
                                try {
                                    bodyOutputStream.write(data, position, chunkRead);
                                } catch (IOException e) {
                                    return error("Disk write failed");
                                }
                            } else {
                                if (bodyStream == null)
                                    bodyStream = new ByteArrayOutputStream();
                                bodyStream.write(data, position, chunkRead);
                            }
                        }

                        currentChunkBytesRead += chunkRead;
                        bodyBytesRead += chunkRead;
                        position += chunkRead;
                    }

                    if (currentChunkBytesRead >= currentChunkSize) {
                        if (position + 2 > data.length) {
                            removeProcessedData(data, position);
                            return ParsingResult.needMoreData();
                        }
                        if (data[position] != '\r' || data[position + 1] != '\n')
                            return error("Expected CRLF");
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
                    if (data[position] != '\r' || data[position + 1] != '\n')
                        return error("Expected CRLF");

                    // Finalize Body based on Mode
                    if (isChunkedMultipart) {
                        // Multipart parser should have populated httpRequest.multipartParts already
                        // We assume it finished when it saw the end boundary inside the chunks.
                    } else if (streamingBodyToDisk) {
                        try {
                            bodyOutputStream.close();
                        } catch (IOException e) {
                        }
                        httpRequest.setBodyTempFile(bodyTempFile);
                    } else if (bodyStream != null) {
                        httpRequest.setBody(bodyStream.toByteArray());
                    } else {
                        httpRequest.setBody(new byte[0]);
                    }

                    currentState = State.COMPLETE;
                    removeProcessedData(data, position);
                    return ParsingResult.complete(httpRequest);

                case PARSING_MULTIPART:
                    // Standard Identity Multipart (Not Chunked)
                    ParsingResult mpResult = multipartParser.parse(data, position, httpRequest);
                    if (mpResult.getProcessedPosition() >= 0)
                        removeProcessedData(data, mpResult.getProcessedPosition());
                    return mpResult;

                default:
                    return error("Invalid State");
            }
        }
        removeProcessedData(data, position);
        return ParsingResult.needMoreData();
    }

    private void switchToDiskMode() throws IOException {
        if (streamingBodyToDisk)
            return;
        bodyTempFile = File.createTempFile("http-body-", ".tmp");
        bodyTempFile.deleteOnExit();
        bodyOutputStream = new FileOutputStream(bodyTempFile);
        streamingBodyToDisk = true;
        httpRequest.setBodyTempFile(bodyTempFile);
    }

    private boolean parseRequestLine(String line) {
        if (line.length() > MAX_REQUEST_LINE_LENGTH) {
            errorMessage = "Request line too large";
            return false;
        }
        String[] parts = line.split(" ");
        if (parts.length != 3) {
            errorMessage = "Invalid request line";
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
            errorMessage = "URI too large";
            return false;
        }
        int fragmentIndex = uri.indexOf('#');
        if (fragmentIndex != -1)
            uri = uri.substring(0, fragmentIndex);

        String rawPath, rawQuery;
        int queryIndex = uri.indexOf('?');
        if (queryIndex == -1) {
            rawPath = uri;
            rawQuery = null;
        } else {
            rawPath = uri.substring(0, queryIndex);
            rawQuery = uri.substring(queryIndex + 1);
        }

        try {
            String decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name());
            if (decoded.contains("..")) {
                errorMessage = "Path traversal";
                return false;
            }
            httpRequest.setPath(decoded);
        } catch (Exception e) {
            errorMessage = "URL Decode Error";
            return false;
        }

        httpRequest.setQueryString(rawQuery);
        if (!parts[2].equals("HTTP/1.1") && !parts[2].equals("HTTP/1.0")) {
            errorMessage = "Bad HTTP Version";
            return false;
        }
        httpRequest.setHttpVersion(parts[2]);
        return true;
    }

    private boolean parseHeaders(byte[] d, int s, int e) {
        int pos = s;
        String name = null;
        StringBuilder val = new StringBuilder();
        while (pos < e) {
            int end = findLineEnd(d, pos);
            if (end == -1)
                break;
            String line = new String(d, pos, end - pos, StandardCharsets.UTF_8);
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                if (name == null) {
                    errorMessage = "Bad header";
                    return false;
                }
                val.append(' ').append(line.trim());
            } else {
                if (name != null)
                    addHeader(name, val.toString());
                int col = line.indexOf(':');
                if (col == -1) {
                    errorMessage = "No colon in header";
                    return false;
                }
                name = line.substring(0, col).trim();
                val = new StringBuilder(line.substring(col + 1).trim());
            }
            pos = end + 2;
        }
        if (name != null)
            addHeader(name, val.toString());
        return true;
    }

    private void addHeader(String name, String val) {
        httpRequest.getHeaders().add(name, val);
        if (name.equalsIgnoreCase("Cookie")) {
            for (String p : val.split(";")) {
                String[] kv = p.split("=", 2);
                if (kv.length == 2)
                    httpRequest.addCookie(kv[0].trim(), kv[1].trim());
            }
        }
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

    private ParsingResult error(String msg) {
        errorMessage = msg;
        currentState = State.ERROR;
        return ParsingResult.error(msg);
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

    public void cleanup() {
        try {
            if (bodyOutputStream != null) {
                bodyOutputStream.close();
            }
        } catch (IOException e) {
        }

        if (bodyTempFile != null && bodyTempFile.exists()) {
            bodyTempFile.delete();
        }

        if (multipartParser != null) {
            multipartParser.cleanup();
        }
    }
}