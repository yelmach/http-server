package http;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import exceptions.HttpParseException;
import exceptions.InvalidMethodException;

public class HttpParser {
    private enum State {
        PARSE_REQUEST_LINE,
        PARSE_HEADERS,
        PARSE_BODY,
        COMPLETE,
        ERROR
    }

    private State state;
    private HttpRequest.Builder requestBuilder;
    private int bodyBytesRead;
    private int expectedBodyLength;
    private List<byte[]> bodyChunks;

    private static final int MAX_HEADER_SIZE = 16 * 1024;
    private static final int MAX_REQUEST_LINE_LENGTH = 8 * 1024;

    public HttpParser() {
        this.state = State.PARSE_REQUEST_LINE;
        this.requestBuilder = new HttpRequest.Builder();
        this.bodyChunks = new ArrayList<>();
    }

    public ParseResult parse(RequestBuffer buffer) throws HttpParseException {
        while (true) {
            switch (state) {
                case PARSE_REQUEST_LINE:
                    if (!parseRequestLine(buffer)) {
                        return ParseResult.needMore();
                    }
                    state = State.PARSE_HEADERS;
                    break;

                case PARSE_HEADERS:
                    if (!parseHeaders(buffer)) {
                        return ParseResult.needMore();
                    }
                    prepareForBody();
                    state = State.PARSE_BODY;
                    break;

                case PARSE_BODY:
                    if (!parseBody(buffer)) {
                        return ParseResult.needMore();
                    }
                    state = State.COMPLETE;
                    return ParseResult.complete(requestBuilder.build());

                case COMPLETE:
                    throw new IllegalStateException("Parser already completed");

                case ERROR:
                    throw new HttpParseException("Parser in error state");
            }
        }
    }

    private boolean parseRequestLine(RequestBuffer buffer) throws HttpParseException {
        int crlfPos = buffer.indexOf("\r\n");
        if (crlfPos == -1) {
            if (buffer.position() > MAX_REQUEST_LINE_LENGTH) {
                throw new HttpParseException("Request line too long");
            }
            return false;
        }

        String line = buffer.readLine(crlfPos);
        String[] parts = line.split(" ");
        if (parts.length != 3) {
            throw new HttpParseException("Malformed request line: " + line);
        }

        try {
            requestBuilder.method(HttpMethod.fromString(parts[0]));
        } catch (InvalidMethodException e) {
            throw new HttpParseException(e.getMessage());
        }
        requestBuilder.uri(parts[1]);
        requestBuilder.httpVersion(parts[2]);

        return true;
    }

    private boolean parseHeaders(RequestBuffer buffer) throws HttpParseException {
        while (true) {
            int crlfPos = buffer.indexOf("\r\n");
            if (crlfPos == -1) {
                if (buffer.position() > MAX_HEADER_SIZE) {
                    throw new HttpParseException("Headers too large");
                }
                return false;
            }

            if (crlfPos == 0) {
                buffer.skip(2);
                return true;
            }

            String line = buffer.readLine(crlfPos);
            int colonPos = line.indexOf(':');
            if (colonPos == -1) {
                throw new HttpParseException("Invalid header: " + line);
            }

            String key = line.substring(0, colonPos).trim();
            String value = line.substring(colonPos + 1).trim();
            requestBuilder.addHeader(key, value);
        }
    }

    private void prepareForBody() {
        HttpHeader headers = requestBuilder.getHeaders();

        if (headers.has("Content-Length")) {
            Long contentLength = headers.getContentLength();
            if (contentLength != null) {
                expectedBodyLength = contentLength.intValue();
            } else {
                expectedBodyLength = 0;
            }
        } else if ("chunked".equalsIgnoreCase(headers.getTransferEncoding())) {
            throw new UnsupportedOperationException("Chunked encoding not yet supported");
        } else {
            expectedBodyLength = 0;
        }
    }

    private boolean parseBody(RequestBuffer buffer) throws HttpParseException {
        if (expectedBodyLength == 0) {
            return true;
        }

        int available = buffer.remaining();
        int needed = expectedBodyLength - bodyBytesRead;

        if (available < needed) {
            byte[] chunk = buffer.readBytes(available);
            bodyChunks.add(chunk);
            bodyBytesRead += available;
            return false;
        } else {
            byte[] chunk = buffer.readBytes(needed);
            bodyChunks.add(chunk);
            bodyBytesRead += needed;

            byte[] fullBody = assembleBody();
            requestBuilder.body(fullBody);
            return true;
        }
    }

    private byte[] assembleBody() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(expectedBodyLength);
        for (byte[] chunk : bodyChunks) {
            baos.write(chunk, 0, chunk.length);
        }
        return baos.toByteArray();
    }
}
