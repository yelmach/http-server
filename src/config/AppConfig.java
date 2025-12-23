package config;

import java.util.ArrayList;
import java.util.List;

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

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AppConfig {\n")
                .append("  name='").append(name).append("',\n")
                .append("  version='").append(version).append("',\n")
                .append("  maxBodySize=").append(maxBodySize).append(",\n")
                .append("  timeout=").append(timeout).append(",\n")
                .append("  servers=").append(servers == null ? "[]" : servers).append("\n")
                .append("}");
        return sb.toString();
    }

}
