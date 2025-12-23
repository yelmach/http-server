package config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import utils.JsonParser;

public class ConfigLoader {

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
            serverConfig.setHost((String) serverMap.get("host"));
            serverConfig.setPorts((List<Integer>) serverMap.get("ports"));
            serverConfig.setErrorPages((Map<String, String>) serverMap.get("errorPages"));
            serverConfig.setRoutes(loadRoutes((List<Map<String, Object>>) serverMap.get("routes")));

            config.addServers(serverConfig);
        }

        return config;
    }

    private static List<RouteConfig> loadRoutes(List<Map<String, Object>> routes) {
        List<RouteConfig> routesConfigs = new ArrayList<>();

        for (Map<String, Object> route : routes) {
            RouteConfig routeConfig = new RouteConfig();
            routeConfig.setPath((String) route.get("path"));
            routeConfig.setRoot((String) route.get("root"));
            routeConfig.setMethods((List<String>) route.get("methods"));

            routesConfigs.add(routeConfig);
        }
        return routesConfigs;
    }
}
