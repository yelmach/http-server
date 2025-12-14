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

    public ClientHandler(SocketChannel clientChannel, SelectionKey selectionKey) {
        this.client = clientChannel;
        this.selectionKey = selectionKey;
        this.readBuffer = ByteBuffer.allocate(8192);
        this.parser = new RequestParser();
    }

    public void read() throws IOException {
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
                System.out.println("Need more data, read " + bytesRead + " bytes so far");
            }
        }
    }

    private void handleRequest() {
        System.out.println("Received: " + currentRequest.getMethod() + " " + currentRequest.getPath());

        // Temporary: Return simple 200 OK response
        String responseText = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: 13\r\n" +
                "\r\n" +
                "Hello, World!";
        responseBuffer = ByteBuffer.wrap(responseText.getBytes(StandardCharsets.UTF_8));
    }

    public void write() throws IOException {
        if (responseBuffer != null && responseBuffer.hasRemaining()) {
            client.write(responseBuffer);

            if (!responseBuffer.hasRemaining()) {
                // Response sent, close connection
                // Later: check Connection: keep-alive
                close();
            }
        }
    }

    private void close() throws IOException {
        selectionKey.cancel();
        client.close();
    }
}
