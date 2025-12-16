package core;

import http.HttpRequest;
import http.ParsingResult;
import http.RequestParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class ClientHandler {

    private final SocketChannel client;
    private final SelectionKey selectionKey;
    private final ByteBuffer readBuffer;
    private final RequestParser parser;
    private HttpRequest currentRequest;
    private ByteBuffer responseBuffer;
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

            if (result.isComplete()) {
                currentRequest = result.getRequest();
                handleRequest();
                selectionKey.interestOps(SelectionKey.OP_WRITE);
            } else if (result.isError()) {
                System.err.println("Parsing error: " + result.getErrorMessage());

                close();
            } else if (result.isNeedMoreData()) {
                System.out.println("-> Need more data");
            }
        }
    }

    private void handleRequest() {
        System.out.println("Received: " + currentRequest.getMethod() + " " + currentRequest.getPath());

        keepAlive = currentRequest.shouldKeepAlive();

        String body = "Request parsed successfuly";
        String connectionHeader = keepAlive ? "keep-alive" : "close";

        String responseText = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "Connection: " + connectionHeader + "\r\n" +
                "\r\n" +
                body;
        responseBuffer = ByteBuffer.wrap(responseText.getBytes(StandardCharsets.UTF_8));
    }

    public void write() throws IOException {
        if (responseBuffer != null && responseBuffer.hasRemaining()) {
            lastActivityTime = System.currentTimeMillis();
            client.write(responseBuffer);

            if (!responseBuffer.hasRemaining()) {
                if (keepAlive) {
                    resetForNextRequest();
                } else {
                    close();
                }
            }
        }
    }

    private void resetForNextRequest() {
        parser.reset();
        currentRequest = null;
        responseBuffer = null;
        keepAlive = false;
        lastActivityTime = System.currentTimeMillis();
        selectionKey.interestOps(SelectionKey.OP_READ);
        System.out.println("Connection kept alive, ready for next request");
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
