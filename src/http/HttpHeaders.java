package http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HttpHeaders {

    private final Map<String, List<String>> headers;

    public HttpHeaders() {
        this.headers = new HashMap<>();
    }

    public void add(String key, String value) {
        String lowerCaseKey = key.toLowerCase();
        headers.computeIfAbsent(lowerCaseKey, k -> new ArrayList<>()).add(value);
    }

    public String get(String key) {
        String lowerCaseKey = key.toLowerCase();
        List<String> values = headers.get(lowerCaseKey);

        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    public List<String> getAll(String key) {
        String lowerCaseKey = key.toLowerCase();
        return headers.get(lowerCaseKey);
    }

    public Boolean has(String key) {
        String lowerCaseKey = key.toLowerCase();
        return headers.containsKey(lowerCaseKey);
    }

    public int getContentLength() {
        String value = get("content-length");
        if (value == null) {
            return -1;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public boolean isChunked() {
        String value = get("Transfer-Encoding");
        return value != null &&
                value.toLowerCase().contains("chunked");
    }

    public String validate() {
        if (!has("host")) {
            return "Missing required Host header";
        }

        List<String> clValues = getAll("content-length");
        if (clValues != null && clValues.size() > 1) {
            String first = clValues.get(0).trim();
            for (String val : clValues) {
                if (!val.trim().equals(first)) {
                    return "Conflicting Content-Length headers detected";
                }
            }
        }

        if (isChunked() && has("content-length")) {
            headers.remove("content-length");
        } else if (has("content-length") && getContentLength() < 0) {
            return "Invalid content-length value";
        }

        if (has("transfer-encoding")) {
            String encoding = get("transfer-encoding");
            if (!"chunked".equals(encoding.trim())) {
                return "Not Implemented: Unsupported Transfer-Encoding: " + encoding;
            }

            if (has("content-length")) {
                headers.remove("content-length");
            }
        } else if (has("content-length") && getContentLength() < 0) {
            return "Invalid Content-Length value";

        }

        return null;
    }
}
