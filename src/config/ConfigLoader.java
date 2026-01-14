package config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import utils.JsonParser;

public class ConfigLoader {

    public static AppConfig load(String filePath, Logger logger) throws IOException {
        JsonParser parser = new JsonParser();
        String jsonContent;

        try {
            jsonContent = new String(Files.readAllBytes(Paths.get(filePath)));
        } catch (IOException e) {
            logger.severe(() -> "Error: reading " + filePath + " content: " + e.getMessage());
            return null;
        }

        Map<String, Object> result = parser.parse(jsonContent);

        // Validate general fields
        if (!ConfigValidator.validateGeneralFields(result)) {
            return null;
        }

        AppConfig config = new AppConfig();
        config.setName((String) result.get("name"));
        config.setVersion((String) result.get("version"));

        // Parse servers
        Set<String> identitySet = new HashSet<>();

        List<Map<String, Object>> servers = (List<Map<String, Object>>) result.get("servers");
        for (Map<String, Object> serverMap : servers) {

            if (!ConfigValidator.validateServer(serverMap)) {
                continue;
            }

            ServerConfig serverConfig = new ServerConfig();

            String host = (String) serverMap.get("host");
            String serverName = (String) serverMap.get("serverName");
            Integer maxBodySize = (Integer) serverMap.get("maxBodySize");
            Boolean isDefault = (Boolean) serverMap.get("defaultServer");
            List<Integer> ports = (List<Integer>) serverMap.get("ports");
            Map<String, String> errorPages = (Map<String, String>) serverMap.get("errorPages");
            List<RouteConfig> routes = loadRoutes((List<Map<String, Object>>) serverMap.get("routes"));

            for (int port : ports) {
                String identityKey = port + host + serverName;
                if (identitySet.contains(identityKey)) {
                    throw new RuntimeException(
                            "Conflict: Multiple servers bound to port " + port + " with server name '" + host + "'");
                }
                identitySet.add(identityKey);
            }

            serverConfig.setServerName(serverName);
            serverConfig.setHost(host);
            serverConfig.setMaxBodySize(maxBodySize);
            serverConfig.setPorts(ports);
            serverConfig.setErrorPages(errorPages);
            serverConfig.setIsDefault(isDefault != null ? isDefault : false);
            serverConfig.setRoutes(routes);

            config.addServers(serverConfig);
        }

        if (config.getServers() == null || config.getServers().size() < 1) {
            throw new RuntimeException("Conflict: At least one VALID server must be provided!");
        }

        return config;
    }

    private static List<RouteConfig> loadRoutes(List<Map<String, Object>> routes) {
        List<RouteConfig> routesConfigs = new ArrayList<>();

        for (Map<String, Object> route : routes) {
            RouteConfig routeConfig = new RouteConfig();
            routeConfig.setPath((String) route.get("path"));
            routeConfig.setRoot((String) route.get("root"));
            routeConfig.setIndex((String) route.get("index"));
            routeConfig.setDirectoryListing((Boolean) route.get("directoryListing"));
            routeConfig.setCgiExtension((String) route.get("cgiExtension"));
            routeConfig.setRedirectTo((String) route.get("redirectTo"));
            routeConfig.setRedirectStatusCode((Integer) route.get("redirectStatusCode"));
            routeConfig.setMethods((List<String>) route.get("methods"));

            routesConfigs.add(routeConfig);
        }
        return routesConfigs;
    }
}
