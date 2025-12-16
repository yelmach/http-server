package http;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import exceptions.InvalidMethodException;

public class RequestParser {

    private enum State {
        PARSING_REQUEST_LINE,
        PARSING_HEADERS,
        PARSING_BODY_FIXED_LENGTH,
        PARSING_CHUNK_SIZE,
        PARSING_CHUNK_DATA,
        PARSING_CHUNK_TRAILER,
        COMPLETE,
        ERROR
    }

    private static final int MAX_REQUEST_LINE_LENGTH = 8 * 1024;
    private static final int MAX_HEADER_SIZE = 16 * 1024;
    private static final int MAX_URI_LENGTH = 4 * 1024;
    private final int maxBodySize;

    private State currentState;
    private HttpRequest httpRequest;
    private ByteArrayOutputStream accumulationBuffer;
    private ByteArrayOutputStream bodyStream;

    private int bodyBytesRead;
    private int expectedBodyLength;

    private int currentChunkSize;
    private int currentChunkBytesRead;

    private String errorMessage;

    public RequestParser(int maxBodySize) {
        this.maxBodySize = maxBodySize;
        this.accumulationBuffer = new ByteArrayOutputStream();

        reset();
    }

    public RequestParser() {
        this(10 * 1024 * 1024);
    }

    public void reset() {
        currentState = State.PARSING_REQUEST_LINE;
        httpRequest = new HttpRequest();
        accumulationBuffer.reset();
        bodyBytesRead = 0;
        expectedBodyLength = 0;
        currentChunkSize = 0;
        currentChunkBytesRead = 0;
        errorMessage = null;
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

                    if (httpRequest.getHeaders().isChunked()) {
                        currentState = State.PARSING_CHUNK_SIZE;
                    } else {
                        expectedBodyLength = httpRequest.getHeaders().getContentLength();

                        if (expectedBodyLength > maxBodySize) {
                            return error("Request body size " + expectedBodyLength +
                                    " exceeds maximum " + maxBodySize);
                        }

                        if (expectedBodyLength > 0) {
                            currentState = State.PARSING_BODY_FIXED_LENGTH;
                        } else {
                            currentState = State.COMPLETE;
                            accumulationBuffer.reset();
                            return ParsingResult.complete(httpRequest);
                        }
                    }
                    break;

                case PARSING_BODY_FIXED_LENGTH:
                    int remaining = expectedBodyLength - bodyBytesRead;
                    int availableBytes = data.length - position;
                    int bytesToRead = Math.min(remaining, availableBytes);

                    if (bytesToRead > 0) {
                        if (httpRequest.getBody() == null) {
                            httpRequest.setBody(new byte[expectedBodyLength]);
                        }

                        System.arraycopy(data, position, httpRequest.getBody(), bodyBytesRead, bytesToRead);
                        bodyBytesRead += bytesToRead;
                        position += bytesToRead;
                    }

                    if (bodyBytesRead >= expectedBodyLength) {
                        currentState = State.COMPLETE;
                        accumulationBuffer.reset();
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
                    accumulationBuffer.reset();
                    return ParsingResult.complete(httpRequest);

                default:
                    return error("Invalid parser state: " + currentState);
            }
        }

        accumulationBuffer.reset();
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

        // Check for path traversal
        if (uri.contains("..")) {
            errorMessage = "Path traversal detected in URI";
            return false;
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
}
