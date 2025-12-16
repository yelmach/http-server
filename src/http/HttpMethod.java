package http;

import exceptions.InvalidMethodException;

public enum HttpMethod {
    GET, POST, DELETE;

    public static HttpMethod fromString(String method) throws InvalidMethodException {
        try {
            return valueOf(method.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidMethodException("Invalid HTTP method: " + method);
        }
    }

    // Helper for tests - doesn't throw checked exception
    public static HttpMethod fromStringUnsafe(String method) {
        try {
            return fromString(method);
        } catch (InvalidMethodException e) {
            return null;
        }
    }
}
