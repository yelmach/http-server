import core.Server;
import utils.ServerLogger;

import java.io.IOException;
import java.util.logging.Logger;

import config.ConfigLoader;
import config.ServerConfig;

public class Main {

    final static String configFileName = "config.properties";
    final static Logger logger = ServerLogger.get();

    public static void main(String[] args) {
        try {

            ServerConfig config = ConfigLoader.load(configFileName);

            Server server = new Server();
            server.start(config.getPort());

        } catch (IOException e) {
            logger.severe("Server failed to start: " + e.getMessage());
        }
    }
}
