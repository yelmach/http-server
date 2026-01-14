package config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import utils.ServerLogger;

public class ConfigValidator {

    private final static Logger logger = ServerLogger.get();
    private static Set<String> allowed = Set.of("GET", "POST", "DELETE");

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

        java.util.List servers = (java.util.List) json.get("servers");

        if (servers.size() > 10) {
            logger.severe("you can't use more than 10 servers.");
            return false;
        }

        return true;
    }

    public static boolean validateServersFields(Map<String, Object> json) {
        Object serversObject = json.get("servers");
        boolean foundDefault = false;

        for (Object serverObj : (java.util.List<?>) serversObject) {
            Map<String, Object> server = (Map<String, Object>) serverObj;

            if (server.get("serverName") == null || !(server.get("serverName") instanceof String)) {
                logger.severe("Invalid or missing 'serverName' field in server.");
                return false;
            }

            Object host = server.get("host");
            if (!(host instanceof String)) {
                logger.severe("Invalid or missing 'host' field in server.");
                return false;
            }

            String hostStr = (String) host;
            try {
                InetAddress.getByName(hostStr);
            } catch (UnknownHostException e) {
                logger.severe("Invalid IP address: " + hostStr);
                return false;
            }

            Object maxBodySize = server.get("maxBodySize");
            if (maxBodySize != null) {
                if (!(maxBodySize instanceof Integer) || (Integer) maxBodySize <= 0) {
                    logger.severe("Invalid 'maxBodySize' field.");
                    return false;
                }
            }

            Object ports = server.get("ports");
            if (ports == null || !(ports instanceof java.util.List)) {
                logger.severe("Invalid or missing 'ports' field in server.");
                return false;
            }

            List<?> portsList = (List<?>) ports;

            if (portsList.isEmpty()) {
                logger.severe("Ports list cannot be empty.");
                return false;
            }

            Set<Integer> seenPorts = new HashSet<>();

            for (Object portObj : portsList) {
                if (!(portObj instanceof Integer)) {
                    logger.severe("Port must be an integer.");
                    return false;
                }

                int port = (Integer) portObj;
                if (port < 1 || port > 65535) {
                    logger.severe("Invalid port number: " + port);
                    return false;
                }

                if (!seenPorts.add(port)) {
                    logger.severe("Duplicate port: " + port);
                    return false;
                }
            }

            Object defaultServer = server.get("defaultServer");
            if (defaultServer != null) {
                if (!(defaultServer instanceof Boolean)) {
                    logger.severe("Invalid 'defaultServer' field in server.");
                    return false;
                }
                if ((Boolean) defaultServer) {
                    if (foundDefault) {
                        logger.severe("Multiple default servers defined.");
                        return false;
                    }
                    foundDefault = true;
                }
            }

            Object routes = server.get("routes");
            if (routes instanceof java.util.List) {
                for (Object routeObj : (java.util.List<?>) routes) {
                    Map<String, Object> route = (Map<String, Object>) routeObj;

                    if (route.get("path") == null || !(route.get("path") instanceof String)) {
                        logger.severe("Invalid or missing 'path' field in route.");
                        return false;
                    }
                    String path = (String) route.get("path");

                    if (!path.startsWith("/")) {
                        logger.severe("Route path must start with '/': " + path);
                        return false;
                    }

                    boolean isRedirect = route.get("redirectTo") != null;
                    if (isRedirect) {
                        Object code = route.get("redirectStatusCode");
                        if (!(code instanceof Integer)
                                || (((Integer) code) != 301 && ((Integer) code) != 302)) {
                            logger.severe("Invalid redirectStatusCode for path: " + path);
                            return false;
                        }
                        continue;
                    }

                    if (route.get("root") == null || !(route.get("root") instanceof String)) {
                        logger.severe("Invalid or missing 'root' field in route.");
                        return false;
                    }
                    if (route.get("methods") == null || !(route.get("methods") instanceof java.util.List)) {
                        logger.severe("Invalid or missing 'methods' field in route.");
                        return false;
                    }

                    List methodsList = (List) route.get("methods");

                    for (Object m : methodsList) {
                        if (!(m instanceof String) || !allowed.contains(m)) {
                            logger.severe("Invalid HTTP method: " + m);
                            return false;
                        }
                    }

                    Object dl = route.get("directoryListing");
                    if (dl != null && !(dl instanceof Boolean)) {
                        logger.severe("Invalid directoryListing flag");
                        return false;
                    }

                    Object index = route.get("index");
                    if (index != null && !(index instanceof String)) {
                        logger.severe("Invalid index field");
                        return false;
                    }

                    Object ext = route.get("cgiExtension");
                    if (ext != null && (!(ext instanceof String) || !ext.equals("py"))) {
                        logger.severe("Invalid or missing 'cgiExtension' field in route.");
                        return false;
                    }
                }
            } else {
                logger.severe("Invalid 'routes' field in server.");
                return false;
            }
        }

        if (!foundDefault) {
            logger.severe("No default server defined.");
            return false;
        }

        return true;
    }
}
