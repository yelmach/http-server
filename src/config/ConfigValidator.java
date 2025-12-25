package config;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import utils.ServerLogger;

public class ConfigValidator {

    private static Logger logger = ServerLogger.get();

    public static boolean validateGeneralFields(Map<String, Object> json) {
        if (json.get("name") == null || !(json.get("name") instanceof String)) {
            logger.severe("Invalid or missing 'name' field.");
            return false;
        }

        if (json.get("version") == null || !(json.get("version") instanceof String)) {
            logger.severe("Invalid or missing 'version' field.");
            return false;
        }

        if (json.get("servers") == null || !(json.get("servers") instanceof java.util.List)) {
            logger.severe("Invalid or missing 'servers' field.");
            return false;
        }

        return true;
    }

    public static boolean validateServersFields(Map<String, Object> json) {
        Object serversObject = json.get("servers");
        for (Object serverObj : (java.util.List<?>) serversObject) {
            Map<String, Object> server = (Map<String, Object>) serverObj;

            if (server.get("serverName") == null || !(server.get("serverName") instanceof String)) {
                logger.severe("Invalid or missing 'serverName' field in server.");
                return false;
            }

            Object ports = server.get("ports");
            if (ports == null || !(ports instanceof java.util.List)) {
                logger.severe("Invalid or missing 'ports' field in server.");
                return false;
            }

            Object routes = server.get("routes");
            if (routes instanceof java.util.List) {
                for (Object routeObj : (java.util.List<?>) routes) {
                    Map<String, Object> route = (Map<String, Object>) routeObj;

                    if (route.get("path") == null || !(route.get("path") instanceof String)) {
                        logger.severe("Invalid or missing 'path' field in route.");
                        return false;
                    }
                    if (route.get("root") == null || !(route.get("root") instanceof String)) {
                        logger.severe("Invalid or missing 'root' field in route.");
                        return false;
                    }
                    if (route.get("methods") == null || !(route.get("methods") instanceof java.util.List)) {
                        logger.severe("Invalid or missing 'methods' field in route.");
                        return false;
                    }
                }
            } else {
                logger.severe("Invalid 'routes' field in server.");
                return false;
            }
        }

        return true;
    }

    public static void validateDuplicateServerName(AppConfig config) throws RuntimeException {
        Set<String> identitySet = new HashSet<>();

        for (ServerConfig server : config.getServers()) {
            String host = server.getServerName();

            for (int port : server.getPorts()) {
                String identityKey = port + ":" + host;

                if (identitySet.contains(identityKey)) {
                    throw new RuntimeException(
                            "Conflict: Multiple servers bound to port " + port + " with server name '" + host + "'");
                }

                identitySet.add(identityKey);
            }

        }
    }
}
