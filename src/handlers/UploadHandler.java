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
            response.status(HttpStatusCode.INTERNAL_SERVER_ERROR);
        }
    }

    private void handleMultipartUpload(HttpRequest request, ResponseBuilder response) throws Exception {
        File uploadDir = resource;

        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }

        List<MultipartPart> parts = request.getMultipartParts();
        int savedCount = 0;

        for (MultipartPart part : parts) {
            if (part.isFile()) {
                saveFilePart(part, uploadDir);
                savedCount++;
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

        if (!isPathSafe(targetFile, route.getRoot())) {
            throw new SecurityException("Path traversal detected");
        }

        File parentDir = targetFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        if (request.getBodyTempFile() != null) {
            Files.move(request.getBodyTempFile().toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.write(targetFile.toPath(), request.getBody());
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

        Files.move(part.getTempFile().toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            throw new IllegalArgumentException("Empty filename");
        }

        filename = filename.replaceAll(".*[/\\\\]", "");
        filename = filename.replaceAll("[^a-zA-Z0-9._-]", "_");

        if (filename.startsWith(".")) {
            filename = "_" + filename.substring(1);
        }

        if (filename.isEmpty()) {
            throw new IllegalArgumentException("Filename invalid after sanitization");
        }

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
