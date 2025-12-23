package config;

import java.util.List;

public class ServerConfig {
    private String serverName;
    private String host;
    private String root;
    private List<Integer> ports;
    private List<String> methods;
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

    public void setPorts(List<Integer> ports) {
        this.ports = ports;
    }

    public List<String> getMethods() {
        return methods;
    }

    public void setMethods(List<String> methods) {
        this.methods = methods;
    }

    public List<RouteConfig> getRoutes() {
        return routes;
    }

    public void setRoutes(List<RouteConfig> routes) {
        this.routes = routes;
    }

    @Override
    public String toString() {
        return "ServerConfig [host=" + host + ", root=" + root + "]";
    }

}
