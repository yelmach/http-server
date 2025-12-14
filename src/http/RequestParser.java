package http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import exceptions.InvalidMethodException;

public class RequestParser {

    private enum State {
        PARSING_REQUEST_LINE,
        PARSING_HEADERS,
        PARSING_BODY_FIXED_LENGTH,
        PARSING_BODY_CHUNKED,
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
    private HttpRequest HttpRequest;
    private ByteArrayOutputStream accumulationBuffer;

    private int headerBytesRead;
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
        HttpRequest = new HttpRequest();
        accumulationBuffer.reset();
        headerBytesRead = 0;
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
                    break;
                case PARSING_BODY_FIXED_LENGTH:
                    break;
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

        return ParsingResult.complete(HttpRequest);
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
            HttpRequest.setMethod(HttpMethod.fromString(parts[0]));
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
            HttpRequest.setPath(uri);
            HttpRequest.setQueryString(null);
        } else {
            HttpRequest.setPath(uri.substring(0, queryIndex));
            HttpRequest.setQueryString(uri.substring(queryIndex + 1));
        }

        String version = parts[2];
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            errorMessage = "Unsupported HTTP version: " + version;
            return false;
        }

        HttpRequest.setHttpVersion(version);

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

    private int findHeaderEnd(byte[] data, int startPos) {
        for (int i = startPos; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }

        return -1;
    }

    private void KeepUnprocessedData(byte[] data, int startPos) {
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
