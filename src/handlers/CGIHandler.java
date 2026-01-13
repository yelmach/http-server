package handlers;

import config.RouteConfig;
import http.HttpRequest;
import http.HttpStatusCode;
import http.ResponseBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import utils.ServerLogger;

public class CGIHandler implements Handler {

    private final RouteConfig route;
    private final File resource;
    final static Logger logger = ServerLogger.get();

    public CGIHandler(RouteConfig route, File resource) {
        this.route = route;
        this.resource = resource;
    }

    @Override
    public void handle(HttpRequest request, ResponseBuilder response) throws IOException {

        if (!resource.exists()) {
            logger.severe("CGI script not found");
            response.status(HttpStatusCode.NOT_FOUND).body("CGI script not found");
            return;
        }

        if (!resource.canExecute()) {
            logger.severe("CGI script not executable!");
            response.status(HttpStatusCode.FORBIDDEN).body("CGI script not executable");
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(
                "python3",
                resource.getAbsolutePath()
        );

        pb.environment().put("REQUEST_METHOD", request.getMethod().toString());
        pb.environment().put("QUERY_STRING", request.getQueryString() != null ? request.getQueryString() : "");
        pb.environment().put("CONTENT_LENGTH", request.getContentLength());
        pb.environment().put("SERVER_PROTOCOL", "HTTP/1.1");
        pb.environment().put("PATH_INFO", resource.getAbsolutePath());

        pb.directory(resource.getParentFile());

        Process process = pb.start();

        if (request.getBody() != null && request.getBody().length > 0) {
            try (OutputStream os = process.getOutputStream()) {
                os.write(request.getBody());
                os.flush();
            }
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream is = process.getInputStream()) {
            is.transferTo(output);
        }

        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                logger.severe("CGI execution timeout");
                response.status(HttpStatusCode.INTERNAL_SERVER_ERROR)
                        .body("CGI execution timeout");
                return;
            }
        } catch (InterruptedException e) {
            logger.severe("CGI execution interrupted");
            response.status(HttpStatusCode.INTERNAL_SERVER_ERROR)
                    .body("CGI execution interrupted");
            return;
        }

        if (process.exitValue() != 0) {
            logger.severe("CGI execution failed");
            response.status(HttpStatusCode.INTERNAL_SERVER_ERROR)
                    .body("CGI execution failed");
            return;
        }

        String cgiOutput = output.toString(StandardCharsets.UTF_8);

        response.body(cgiOutput);
        response.status(HttpStatusCode.OK);
    }

}
