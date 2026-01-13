package handlers;

import http.HttpRequest;
import http.HttpStatusCode;
import http.ResponseBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import utils.MimeTypes;

public class StaticFileHandler implements Handler {

    private final File file;

    public StaticFileHandler(File file) {
        this.file = file;
    }

    @Override
    public void handle(HttpRequest request, ResponseBuilder response) {
        byte[] content = new byte[] {};

        try {
            content = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            response.status(HttpStatusCode.INTERNAL_SERVER_ERROR);
        }

        String mimeType = MimeTypes.getContentType(file.getName());

        response.status(HttpStatusCode.OK)
                .contentType(mimeType)
                .body(content);
    }
}
