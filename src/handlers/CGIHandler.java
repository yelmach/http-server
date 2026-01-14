package handlers;

import config.RouteConfig;
import http.HttpRequest;
import http.HttpStatusCode;
import http.ResponseBuilder;
import java.io.*;
import java.util.Map;
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

        try {
            Process process = pb.start();

            if (request.getBody() != null && request.getBody().length > 0) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(request.getBody());
                    os.flush();
                } catch (IOException e) {
                    process.destroy();
                    throw e;
                }
            } else {
                process.getOutputStream().close();
            }

            response.setPendingProcess(process);

        } catch (IOException e) {
            logger.severe("Failed to start CGI: " + e.getMessage());
            response.status(HttpStatusCode.INTERNAL_SERVER_ERROR).body("CGI Launch Failed");
        }
    }
}
