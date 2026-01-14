package handlers;

import http.HttpRequest;
import http.HttpStatusCode;
import http.ResponseBuilder;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import utils.MimeTypes;

public class StaticFileHandler implements Handler {

    private final File file;

    public StaticFileHandler(File file) {
        this.file = file;
    }

    @Override
    public void handle(HttpRequest request, ResponseBuilder response) {
        if (!file.exists() || !file.isFile()) {
            response.status(HttpStatusCode.NOT_FOUND);
            return;
        }

        if (!file.canRead()) {
            response.status(HttpStatusCode.FORBIDDEN).body("Access Denied");
            return;
        }

        String mimeType = MimeTypes.getContentType(file.getName());

        // Add Last-Modified Header of browser caching
        String lastModified = DateTimeFormatter.RFC_1123_DATE_TIME
                .withZone(ZoneId.of("GMT"))
                .format(Instant.ofEpochMilli(file.lastModified()));

        response.status(HttpStatusCode.OK)
                .contentType(mimeType)
                .header("Last-Modified", lastModified)
                .body(file);
    }
}
