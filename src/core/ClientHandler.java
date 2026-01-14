package core;

import config.ServerConfig;
import handlers.ErrorHandler;
import handlers.Handler;
import http.HttpRequest;
import http.HttpStatusCode;
import http.ParsingResult;
import http.RequestParser;
import http.ResponseBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.logging.Logger;
import router.Router;
import utils.ServerLogger;

public class ClientHandler {

    private final SocketChannel client;
    private final SelectionKey selectionKey;
    private final ByteBuffer readBuffer;
    private final RequestParser parser;
    private final Queue<ByteBuffer> responseQueue = new LinkedList<>();
    private boolean keepAlive;
    private long lastActivityTime;
    private final long timeoutMs = 10000;
    private final Logger logger = ServerLogger.get();
    private final List<ServerConfig> virtualHosts;
    private ServerConfig currentConfig;

    // CGI
    private Process cgiProcess;
    private boolean isCgiRunning = false;
    private long cgiStartTime;
    private ResponseBuilder pendingResponseBuilder;
    private HttpRequest pendingRequest;
    private ByteArrayOutputStream cgiOutputBuffer;

    public ClientHandler(SocketChannel clientChannel, SelectionKey selectionKey, List<ServerConfig> virtualHosts) {
        this.client = clientChannel;
        this.selectionKey = selectionKey;
        this.readBuffer = ByteBuffer.allocate(8192);

        int maxBodySize = 10 * 1024 * 1024; // default 10MB
        for (ServerConfig config : virtualHosts) {
            if (config.getMaxBodySize() > maxBodySize) {
                maxBodySize = config.getMaxBodySize();
            }
        }
        this.parser = new RequestParser(maxBodySize);
        this.lastActivityTime = System.currentTimeMillis();
        this.virtualHosts = virtualHosts;
    }

    public void read() throws IOException {
        lastActivityTime = System.currentTimeMillis();
        readBuffer.clear();
        int bytesRead = client.read(readBuffer);

        if (bytesRead == -1) {
            close();
            logger.info("Client disconnected.");
            return;
        }

        if (bytesRead > 0) {
            readBuffer.flip();

            ParsingResult result = parser.parse(readBuffer);

            while (result.isComplete()) {
                HttpRequest currentRequest = result.getRequest();
                handleRequest(currentRequest);

                parser.resetState();
                result = parser.parse(ByteBuffer.allocate(0));
            }

            if (result.isError()) {
                logger.severe("Parsing error: " + result.getErrorMessage());
                close();
            }

            // Only switch to WRITE if we aren't waiting for a CGI process
            if (!responseQueue.isEmpty() && !isCgiRunning) {
                selectionKey.interestOps(SelectionKey.OP_WRITE);
            }
        }
    }

    private void handleRequest(HttpRequest currentRequest) {
        String hostHeader = currentRequest.getHeaders().get("Host");
        this.currentConfig = resolveConfig(hostHeader);

        Router router = new Router();
        Handler handler = router.route(currentRequest, currentConfig);
        ResponseBuilder responseBuilder = new ResponseBuilder();

        try {
            handler.handle(currentRequest, responseBuilder);
        } catch (Exception e) {
            logger.severe("Handler Error: " + e.getMessage());
            responseBuilder.status(HttpStatusCode.INTERNAL_SERVER_ERROR);
        }

        // Check if the Handler started a CGI process (Non-Blocking)
        if (responseBuilder.hasPendingProcess()) {
            this.cgiProcess = responseBuilder.getPendingProcess();
            this.pendingResponseBuilder = responseBuilder;
            this.pendingRequest = currentRequest;
            this.isCgiRunning = true;
            this.cgiStartTime = System.currentTimeMillis();
            this.cgiOutputBuffer = new ByteArrayOutputStream();

            // We pause InterestOps because we are waiting for the Process, not the Socket
            selectionKey.interestOps(0);
            return;
        }

        finishRequest(responseBuilder, currentRequest);
    }

