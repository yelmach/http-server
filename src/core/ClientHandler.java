package core;

import config.ServerConfig;
import handlers.ErrorHandler;
import handlers.Handler;
import http.HttpRequest;
import http.HttpStatusCode;
import http.ParsingResult;
import http.RequestParser;
import http.ResponseBuilder;
import java.io.IOException;
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
                return;
            }

            if (!responseQueue.isEmpty()) {
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
            // TODO: handle exception
        }

        HttpStatusCode statusCode = responseBuilder.getStatusCode() != null ? responseBuilder.getStatusCode()
                : HttpStatusCode.INTERNAL_SERVER_ERROR;
        if (statusCode.isError()) {
            ErrorHandler errorHandler = new ErrorHandler(statusCode, currentConfig);
            errorHandler.handle(currentRequest, responseBuilder);
        }

        keepAlive = currentRequest.shouldKeepAlive();

        responseQueue.add(responseBuilder.buildResponse());
        logger.info("handle request for " + currentConfig.getServerName() + ": " + currentRequest.getMethod() + " "
                + currentRequest.getPath());
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
                        return;
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
        selectionKey.cancel();
        client.close();
    }
}
