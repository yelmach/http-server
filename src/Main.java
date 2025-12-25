
import config.AppConfig;
import config.ConfigLoader;
import core.Server;
import java.util.logging.Logger;
import utils.ServerLogger;

public class Main {

    final static String configFileName = "config.json";
    final static Logger logger = ServerLogger.get();

    public static void main(String[] args) {
        try {
            AppConfig config = ConfigLoader.load(configFileName, logger);
            if (config == null || config.getServers() == null) {
                logger.severe("Configuration loading failed!");
                return;
            }

            System.out.println(config.toString());

            Server server = new Server();
            server.start(config.getServers());

        } catch (Exception e) {
            logger.severe("Server failed to start: " + e.getMessage());
        }
    }
}
