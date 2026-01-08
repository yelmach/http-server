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
        System.err.println(route.getCgiExtension());
        if (!resource.exists()) {
            response.status(HttpStatusCode.NOT_FOUND)
                    .body("CGI script not found");
            logger.severe("CGI script not found!");
            return;
        }

        if (!resource.canExecute()) {
            response.status(HttpStatusCode.INTERNAL_SERVER_ERROR)
                    .body("CGI script not executable");
            logger.severe("CGI script not executable!");
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(resource.getAbsolutePath());
        pb.directory(resource.getParentFile());

        Process process = pb.start();

        ByteArrayOutputStream rawOutput = new ByteArrayOutputStream();
        try (InputStream is = process.getInputStream()) {
            is.transferTo(rawOutput);
        } catch (IOException e) {
            logger.severe("STreaming CGI output failed!");
        }

        String cgiResponse = rawOutput.toString(StandardCharsets.UTF_8);

        response.status(HttpStatusCode.OK)
                .body(cgiResponse);
    }
}