    private void finishRequest(ResponseBuilder responseBuilder, HttpRequest currentRequest) {
        HttpStatusCode statusCode = responseBuilder.getStatusCode() != null ? responseBuilder.getStatusCode()
                : HttpStatusCode.INTERNAL_SERVER_ERROR;

        if (statusCode.isError()) {
            ErrorHandler errorHandler = new ErrorHandler(statusCode, currentConfig);
            errorHandler.handle(currentRequest, responseBuilder);
        }

        keepAlive = (currentRequest != null) && currentRequest.shouldKeepAlive();

        responseQueue.add(responseBuilder.buildResponse());
        logger.info("Request handled: " + statusCode + " for " + currentConfig.getServerName());

        if (selectionKey.isValid()) {
            selectionKey.interestOps(SelectionKey.OP_WRITE);
        }
    }

    // Called by Server.java loop to check if the CGI process has finished.
    public void checkCgiProcess() {
        if (!isCgiRunning)
            return;

        // 1. We read whatever is available right now
        try {
            InputStream is = cgiProcess.getInputStream();
            while (is.available() > 0) {
                byte[] chunk = new byte[8192];
                int read = is.read(chunk);
                if (read > 0) {
                    cgiOutputBuffer.write(chunk, 0, read);
                }
            }
        } catch (IOException e) {
            logger.severe("Error reading CGI stream: " + e.getMessage());
        }

        // 2. Check Timeout
        if (System.currentTimeMillis() - cgiStartTime > 5000) {
            logger.severe("CGI Execution Timed Out");
            cgiProcess.destroyForcibly();
            isCgiRunning = false;

            pendingResponseBuilder.status(HttpStatusCode.INTERNAL_SERVER_ERROR).body("CGI Timeout");
            finishRequest(pendingResponseBuilder, pendingRequest);
            return;
        }

        // 3. Poll Process Status
        if (!cgiProcess.isAlive()) {
            isCgiRunning = false;

            try {
                // Read any remaining bytes that arrived
                InputStream is = cgiProcess.getInputStream();
                is.transferTo(cgiOutputBuffer);

                byte[] fullOutput = cgiOutputBuffer.toByteArray();

                // Set body (Raw output)
                pendingResponseBuilder.body(fullOutput);
                pendingResponseBuilder.status(HttpStatusCode.OK);

            } catch (IOException e) {
                logger.severe("Error finalizing CGI output: " + e.getMessage());
                pendingResponseBuilder.status(HttpStatusCode.INTERNAL_SERVER_ERROR);
            }

            finishRequest(pendingResponseBuilder, pendingRequest);
        }
    }

    public void write() throws IOException {
        lastActivityTime = System.currentTimeMillis();

        ByteBuffer responseBuffer = responseQueue.peek();

        if (responseBuffer != null) {
            client.write(responseBuffer);

            if (!responseBuffer.hasRemaining()) {
                responseQueue.poll();

                if (responseQueue.isEmpty()) {
                    if (keepAlive) {
                        selectionKey.interestOps(SelectionKey.OP_READ);
                    } else {
                        close();
                    }
                }
            }
        }
    }

    private ServerConfig resolveConfig(String hostHeader) {
        String hostName = (hostHeader != null && hostHeader.contains(":"))
                ? hostHeader.split(":")[0]
                : hostHeader;

        for (ServerConfig cfg : virtualHosts) {
            if (cfg.getServerName().equalsIgnoreCase(hostName)) {
                return cfg;
            }
        }

        return virtualHosts.stream()
                .filter(ServerConfig::isDefault)
                .findFirst()
                .orElse(virtualHosts.get(0));
    }

    public SocketAddress getIpAddress() {
        try {
            return this.client.getRemoteAddress();

        } catch (IOException e) {
            logger.severe("Error getting IP address: " + e.getMessage());
            return null;
        }
    }

    public boolean isTimedOut() {
        long currentTime = System.currentTimeMillis();
        return (currentTime - lastActivityTime) > timeoutMs;
    }

    private void close() throws IOException {
        if (cgiProcess != null && cgiProcess.isAlive()) {
            cgiProcess.destroyForcibly();
        }
        selectionKey.cancel();
        client.close();
    }
}