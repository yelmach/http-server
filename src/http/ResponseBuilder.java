package http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class ResponseBuilder {

    private final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.RFC_1123_DATE_TIME;

    private HttpStatusCode statusCode;
    private HttpHeaders headers;
    private byte[] body;

    public ResponseBuilder() {
        headers = new HttpHeaders();
        statusCode = HttpStatusCode.OK;
        body = new byte[0];
    }

    public ResponseBuilder status(HttpStatusCode statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    public ResponseBuilder header(String key, String value) {
        headers.add(key, value);
        return this;
    }

    public ResponseBuilder contentType(String mimeType) {
        return header("Content-Type", mimeType);
    }

    public ResponseBuilder keepAlive(boolean keep) {
        return header("Connection", keep ? "keep-alive" : "close");
    }

    public ResponseBuilder body(byte[] content) {
        this.body = (content != null) ? content : new byte[0];

        return this;
    }

    public ResponseBuilder body(String content) {
        if (content == null) {
            this.body = new byte[0];
        } else {
            this.body = content.getBytes(StandardCharsets.UTF_8);
        }

        return this;
    }

    public ByteBuffer buildResponse() {
        setAutoHeaders();

        StringBuilder head = new StringBuilder();

        head.append("HTTP/1.1 ")
                .append(statusCode.getCode() + " ")
                .append(statusCode.getReasonPhrase())
                .append("\r\n");

        head.append(headers.toString()).append("\r\n");

        byte[] headerBytes = head.toString().getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(headerBytes.length + body.length);
        buffer.put(headerBytes);
        buffer.put(body);

        buffer.flip();
        return buffer;
    }

    private void setAutoHeaders() {
        if (!headers.has("date")) {
            headers.add("date", ZonedDateTime.now(ZoneId.of("GMT")).format(DATE_FORMAT));
        }

        if (!headers.has("Connection")) {
            headers.add("Connection", "keep-alive");
        }

        headers.add("Content-Length", String.valueOf(body.length));
    }
}
