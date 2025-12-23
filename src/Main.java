import core.Server;
import utils.ServerLogger;

import java.io.IOException;
import java.util.logging.Logger;

import config.AppConfig;
import config.ServerConfig;

public class Main {

    final static String configFileName = "config.json";
    final static Logger logger = ServerLogger.get();

    public static void main(String[] args) {
        try {

            AppConfig config = AppConfig.load(configFileName, logger);

            if (config == null || config.getServers() == null) {
                logger.severe("Configuration loading failed!");
                return;
            }

            for (ServerConfig serverConfig : config.getServers()) {
                Server server = new Server();
                for (int port : serverConfig.getPorts()) {
                    server.start(port);
                }
            }

        } catch (IOException e) {
            logger.severe("Server failed to start: " + e.getMessage());
        }
    }
}
