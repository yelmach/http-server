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
}
