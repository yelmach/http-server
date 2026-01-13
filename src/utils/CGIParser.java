package utils;

import http.HttpRequest;
import http.HttpStatusCode;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class CGIParser {

    public static CGIResult parse(String raw) {

        Map<String, String> headers = new HashMap<>();
        HttpStatusCode status = HttpStatusCode.OK;

        String[] parts = raw.split("\\r?\\n\\r?\\n", 2);

        if (parts.length <= 1) {
            return new CGIResult(status, null, raw);
        }

        String headerBlock = parts.length > 0 ? parts[0] : "";
        String body = parts.length == 2 ? parts[1] : "";

        for (String line : headerBlock.split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            int idx = line.indexOf(':');
            if (idx == -1) {
                continue;
            }

            String key = line.substring(0, idx).trim();
            String val = line.substring(idx + 1).trim();

            if (key.equalsIgnoreCase("Status")) {
                status = HttpStatusCode.fromCode(Integer.parseInt(val.split(" ")[0]));
            } else {
                headers.put(key, val);
            }
        }

        return new CGIResult(status, headers, body);
    }

    public static String extractPathInfo(File script, String uri) {
        int idx = uri.indexOf(script.getName());
        if (idx == -1) {
            return "";
        }
        int start = idx + script.getName().length();
        return start < uri.length() ? uri.substring(start) : "";
    }

    public static String header(HttpRequest r, String k) {
        return Optional.ofNullable(r.getHeaders().get(k)).orElse("");
    }

    public static String header(HttpRequest r, String k, String d) {
        return Optional.ofNullable(r.getHeaders().get(k)).orElse(d);
    }
}
