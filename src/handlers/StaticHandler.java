package handlers;

import config.ServerConfig;
import http.HttpRequest;
import http.HttpResponse;
import http.HttpMethod;
import http.HttpStatusCode;
import http.IRequestHandler;
import utils.MimeTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class StaticHandler implements IRequestHandler {
    private final String documentRoot;
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB

    public StaticHandler(ServerConfig config) {
        this.documentRoot = config.getRoot();
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) {
        HttpMethod method = request.getMethod();

        // Only support GET and HEAD
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            HttpResponse errorResponse = HttpResponse.error405(method.toString());
            response.status(errorResponse.toByteBuffer().array() != null ? HttpStatusCode.METHOD_NOT_ALLOWED : HttpStatusCode.METHOD_NOT_ALLOWED)
                    .header("Allow", "GET, HEAD")
                    .contentType("text/html; charset=UTF-8")
                    .body(buildErrorHtml("405", "Method Not Allowed",
                            "The HTTP method " + method + " is not supported for this resource"));
            return;
        }

        String requestPath = request.getPath();

        // Resolve and validate path
        Path filePath;
        try {
            filePath = resolveAndValidatePath(requestPath);
        } catch (SecurityException e) {
            System.err.println("Security violation: " + e.getMessage());
            response.status(HttpStatusCode.FORBIDDEN)
                    .contentType("text/html; charset=UTF-8")
                    .body(buildErrorHtml("403", "Forbidden",
                            "Access to the requested resource is forbidden: " + requestPath));
            return;
        } catch (IOException e) {
            response.status(HttpStatusCode.NOT_FOUND)
                    .contentType("text/html; charset=UTF-8")
                    .body(buildErrorHtml("404", "Not Found",
                            "The requested resource was not found: " + requestPath));
            return;
        }

        // Check if directory
        if (Files.isDirectory(filePath)) {
            Path indexPath = resolveDirectoryIndex(filePath);
            if (indexPath == null) {
                System.err.println("Directory without index.html: " + filePath);
                response.status(HttpStatusCode.FORBIDDEN)
                        .contentType("text/html; charset=UTF-8")
                        .body(buildErrorHtml("403", "Forbidden",
                                "Directory listing is not allowed"));
                return;
            }
            filePath = indexPath;
        }

        // Check if readable
        if (!Files.isReadable(filePath)) {
            System.err.println("File not readable: " + filePath);
            response.status(HttpStatusCode.FORBIDDEN)
                    .contentType("text/html; charset=UTF-8")
                    .body(buildErrorHtml("403", "Forbidden",
                            "The requested resource cannot be read"));
            return;
        }

        // Check file size
        long fileSize;
        try {
            fileSize = Files.size(filePath);
            if (fileSize > MAX_FILE_SIZE) {
                System.err.println("File too large: " + filePath + " (" + fileSize + " bytes)");
                response.status(HttpStatusCode.INTERNAL_SERVER_ERROR)
                        .contentType("text/html; charset=UTF-8")
                        .body(buildErrorHtml("500", "Internal Server Error",
                                "The requested file is too large"));
                return;
            }
        } catch (IOException e) {
            System.err.println("Error getting file size: " + e.getMessage());
            response.status(HttpStatusCode.INTERNAL_SERVER_ERROR)
                    .contentType("text/html; charset=UTF-8")
                    .body(buildErrorHtml("500", "Internal Server Error",
                            "Error accessing the requested resource"));
            return;
        }

        // Read file
        byte[] fileBytes;
        try {
            fileBytes = readFile(filePath);
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            e.printStackTrace();
            response.status(HttpStatusCode.INTERNAL_SERVER_ERROR)
                    .contentType("text/html; charset=UTF-8")
                    .body(buildErrorHtml("500", "Internal Server Error",
                            "Error reading the requested resource: " + e.getMessage()));
            return;
        }

        // Determine MIME type
        String filename = filePath.getFileName().toString();
        String mimeType = MimeTypes.fromFilename(filename);

        // Add charset for text types
        if (mimeType.startsWith("text/") || mimeType.equals("application/json")) {
            mimeType += "; charset=UTF-8";
        }

        // Build successful response
        response.status(HttpStatusCode.OK)
                .contentType(mimeType);

        // For GET: include body. For HEAD: set Content-Length but no body
        if (method == HttpMethod.GET) {
            response.body(fileBytes);
        } else {
            // HEAD request: manually set Content-Length to match what GET would return
            response.header("Content-Length", String.valueOf(fileBytes.length));
        }

        System.out.println("Serving: " + filePath + " (" + fileSize + " bytes, " + mimeType + ")");
    }

    /**
     * Resolve and validate file path, ensuring it's within document root
     * @throws SecurityException if path traversal attempt detected
     * @throws IOException if path doesn't exist
     */
    private Path resolveAndValidatePath(String requestPath) throws SecurityException, IOException {
        // Combine document root with request path
        Path basePath = Paths.get(documentRoot).toRealPath();

        // Remove leading slash from request path
        if (requestPath.startsWith("/")) {
            requestPath = requestPath.substring(1);
        }

        // Handle empty path (root)
        if (requestPath.isEmpty()) {
            requestPath = ".";
        }

        // Resolve the requested path
        Path requestedPath = basePath.resolve(requestPath).normalize();

        // Convert to canonical path (follows symlinks)
        Path canonicalPath;
        try {
            canonicalPath = requestedPath.toRealPath();
        } catch (IOException e) {
            // File doesn't exist
            throw new IOException("File not found: " + requestPath);
        }

        // Verify the canonical path is still within document root
        if (!canonicalPath.startsWith(basePath)) {
            throw new SecurityException("Path traversal attempt detected: " + requestPath);
        }

        return canonicalPath;
    }

    /**
     * Read file contents into byte array
     */
    private byte[] readFile(Path filePath) throws IOException {
        return Files.readAllBytes(filePath);
    }

    /**
     * Look for index.html in directory
     * @return Path to index.html if found, null otherwise
     */
    private Path resolveDirectoryIndex(Path dirPath) {
        Path indexPath = dirPath.resolve("index.html");
        if (Files.exists(indexPath) && Files.isRegularFile(indexPath)) {
            return indexPath;
        }
        return null;
    }

    /**
     * Build HTML error page
     */
    private String buildErrorHtml(String statusCode, String reasonPhrase, String message) {
        return "<!DOCTYPE html>\n" +
               "<html>\n" +
               "<head>\n" +
               "    <meta charset=\"UTF-8\">\n" +
               "    <title>" + statusCode + " " + reasonPhrase + "</title>\n" +
               "    <style>\n" +
               "        body { font-family: Arial, sans-serif; margin: 40px; }\n" +
               "        h1 { color: #333; }\n" +
               "        p { color: #666; }\n" +
               "    </style>\n" +
               "</head>\n" +
               "<body>\n" +
               "    <h1>" + statusCode + " " + reasonPhrase + "</h1>\n" +
               "    <p>" + escapeHtml(message) + "</p>\n" +
               "    <hr>\n" +
               "    <p><em>JavaNIOServer/1.0</em></p>\n" +
               "</body>\n" +
               "</html>";
    }

    /**
     * Escape HTML special characters
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#x27;");
    }
}
