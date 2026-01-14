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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.file.StandardOpenOption;
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
    private static final int MAX_CGI_OUTPUT = 10 * 1024 * 1024; // 10MB limit
    private Process cgiProcess;
    private boolean isCgiRunning = false;
    private long cgiStartTime;
    private ResponseBuilder pendingResponseBuilder;
    private HttpRequest pendingRequest;
    private ByteArrayOutputStream cgiOutputBuffer;
    private int cgiOutputSize = 0;

    // Static File Streaming
    private FileChannel fileChannel;
    private long filePosition;
    private long fileSize;

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
                logger.severe("=> Parsing error: " + result.getErrorMessage());

                if (currentConfig == null) {
                    currentConfig = resolveConfig(null);
                }

                HttpStatusCode code = HttpStatusCode.BAD_REQUEST; // Default 400
                String msg = result.getErrorMessage().toLowerCase();

                if (msg.contains("http method")) {
                    code = HttpStatusCode.METHOD_NOT_ALLOWED;
                }

                if (msg.contains("too large") || msg.contains("exceeded") || msg.contains("maximum")) {
                    code = HttpStatusCode.PAYLOAD_TOO_LARGE; // 413
                } else if (msg.contains("unsupported") || msg.contains("not implemented")) {
                    code = HttpStatusCode.NOT_IMPLEMENTED; // 501
                }

                ResponseBuilder errorBuilder = new ResponseBuilder();
                errorBuilder.status(code);
                errorBuilder.header("Connection", "close");

                finishRequest(errorBuilder, null);

                return;
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
            responseBuilder = new ResponseBuilder();
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
            this.cgiOutputSize = 0; // Reset size counter

            // We pause InterestOps because we are waiting for the Process, not the Socket
            selectionKey.interestOps(0);
            return;
        }

        // Check for Static File
        if (responseBuilder.hasFile()) {
            try {
                File file = responseBuilder.getBodyFile();
                this.fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
                this.fileSize = file.length();
                this.filePosition = 0;
            } catch (IOException e) {
                logger.severe("Failed to open file: " + e.getMessage());
                responseBuilder = new ResponseBuilder();
                responseBuilder.status(HttpStatusCode.INTERNAL_SERVER_ERROR).body("Error reading file");
            }
        }

        finishRequest(responseBuilder, currentRequest);
    }

    private void finishRequest(ResponseBuilder responseBuilder, HttpRequest currentRequest) {
        HttpStatusCode statusCode = responseBuilder.getStatusCode() != null ? responseBuilder.getStatusCode()
                : HttpStatusCode.INTERNAL_SERVER_ERROR;

        if (statusCode.isError()) {
            // Close file channel if we are switching to an error page
            if (this.fileChannel != null) {
                try {
                    this.fileChannel.close();
                } catch (IOException ignored) {
                }
                this.fileChannel = null;
            }
            ErrorHandler errorHandler = new ErrorHandler(statusCode, currentConfig);
            errorHandler.handle(currentRequest, responseBuilder);
        }

        keepAlive = (currentRequest != null) && currentRequest.shouldKeepAlive();

        // This puts the Headers (and small body if any) into the queue
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
                    // Check size limit BEFORE writing
                    if (cgiOutputSize + read > MAX_CGI_OUTPUT) {
                        logger.warning("CGI output exceeds limit (" + MAX_CGI_OUTPUT + " bytes), killing process");
                        cgiProcess.destroyForcibly();
                        isCgiRunning = false;

                        pendingResponseBuilder.status(HttpStatusCode.PAYLOAD_TOO_LARGE)
                                .body("CGI output too large (max " + MAX_CGI_OUTPUT + " bytes)");
                        finishRequest(pendingResponseBuilder, pendingRequest);
                        return;
                    }

                    cgiOutputBuffer.write(chunk, 0, read);
                    cgiOutputSize += read;
                }
            }
        } catch (IOException e) {
            logger.severe("Error reading CGI stream: " + e.getMessage());
            cgiProcess.destroyForcibly();
            isCgiRunning = false;
            pendingResponseBuilder.status(HttpStatusCode.INTERNAL_SERVER_ERROR).body("CGI Stream Error");
            finishRequest(pendingResponseBuilder, pendingRequest);
            return;
        }

        // 2. Check Timeout
        if (System.currentTimeMillis() - cgiStartTime > 5000) {
            logger.severe("CGI Execution Timed Out");
            cgiProcess.destroyForcibly();
            isCgiRunning = false;

            pendingResponseBuilder.status(HttpStatusCode.REQUEST_TIMEOUT).body("CGI Timeout");
            finishRequest(pendingResponseBuilder, pendingRequest);
            return;
        }

        // 3. Poll Process Status
        if (!cgiProcess.isAlive()) {
            isCgiRunning = false;

            try {
                // Read any remaining bytes that arrived
                InputStream is = cgiProcess.getInputStream();

                // Read remaining data with size checking
                byte[] chunk = new byte[8192];
                int read;
                while ((read = is.read(chunk)) != -1) {
                    if (cgiOutputSize + read > MAX_CGI_OUTPUT) {
                        logger.warning("CGI output exceeds limit during final read");
                        pendingResponseBuilder.status(HttpStatusCode.PAYLOAD_TOO_LARGE)
                                .body("CGI output too large (max " + MAX_CGI_OUTPUT + " bytes)");
                        finishRequest(pendingResponseBuilder, pendingRequest);
                        return;
                    }
                    cgiOutputBuffer.write(chunk, 0, read);
                    cgiOutputSize += read;
                }

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

        // 1. Send Headers (or error messages) from Queue
        ByteBuffer responseBuffer = responseQueue.peek();

        if (responseBuffer != null) {
            client.write(responseBuffer);

            if (!responseBuffer.hasRemaining()) {
                responseQueue.poll();
            }
            // If we still have queue items, return and continue writing next loop
            // to be fair to other clients.
            return;
        }

        // 2. Stream File Body (Zero-Copy)
        if (fileChannel != null && fileChannel.isOpen()) {
            long count = 8192 * 4; // Send ~32KB per select loop
            long transferred = fileChannel.transferTo(filePosition, count, client);

            if (transferred > 0) {
                filePosition += transferred;
            }

            // Done sending file
            if (filePosition >= fileSize) {
                fileChannel.close();
                fileChannel = null;
                logger.info("File transfer complete.");

                if (!keepAlive) {
                    close();
                } else {
                    selectionKey.interestOps(SelectionKey.OP_READ);
                }
            }
            // If not done, we exit here.
            // The selector is still interested in OP_WRITE, so it will call write() again.
            return;
        }

        // 3. Nothing to write
        if (responseQueue.isEmpty()) {
            if (keepAlive) {
                selectionKey.interestOps(SelectionKey.OP_READ);
            } else {
                close();
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

    void close() throws IOException {
        if (cgiProcess != null && cgiProcess.isAlive()) {
            cgiProcess.destroyForcibly();
        }

        if (fileChannel != null) {
            fileChannel.close();
        }

        if (parser != null) {
            parser.cleanup();
        }

        selectionKey.cancel();
        client.close();
    }
}