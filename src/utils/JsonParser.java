package utils;

import java.util.*;

public class JsonParser {

    private int index = 0;
    private String json;

    public Map<String, Object> parse(String jsonString) {
        this.json = jsonString.trim();
        this.index = 0;
        return (Map<String, Object>) parseValue();
    }

    private Object parseValue() {
        skipWhitespace();
        char current = json.charAt(index);

        if (current == '{') {
            return parseObject();
        } else if (current == '[') {
            return parseArray();
        } else if (current == '"') {
            return parseString();
        } else if (current == 't' || current == 'f') {
            return parseBoolean();
        } else if (current == 'n') {
            return parseNull();
        } else if (current == '-' || Character.isDigit(current)) {
            return parseNumber();
        }

        throw new RuntimeException("Unexpected character: " + current);
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        index++; // skip '{'
        skipWhitespace();

        if (json.charAt(index) == '}') {
            index++;
            return map;
        }

        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();

            if (json.charAt(index) != ':') {
                throw new RuntimeException("Expected ':' after key");
            }
            index++; // skip ':'

            skipWhitespace();
            Object value = parseValue();
            map.put(key, value);

            skipWhitespace();
            char next = json.charAt(index);

            if (next == '}') {
                index++;
                break;
            } else if (next == ',') {
                index++;
            } else {
                throw new RuntimeException("Expected ',' or '}' in object");
            }
        }

        return map;
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        index++; // skip '['
        skipWhitespace();

        if (json.charAt(index) == ']') {
            index++;
            return list;
        }

        while (true) {
            skipWhitespace();
            list.add(parseValue());
            skipWhitespace();

            char next = json.charAt(index);
            if (next == ']') {
                index++;
                break;
            } else if (next == ',') {
                index++;
            } else {
                throw new RuntimeException("Expected ',' or ']' in array");
            }
        }

        return list;
    }

    private String parseString() {
        StringBuilder sb = new StringBuilder();
        index++; // skip opening '"'

        while (index < json.length()) {
            char c = json.charAt(index);

            if (c == '"') {
                index++;
                return sb.toString();
            } else if (c == '\\') {
                index++;
                if (index >= json.length()) {
                    throw new RuntimeException("Unterminated string");
                }
                char escaped = json.charAt(index);
                switch (escaped) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        // Unicode escape
                        String hex = json.substring(index + 1, index + 5);
                        sb.append((char) Integer.parseInt(hex, 16));
                        index += 4;
                        break;
                    default:
                        throw new RuntimeException("Invalid escape sequence: \\" + escaped);
                }
                index++;
            } else {
                sb.append(c);
                index++;
            }
        }

        throw new RuntimeException("Unterminated string");
    }

    private Number parseNumber() {
        int start = index;

        if (json.charAt(index) == '-') {
            index++;
        }

        while (index < json.length() && (Character.isDigit(json.charAt(index)) ||
                json.charAt(index) == '.' || json.charAt(index) == 'e' ||
                json.charAt(index) == 'E' || json.charAt(index) == '+' ||
                json.charAt(index) == '-')) {
            index++;
        }

        String numberStr = json.substring(start, index);

        if (numberStr.contains(".") || numberStr.contains("e") || numberStr.contains("E")) {
            return Double.parseDouble(numberStr);
        } else {
            try {
                return Integer.parseInt(numberStr);
            } catch (NumberFormatException e) {
                return Long.parseLong(numberStr);
            }
        }
    }

    private Boolean parseBoolean() {
        if (json.startsWith("true", index)) {
            index += 4;
            return true;
        } else if (json.startsWith("false", index)) {
            index += 5;
            return false;
        }
        throw new RuntimeException("Invalid boolean value");
    }

    private Object parseNull() {
        if (json.startsWith("null", index)) {
            index += 4;
            return null;
        }
        throw new RuntimeException("Invalid null value");
    }

    private void skipWhitespace() {
        while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
            index++;
        }
    }
}
