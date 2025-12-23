package config;

import java.util.List;
import java.util.Map;

public class ServerConfig {

    private String serverName;
    private String host;
    private List<Integer> ports;
    private Map<String, String> errorPages;
    private List<RouteConfig> routes;

    // Getters and setters
    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public List<Integer> getPorts() {
        return ports;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setPorts(List<Integer> ports) {
        this.ports = ports;
    }

    public List<RouteConfig> getRoutes() {
        return routes;
    }

    public void setRoutes(List<RouteConfig> routes) {
        this.routes = routes;
    }

    public Map<String, String> getErrorPages() {
        return errorPages;
    }

    public void setErrorPages(Map<String, String> errorPages) {
        this.errorPages = errorPages;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ServerConfig {\n")
                .append("  serverName='").append(serverName).append("',\n")
                .append("  host='").append(host).append("',\n")
                .append("  ports=").append(ports).append(",\n")
                .append("  errorPages=").append(errorPages).append(",\n")
                .append("  routes=").append(routes == null ? "[]" : routes).append("\n")
                .append("}");
        return sb.toString();
    }

}
