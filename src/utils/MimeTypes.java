package utils;

import java.util.HashMap;
import java.util.Map;

public class MimeTypes {
    private static final Map<String, String> MIME_MAP = new HashMap<>();
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    static {
        // Text formats
        MIME_MAP.put("html", "text/html");
        MIME_MAP.put("htm", "text/html");
        MIME_MAP.put("css", "text/css");
        MIME_MAP.put("js", "text/javascript");
        MIME_MAP.put("txt", "text/plain");
        MIME_MAP.put("xml", "text/xml");
        MIME_MAP.put("csv", "text/csv");

        // Images
        MIME_MAP.put("png", "image/png");
        MIME_MAP.put("jpg", "image/jpeg");
        MIME_MAP.put("jpeg", "image/jpeg");
        MIME_MAP.put("gif", "image/gif");
        MIME_MAP.put("svg", "image/svg+xml");
        MIME_MAP.put("ico", "image/x-icon");
        MIME_MAP.put("webp", "image/webp");
        MIME_MAP.put("bmp", "image/bmp");

        // Application formats
        MIME_MAP.put("json", "application/json");
        MIME_MAP.put("pdf", "application/pdf");
        MIME_MAP.put("zip", "application/zip");
        MIME_MAP.put("wasm", "application/wasm");
        MIME_MAP.put("gz", "application/gzip");
        MIME_MAP.put("tar", "application/x-tar");

        // Fonts
        MIME_MAP.put("woff", "font/woff");
        MIME_MAP.put("woff2", "font/woff2");
        MIME_MAP.put("ttf", "font/ttf");
        MIME_MAP.put("otf", "font/otf");
        MIME_MAP.put("eot", "application/vnd.ms-fontobject");

        // Audio/Video
        MIME_MAP.put("mp3", "audio/mpeg");
        MIME_MAP.put("mp4", "video/mp4");
        MIME_MAP.put("webm", "video/webm");
        MIME_MAP.put("ogg", "audio/ogg");
        MIME_MAP.put("wav", "audio/wav");
        MIME_MAP.put("avi", "video/x-msvideo");
    }

    /**
     * Get MIME type from file extension (without dot)
     * @param extension File extension without the dot (e.g., "html", "jpg")
     * @return MIME type string, or default if unknown
     */
    public static String getMimeType(String extension) {
        if (extension == null || extension.isEmpty()) {
            return DEFAULT_MIME_TYPE;
        }
        return MIME_MAP.getOrDefault(extension.toLowerCase(), DEFAULT_MIME_TYPE);
    }

    /**
     * Get MIME type from filename by extracting extension
     * @param filename Full filename (e.g., "index.html", "path/to/file.css")
     * @return MIME type string, or default if no extension or unknown
     */
    public static String fromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return DEFAULT_MIME_TYPE;
        }

        String extension = getExtension(filename);
        return getMimeType(extension);
    }

    /**
     * Extract file extension from filename
     * @param filename Full filename
     * @return Extension without dot, or empty string if no extension
     */
    private static String getExtension(String filename) {
        if (filename == null) {
            return "";
        }

        // Get just the filename if path is included
        int lastSlash = filename.lastIndexOf('/');
        if (lastSlash >= 0) {
            filename = filename.substring(lastSlash + 1);
        }

        // Find last dot
        int lastDot = filename.lastIndexOf('.');

        // No dot, or dot is first character (hidden file), or dot is last character
        if (lastDot <= 0 || lastDot == filename.length() - 1) {
            return "";
        }

        return filename.substring(lastDot + 1);
    }
}
