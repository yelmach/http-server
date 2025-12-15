package http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
                    break;
                case PARSING_CHUNK_DATA:
                    break;
                case PARSING_CHUNK_TRAILER:
                    break;
                default:
                    break;
            }
        }

        return ParsingResult.complete(httpRequest);
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

        int queryIndex = uri.indexOf('?');
        if (queryIndex == -1) {
            httpRequest.setPath(uri);
            httpRequest.setQueryString(null);
        } else {
            httpRequest.setPath(uri.substring(0, queryIndex));
            httpRequest.setQueryString(uri.substring(queryIndex + 1));
        }

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

        // TODO: Handle multi-line headers starting with space/tab
        while (position < end) {
            int lineEnd = findLineEnd(data, position);
            if (lineEnd == -1) {
                break;
            }

            String headerLine = new String(data, position, lineEnd - position, StandardCharsets.UTF_8);

            int colonIndex = headerLine.indexOf(':');
            if (colonIndex == -1) {
                errorMessage = "Invalid header format (missing colon): " + headerLine;
                return false;
            }

            String name = headerLine.substring(0, colonIndex).trim();
            String value = headerLine.substring(colonIndex + 1).trim();

            if (name.isEmpty()) {
                errorMessage = "Empty header name";
                return false;
            }

            httpRequest.getHeaders().add(name, value);

            position = lineEnd + 2; // Skip \r\n
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
