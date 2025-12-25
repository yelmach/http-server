package core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import config.ServerConfig;
import utils.ServerLogger;

public class Server {

    private Selector selector;
    private ServerSocketChannel serverSocket;
    private final Logger logger = ServerLogger.get();

    public void start(List<ServerConfig> configs) throws IOException {

        selector = Selector.open();

        Map<Integer, List<ServerConfig>> portMapping = new HashMap<>();
        for (ServerConfig config : configs) {
            portMapping.computeIfAbsent(config.getPorts().get(0), k -> new ArrayList<>()).add(config);
        }

        for (Map.Entry<Integer, List<ServerConfig>> entry : portMapping.entrySet()) {
            int port = entry.getKey();
            List<ServerConfig> virtualHosts = entry.getValue();

            serverSocket = ServerSocketChannel.open();
            serverSocket.bind(new InetSocketAddress(port));
            serverSocket.configureBlocking(false);
            serverSocket.register(selector, SelectionKey.OP_ACCEPT, virtualHosts);

            logger.info("Server started on port: " + port + " with " + virtualHosts.size() + " virtual hosts.");
        }

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
                    logger.severe(e.getMessage());
                    key.cancel();
                    key.channel().close();
                }
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        List<ServerConfig> virtualHosts = (List<ServerConfig>) key.attachment();

        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);

        SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ);

        ClientHandler handler = new ClientHandler(client, clientKey, virtualHosts);
        clientKey.attach(handler);

        logger.info("New connection: " + client.getRemoteAddress());
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
                    logger.info("Connection timed out, closing...: " + handler.getIpAddress());
                    try {
                        key.cancel();
                        key.channel().close();
                    } catch (IOException e) {
                        logger.severe("Error closing timed-out connection: " + e.getMessage());
                    }
                }
            }
        }
    }
}
