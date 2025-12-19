package config;

public class ServerConfig {
    private String host;
    private int port;
    private String root;

    public ServerConfig(String host, int port, String root) {
        this.host = host;
        this.port = port;
        this.root = root;
    }

    public ServerConfig() {
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    @Override
    public String toString() {
        return "ServerConfig [host=" + host + ", port=" + port + ", root=" + root + "]";
    }

}
