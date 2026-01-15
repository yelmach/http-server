package handlers;

import config.RouteConfig;
import http.HttpRequest;
import http.HttpStatusCode;
import http.MultipartPart;
import http.ResponseBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class UploadHandler implements Handler {

    private final RouteConfig route;
    private final File resource;

    public UploadHandler(RouteConfig route, File resource) {
        this.route = route;
        this.resource = resource;
    }

    @Override
    public void handle(HttpRequest request, ResponseBuilder response) {
        try {
            if (request.getMultipartParts() != null) {
                handleMultipartUpload(request, response);
            } else {
                handleRawBinaryUpload(request, response);
            }

        } catch (SecurityException e) {
            response.status(HttpStatusCode.FORBIDDEN)
                    .body("Access denied: " + e.getMessage());

        } catch (IllegalArgumentException e) {
            response.status(HttpStatusCode.BAD_REQUEST)
                    .body("Invalid request: " + e.getMessage());

        } catch (Exception e) {
            e.printStackTrace(); // Log error for debugging
            response.status(HttpStatusCode.INTERNAL_SERVER_ERROR)
                    .body("Upload failed: " + e.getMessage());
        }
    }

    private void handleMultipartUpload(HttpRequest request, ResponseBuilder response) throws Exception {
        File uploadDir = resource;

        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        } else if (!uploadDir.isDirectory()) {
            uploadDir = uploadDir.getParentFile();
        }

        List<MultipartPart> parts = request.getMultipartParts();
        int savedCount = 0;

        if (parts != null) {
            for (MultipartPart part : parts) {
                if (part.isFile()) {
                    saveFilePart(part, uploadDir);
                    savedCount++;
                }
            }
        }

        if (savedCount == 0) {
            response.status(HttpStatusCode.BAD_REQUEST)
                    .contentType("text/plain")
                    .body("No files found in upload");
            return;
        }

        response.status(HttpStatusCode.CREATED)
                .contentType("text/plain")
                .body(savedCount + " file(s) uploaded successfully");
    }

    private void handleRawBinaryUpload(HttpRequest request, ResponseBuilder response)
            throws Exception {

        File targetFile = resource;

        // Target must be a file path, not a directory
        if (targetFile.isDirectory()) {
            response.status(HttpStatusCode.BAD_REQUEST)
                    .body("Filename required in path");
            return;
        }

        if (!isPathSafe(targetFile, route.getRoot())) {
            throw new SecurityException("Path traversal detected");
        }

        // Parent directory must exist
        File parentDir = targetFile.getParentFile();
        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory()) {
            response.status(HttpStatusCode.NOT_FOUND)
                    .body("Directory not found: " + parentDir.getName());
            return;
        }

        if (request.getBodyTempFile() != null) {
            Files.move(request.getBodyTempFile().toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } else {
            byte[] body = request.getBody();
            if (body == null || body.length == 0) {
                response.status(HttpStatusCode.BAD_REQUEST).body("Empty body");
                return;
            }
            Files.write(targetFile.toPath(), body);
        }

        response.status(HttpStatusCode.CREATED)
                .contentType("text/plain")
                .body("File uploaded: " + targetFile.getName());
    }

    private void saveFilePart(MultipartPart part, File uploadDir) throws Exception {
        String sanitized = sanitizeFilename(part.getFilename());
        File targetFile = new File(uploadDir, sanitized);

        if (!isPathSafe(targetFile, uploadDir.getAbsolutePath())) {
            throw new SecurityException("Path traversal in filename: " + part.getFilename());
        }

        if (part.getTempFile() != null) {
            Files.move(part.getTempFile().toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } else if (part.getContent() != null) {
            Files.write(targetFile.toPath(), part.getContent());
        }
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "unknown_file";
        }
        filename = filename.replaceAll(".*[/\\\\]", "");
        filename = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (filename.startsWith("."))
            filename = "_" + filename.substring(1);
        if (filename.isEmpty())
            return "unnamed_file";
        return filename;
    }

    private boolean isPathSafe(File targetFile, String rootPath) {
        try {
            String targetCanonical = targetFile.getCanonicalPath();
            String rootCanonical = new File(rootPath).getCanonicalPath();
            return targetCanonical.startsWith(rootCanonical);
        } catch (IOException e) {
            return false;
        }
    }
}