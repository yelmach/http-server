package config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import utils.JsonParser;

public class AppConfig {
    private String name;
    private String version;
    private Integer maxBodySize;
    private Integer timeout;
    private List<ServerConfig> servers;

    // Getters and setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Integer getMaxBodySize() {
        return maxBodySize;
    }

    public void setMaxBodySize(Integer maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    public Integer getTimeout() {
        return timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public List<ServerConfig> getServers() {
        return servers;
    }

    public void addServers(ServerConfig server) {
        if (this.servers == null) {
            this.servers = new ArrayList<>();
        }
        this.servers.add(server);
    }

    // Load method
    public static AppConfig load(String filePath, Logger logger) throws IOException {
        JsonParser parser = new JsonParser();
        String jsonContent;

        try {
            jsonContent = new String(Files.readAllBytes(Paths.get(filePath)));
        } catch (IOException e) {
            logger.severe("Error: reading " + filePath + " content: " + e.getMessage());
            e.printStackTrace();
            return null;
        }

        Map<String, Object> result = parser.parse(jsonContent);

        // Validate general fields
        if (!ConfigValidator.validateGeneralFields(result)) {
            return null;
        }

        // Validate servers fields
        if (!ConfigValidator.validateServersFields(result)) {
            return null;
        }

        AppConfig config = new AppConfig();
        config.setName((String) result.get("name"));
        config.setVersion((String) result.get("version"));
        config.setMaxBodySize((Integer) result.get("maxBodySize"));
        config.setTimeout((Integer) result.get("timeout"));

        // Parse servers
        List<Map<String, Object>> servers = (List<Map<String, Object>>) result.get("servers");
        for (Map<String, Object> serverMap : servers) {
            ServerConfig serverConfig = new ServerConfig();
            serverConfig.setServerName((String) serverMap.get("serverName"));
            serverConfig.setPorts((List<Integer>) serverMap.get("ports"));
            // serverConfig.setRoutes((List<Map<String, Object>>) serverMap.get("routes"));
            config.addServers(serverConfig);
        }
        // for (ServerConfig server : config.getServers()) {
        //     System.out.println(server.getPorts());
        // }
        return config;
    }
}
