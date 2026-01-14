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
import java.util.stream.Collectors;

import config.ServerConfig;
import utils.ServerLogger;

public class Server {

    private Selector selector;
    private final Logger logger = ServerLogger.get();

    public void start(List<ServerConfig> configs) throws IOException {

        selector = Selector.open();

        Map<String, List<ServerConfig>> socketBindings = new HashMap<>();
        for (ServerConfig config : configs) {
            String host = config.getHost();

            for (int port : config.getPorts()) {
                String bindKey = host + ":" + port;
                socketBindings.computeIfAbsent(bindKey, k -> new ArrayList<>()).add(config);
            }
        }

        for (Map.Entry<String, List<ServerConfig>> entry : socketBindings.entrySet()) {
            String bindKey = entry.getKey();
            List<ServerConfig> virtualHosts = entry.getValue();

            String[] parts = bindKey.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            ServerSocketChannel serverSocket = ServerSocketChannel.open();
            serverSocket.bind(new InetSocketAddress(host, port));
            serverSocket.configureBlocking(false);
            serverSocket.register(selector, SelectionKey.OP_ACCEPT, virtualHosts);

            logger.info("Server bound to " + host + ":" + port + " with " + virtualHosts.size() + " virtual hosts: " +
                    virtualHosts.stream().map(ServerConfig::getServerName).collect(Collectors.joining(", ")));
        }

        while (true) {
            selector.select(50);

            checkTimeouts();

            checkPendingCGI();

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

    private void checkPendingCGI() {
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.attachment() instanceof ClientHandler) {
                ClientHandler handler = (ClientHandler) key.attachment();
                handler.checkCgiProcess();
            }
        }
    }
}
