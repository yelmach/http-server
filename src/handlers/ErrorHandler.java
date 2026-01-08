package handlers;

import config.ServerConfig;
import http.HttpRequest;
import http.HttpStatusCode;
import http.ResponseBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ErrorHandler implements Handler {

    private final HttpStatusCode statusCode;
    private final ServerConfig serverConfig;

    public ErrorHandler(HttpStatusCode statusCode, ServerConfig serverConfig) {
        this.statusCode = statusCode;
        this.serverConfig = serverConfig;
    }

    @Override
    public void handle(HttpRequest request, ResponseBuilder response) {
        response.status(statusCode);
        response.contentType("text/html");

        String errorPage = getCustomErrorPage();

        if (errorPage != null) {
            response.body(errorPage);
        } else {
            response.body(generateDefaultErrorPage());
        }
    }

    private String getCustomErrorPage() {
        if (serverConfig.getErrorPages() == null) {
            return null;
        }

        String errorPagePath = serverConfig.getErrorPages()
                .get(String.valueOf(statusCode.getCode()));

        if (errorPagePath == null) {
            return null;
        }

        File errorFile = new File(errorPagePath);

        if (!errorFile.exists() || !errorFile.isFile()) {
            return null;
        }

        try {
            return new String(Files.readAllBytes(Paths.get(errorPagePath)));
        } catch (IOException e) {
            return null;
        }
    }

    private String generateDefaultErrorPage() {
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <title>" + statusCode.getCode() + " " + statusCode.getReasonPhrase() + "</title>\n" +
                "    <style>\n" +
                "        body { font-family: Arial, sans-serif; text-align: center; padding: 50px; }\n" +
                "        h1 { font-size: 50px; margin: 0; }\n" +
                "        p { font-size: 20px; color: #666; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <h1>" + statusCode.getCode() + "</h1>\n" +
                "    <p>" + statusCode.getReasonPhrase() + "</p>\n" +
                "</body>\n" +
                "</html>";
    }
}