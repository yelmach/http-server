package session;

public class Cookie {
    private final String name;
    private final String value;

    public Cookie(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() { return name; }
    public String getValue() { return value; }

    // Factory method to parse "key=value"
    public static Cookie parse(String cookieString) {
        if (cookieString == null || cookieString.trim().isEmpty()) return null;
        String[] parts = cookieString.split("=", 2);
        if (parts.length == 2) {
            return new Cookie(parts[0].trim(), parts[1].trim());
        }
        return null;
    }

    // Format for response header
    @Override
    public String toString() {
        return name + "=" + value + "; Path=/; HttpOnly";
    }
}