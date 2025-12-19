package http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpResponse {
    private static final String HTTP_VERSION = "HTTP/1.1";
    private static final String SERVER_NAME = "JavaNIOServer/1.0";
    private static final DateTimeFormatter HTTP_DATE_FORMAT = DateTimeFormatter.RFC_1123_DATE_TIME;

    private HttpStatusCode statusCode;
    private Map<String, String> headers;
    private byte[] body;
    private boolean keepAlive;

    public HttpResponse() {
        this.statusCode = HttpStatusCode.OK;
        this.headers = new LinkedHashMap<>(); // Preserve insertion order
        this.body = null;
        this.keepAlive = true;
    }

    /**
     * Set HTTP status code
     */
    public HttpResponse status(HttpStatusCode code) {
        this.statusCode = code;
        return this;
    }

    /**
     * Add a custom header
     */
    public HttpResponse header(String name, String value) {
        this.headers.put(name, value);
        return this;
    }

    /**
     * Set Content-Type header (convenience method)
     */
    public HttpResponse contentType(String mimeType) {
        return header("Content-Type", mimeType);
    }

    /**
     * Set response body from byte array
     */
    public HttpResponse body(byte[] data) {
        this.body = data;
        return this;
    }

    /**
     * Set response body from string (UTF-8)
     */
    public HttpResponse body(String text) {
        this.body = text.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    /**
     * Set keep-alive flag
     */
    public HttpResponse keepAlive(boolean keep) {
        this.keepAlive = keep;
        return this;
    }

    /**
     * Convert response to ByteBuffer for writing to socket
     */
    public ByteBuffer toByteBuffer() {
        setAutoHeaders();
        byte[] responseBytes = buildResponse();
        return ByteBuffer.wrap(responseBytes);
    }

    /**
     * Factory method: 404 Not Found error
     */
    public static HttpResponse error404(String path) {
        String message = "The requested resource was not found: " + path;
        String html = buildErrorHtml("404", "Not Found", message);

        return new HttpResponse()
                .status(HttpStatusCode.NOT_FOUND)
                .contentType("text/html; charset=UTF-8")
                .body(html);
    }

    /**
     * Factory method: 403 Forbidden error
     */
    public static HttpResponse error403(String path) {
        String message = "Access to the requested resource is forbidden: " + path;
        String html = buildErrorHtml("403", "Forbidden", message);

        return new HttpResponse()
                .status(HttpStatusCode.FORBIDDEN)
                .contentType("text/html; charset=UTF-8")
                .body(html);
    }

    /**
     * Factory method: 405 Method Not Allowed error
     */
    public static HttpResponse error405(String method) {
        String message = "The HTTP method " + method + " is not supported for this resource";
        String html = buildErrorHtml("405", "Method Not Allowed", message);

        return new HttpResponse()
                .status(HttpStatusCode.METHOD_NOT_ALLOWED)
                .header("Allow", "GET, HEAD")
                .contentType("text/html; charset=UTF-8")
                .body(html);
    }

    /**
     * Factory method: 500 Internal Server Error
     */
    public static HttpResponse error500(String message) {
        String html = buildErrorHtml("500", "Internal Server Error",
                "The server encountered an error: " + message);

        return new HttpResponse()
                .status(HttpStatusCode.INTERNAL_SERVER_ERROR)
                .contentType("text/html; charset=UTF-8")
                .body(html);
    }

    /**
     * Set automatic headers if not already present
     */
    private void setAutoHeaders() {
        // Content-Length
        int bodyLength = (body != null) ? body.length : 0;
        if (!headers.containsKey("Content-Length")) {
            headers.put("Content-Length", String.valueOf(bodyLength));
        }

        // Date (RFC 1123 format)
        if (!headers.containsKey("Date")) {
            String dateValue = ZonedDateTime.now(ZoneId.of("GMT"))
                    .format(HTTP_DATE_FORMAT);
            headers.put("Date", dateValue);
        }

        // Server
        if (!headers.containsKey("Server")) {
            headers.put("Server", SERVER_NAME);
        }

        // Connection
        if (!headers.containsKey("Connection")) {
            headers.put("Connection", keepAlive ? "keep-alive" : "close");
        }
    }

    /**
     * Build complete HTTP response as byte array
     */
    private byte[] buildResponse() {
        StringBuilder sb = new StringBuilder();

        // Status line
        sb.append(HTTP_VERSION).append(" ")
          .append(statusCode.getCode()).append(" ")
          .append(statusCode.getReasonPhrase())
          .append("\r\n");

        // Headers
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            sb.append(entry.getKey()).append(": ")
              .append(entry.getValue())
              .append("\r\n");
        }

        // Empty line between headers and body
        sb.append("\r\n");

        // Convert headers to bytes
        byte[] headerBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

        // Combine headers and body
        if (body != null && body.length > 0) {
            byte[] responseBytes = new byte[headerBytes.length + body.length];
            System.arraycopy(headerBytes, 0, responseBytes, 0, headerBytes.length);
            System.arraycopy(body, 0, responseBytes, headerBytes.length, body.length);
            return responseBytes;
        } else {
            return headerBytes;
        }
    }

    /**
     * Build HTML error page
     */
    private static String buildErrorHtml(String statusCode, String reasonPhrase, String message) {
        return "<!DOCTYPE html>\n" +
               "<html>\n" +
               "<head>\n" +
               "    <meta charset=\"UTF-8\">\n" +
               "    <title>" + statusCode + " " + reasonPhrase + "</title>\n" +
               "    <style>\n" +
               "        body { font-family: Arial, sans-serif; margin: 40px; }\n" +
               "        h1 { color: #333; }\n" +
               "        p { color: #666; }\n" +
               "    </style>\n" +
               "</head>\n" +
               "<body>\n" +
               "    <h1>" + statusCode + " " + reasonPhrase + "</h1>\n" +
               "    <p>" + escapeHtml(message) + "</p>\n" +
               "    <hr>\n" +
               "    <p><em>JavaNIOServer/1.0</em></p>\n" +
               "</body>\n" +
               "</html>";
    }

    /**
     * Escape HTML special characters
     */
    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#x27;");
    }
}
