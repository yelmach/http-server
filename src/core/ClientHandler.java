package core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class ClientHandler {

    private final SocketChannel client;
    private final SelectionKey selectionKey;
    private final ByteBuffer buffer;

    public ClientHandler(SocketChannel clientChannel, SelectionKey selectionKey) {
        this.client = clientChannel;
        this.selectionKey = selectionKey;

        this.buffer = ByteBuffer.allocate(256);
    }

    public void read() throws IOException {
        buffer.clear();

        int bytesRead = client.read(buffer);

        if (bytesRead == -1) {
            close();
            System.out.println("Client disconnected.");
            return;
        }

        if (bytesRead > 0) {
            buffer.flip();

            String str = new String(buffer.array(), 0, bytesRead);

            if (str.equals("exit\n")) {
                close();
                System.out.println("Client disconnected.");
                return;
            }

            System.out.print("Received: " + str);

            selectionKey.interestOps(SelectionKey.OP_WRITE);
        }
    }

    public void write() throws IOException {
        client.write(buffer);

        if (!buffer.hasRemaining()) {
            selectionKey.interestOps(SelectionKey.OP_READ);
        }
    }

    private void close() throws IOException {
        selectionKey.cancel();
        client.close();
    }
}
