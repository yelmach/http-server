package handlers;

import config.RouteConfig;
import http.HttpRequest;
import http.HttpStatusCode;
import http.ResponseBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import utils.ServerLogger;

public class CGIHandler implements Handler {

    private final RouteConfig route;
    private final File resource;
    private static final Logger logger = ServerLogger.get();

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

        ProcessBuilder pb = new ProcessBuilder("python3", resource.getAbsolutePath());
        pb.directory(resource.getParentFile());

        Map<String, String> env = pb.environment();

        env.put("SERVER_PROTOCOL", "HTTP/1.1");
        env.put("REQUEST_METHOD", request.getMethod().toString());
        env.put("QUERY_STRING", request.getQueryString() != null ? request.getQueryString() : "");
        env.put("PATH_INFO", resource.getAbsolutePath());

        String contentType = request.getHeaders().get("content-type");
        String contentLength = request.getHeaders().get("content-length");

        env.put("CONTENT_TYPE", contentType != null ? contentType : "");
        env.put("CONTENT_LENGTH", contentLength != null ? contentLength : "0");

        Process process = pb.start();

        if (request.getBody() != null && request.getBody().length > 0) {
            try (OutputStream os = process.getOutputStream()) {
                os.write(request.getBody());
                os.flush();
            }
        }

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (InputStream is = process.getInputStream()) {
            is.transferTo(stdout);
        }

        try (InputStream es = process.getErrorStream()) {
            es.transferTo(OutputStream.nullOutputStream());
        }

        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
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

        String cgiOutput = stdout.toString(StandardCharsets.UTF_8);

        response.body(cgiOutput);
        response.status(HttpStatusCode.OK);
    }
}
