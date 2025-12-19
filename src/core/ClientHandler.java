package core;

import http.HttpRequest;
import http.HttpStatusCode;
import http.ParsingResult;
import http.RequestParser;
import http.ResponseBuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Queue;

public class ClientHandler {

    private final SocketChannel client;
    private final SelectionKey selectionKey;
    private final ByteBuffer readBuffer;
    private final RequestParser parser;
    private final Queue<ByteBuffer> responseQueue = new LinkedList<>();
    private boolean keepAlive;
    private long lastActivityTime;
    private final long timeoutMs = 10000;

    public ClientHandler(SocketChannel clientChannel, SelectionKey selectionKey) {
        this.client = clientChannel;
        this.selectionKey = selectionKey;
        this.readBuffer = ByteBuffer.allocate(8192);
        this.parser = new RequestParser();
        this.lastActivityTime = System.currentTimeMillis();
    }

    public void read() throws IOException {
        lastActivityTime = System.currentTimeMillis();
        readBuffer.clear();
        int bytesRead = client.read(readBuffer);

        if (bytesRead == -1) {
            close();
            System.out.println("Client disconnected.");
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
                System.err.println("Parsing error: " + result.getErrorMessage());
                close();
            }

            if (!responseQueue.isEmpty()) {
                selectionKey.interestOps(SelectionKey.OP_WRITE);
            }
        }
    }

    private void handleRequest(HttpRequest currentRequest) {
        System.out.println("Received: " + currentRequest.getMethod() + " " + currentRequest.getPath());

        keepAlive = currentRequest.shouldKeepAlive();

        ByteBuffer response = new ResponseBuilder()
                .status(HttpStatusCode.OK)
                .keepAlive(keepAlive)
                .contentType("text/plain")
                .body("request parsed successfully")
                .buildResponse();

        responseQueue.add(response);
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

    public boolean isTimedOut() {
        long currentTime = System.currentTimeMillis();
        return (currentTime - lastActivityTime) > timeoutMs;
    }

    private void close() throws IOException {
        selectionKey.cancel();
        client.close();
    }
}
