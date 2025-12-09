package http;

public enum HttpMethod {
    GET, POST, DELETE;

    public static HttpMethod fromString(String method) throws InvalidMethodException {
        try {
            return valueOf(method.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidMethodException("Invalid HTTP method: " + method);
        }
    }
}
