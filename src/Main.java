import core.Server;
import java.io.IOException;

import config.ConfigLoader;
import config.ServerConfig;

public class Main {

    final static String configFileName = "config.properties";

    public static void main(String[] args) {
        try {

            ServerConfig config = ConfigLoader.load(configFileName);

            Server server = new Server();
            server.start(config);

        } catch (IOException e) {
            System.err.println("Server failed to start: " + e.getMessage());
        }
    }
}
