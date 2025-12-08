package config;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class ConfigLoader {
    public static ServerConfig load(String filePath) throws IOException {

        Properties props = new Properties();
        try (FileInputStream file = new FileInputStream(filePath)) {
            props.load(file);
        }

        ServerConfig config = new ServerConfig();

        config.setPort(Integer.parseInt(props.getProperty("server.port", "8080")));
        config.setHost(props.getProperty("server.host", "localhost"));
        config.setRoot(props.getProperty("server.root", "./"));

        return config;
    }
}
