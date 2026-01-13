package handlers;

import config.RouteConfig;
import http.HttpRequest;
import http.HttpStatusCode;
import http.ResponseBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import utils.CGIParser;
import utils.CGIResult;
import utils.ServerLogger;

public class CGIHandler implements Handler {

    private static final Logger logger = ServerLogger.get();
    private static final int CGI_TIMEOUT_SECONDS = 5;

    private final RouteConfig route;
    private final File script;

    public CGIHandler(RouteConfig route, File script) {
        this.route = route;
        this.script = script;
    }

    @Override
    public void handle(HttpRequest request, ResponseBuilder response) throws IOException {

        if (!script.exists()) {
            response.status(HttpStatusCode.NOT_FOUND);
            return;
        }
        if (!script.canExecute()) {
            response.status(HttpStatusCode.FORBIDDEN);
            return;
        }

        ProcessBuilder pb = new ProcessBuilder("python3", script.getAbsolutePath());
        pb.directory(script.getParentFile());
        pb.redirectErrorStream(false);

        Map<String, String> env = pb.environment();
        buildCgiEnvironment(env, request);

        Process process = pb.start();

        writeRequestBody(process, request);

        CGIResult result = null;

        try {
            result = readCgiOutput(process);
        } catch (IOException e) {
            response.status(HttpStatusCode.REQUEST_TIMEOUT);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.status(HttpStatusCode.REQUEST_TIMEOUT);
            return;
        }

        response.status(result.getStatus());
        if (result.headers != null) {
            result.headers.forEach(response::header);
        }
        response.body(result.getBody());
    }

    private void buildCgiEnvironment(Map<String, String> env, HttpRequest req) {
        env.put("GATEWAY_INTERFACE", "CGI/1.1");
        env.put("SERVER_PROTOCOL", "HTTP/1.1");
        env.put("SERVER_SOFTWARE", "JavaNioServer/1.0");

        env.put("REQUEST_METHOD", req.getMethod().toString());
        env.put("REQUEST_URI", req.getPath());
        env.put("SCRIPT_NAME", script.getName());
        env.put("PATH_INFO", CGIParser.extractPathInfo(script, req.getPath()));
        env.put("QUERY_STRING", Optional.ofNullable(req.getQueryString()).orElse(""));

        env.put("CONTENT_TYPE", CGIParser.header(req, "content-type"));
        env.put("CONTENT_LENGTH", CGIParser.header(req, "content-length", "0"));
    }

    private void writeRequestBody(Process process, HttpRequest request) throws IOException {
        byte[] body = request.getBody();
        if (body == null || body.length == 0) {
            return;
        }

        try (OutputStream os = process.getOutputStream()) {
            os.write(body);
            os.flush();
        }
    }

    private CGIResult readCgiOutput(Process process) throws IOException, InterruptedException {
        boolean finished;
        finished = process.waitFor(CGI_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("CGI timeout");
        }

        String stdout;
        try (InputStream in = process.getInputStream()) {
            stdout = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        String stderr;
        try (InputStream err = process.getErrorStream()) {
            stderr = new String(err.readAllBytes(), StandardCharsets.UTF_8);
        }

        if (process.exitValue() != 0) {
            logger.severe("CGI stderr: " + stderr);
        }

        return CGIParser.parse(stdout);
    }
}
