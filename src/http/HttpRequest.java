package http;

import java.nio.charset.StandardCharsets;

public class HttpRequest {
    private HttpMethod method;
    private String path;
    private String httpVersion;
    private HttpHeaders headers;
    private byte[] body;
    private String queryString;

    public HttpRequest() {
        this.headers = new HttpHeaders();
    }

    public HttpMethod getMethod() {
        return method;
    }

    public void setMethod(HttpMethod method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getHttpVersion() {
        return httpVersion;
    }

    public void setHttpVersion(String httpVersion) {
        this.httpVersion = httpVersion;
    }

    public HttpHeaders getHeaders() {
        return headers;
    }

    public void setHeaders(HttpHeaders headers) {
        this.headers = headers;
    }

    public void addHeader(String key, String value) {
        this.headers.add(key, value);
    }

    public byte[] getBody() {
        return body;
    }

    public String getBodyAsString() {
        return body != null ? new String(body, StandardCharsets.UTF_8) : null;
    }

    public void setBody(byte[] body) {
        this.body = body;
    }

    public String getQueryString() {
        return queryString;
    }

    public void setQueryString(String queryString) {
        this.queryString = queryString;
    }
}
