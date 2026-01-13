package utils;

import http.HttpStatusCode;
import java.util.Map;

public class CGIResult {

    final HttpStatusCode status;
    public final Map<String, String> headers;
    final String body;

    CGIResult(HttpStatusCode status, Map<String, String> headers, String body) {
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }

}
