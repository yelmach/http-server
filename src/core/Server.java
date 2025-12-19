package core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

public class Server {

    private Selector selector;
    private ServerSocketChannel serverSocket;

    public void start(int port) throws IOException {

        selector = Selector.open();
        serverSocket = ServerSocketChannel.open();

        serverSocket.bind(new InetSocketAddress(port));
        serverSocket.configureBlocking(false);

        serverSocket.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("-> Server started on port " + port);

        while (true) {
            selector.select(1000);

            checkTimeouts();

            // Get list of events
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                iterator.remove();

                if (!key.isValid()) {
                    continue;
                }

                try {
                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                } catch (IOException e) {
                    System.err.println("error: " + e.getMessage());
                    key.cancel();
                    key.channel().close();
                }
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();

        client.configureBlocking(false);

        SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ);

        ClientHandler handler = new ClientHandler(client, clientKey);
        clientKey.attach(handler);

        System.out.println("New connection: " + client.getRemoteAddress());
    }

    private void handleRead(SelectionKey key) throws IOException {
        ClientHandler handler = (ClientHandler) key.attachment();
        handler.read();
    }

    private void handleWrite(SelectionKey key) throws IOException {
        ClientHandler handler = (ClientHandler) key.attachment();
        handler.write();
    }

    private void checkTimeouts() {
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.attachment() instanceof ClientHandler) {
                ClientHandler handler = (ClientHandler) key.attachment();

                if (handler.isTimedOut()) {
                    System.out.println("Connection timed out, closing...");
                    try {
                        key.cancel();
                        key.channel().close();
                    } catch (IOException e) {
                        System.err.println("Error closing timed-out connection: " + e.getMessage());
                    }
                }
            }
        }
    }
}
