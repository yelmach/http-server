package config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class RouteConfig {

    private String path;
    private String root;
    private List<String> methods;
    private String index;
    private Boolean directoryListing;
    private String redirectTo;
    private String cgiExtension;

    public String getCgiExtension() {
        return cgiExtension;
    }

    public void setCgiExtension(String cgiExtension) {
        this.cgiExtension = cgiExtension;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public List<String> getMethods() {
        return methods;
    }

    public void setMethods(List<String> methods) {
        HashSet<String> allowedMethods = new HashSet<>(Arrays.asList("GET", "POST", "DELETE"));
        for (String method : methods) {
            if (!allowedMethods.contains(method)) {
                throw new RuntimeException("Conflict: Method '" + method + "' not supported!");
            }
        }
        this.methods = methods;
    }

    public Boolean getDirectoryListing() {
        return directoryListing;
    }

    public void setDirectoryListing(Boolean directoryListing) {
        this.directoryListing = directoryListing;
    }

    public String getIndex() {
        return index;
    }

    public void setIndex(String index) {
        this.index = index;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("\nRouteConfig {\n")
                .append("  path='").append(path).append("',\n")
                .append("  root='").append(root).append("',\n")
                .append("  methods=").append(methods).append(",\n")
                .append("  index=").append(index).append(",\n")
                .append("  directoryListing=").append(directoryListing).append(",\n")
                .append("  redirectTo=").append(redirectTo).append(",\n")
                .append("}");
        return sb.toString();
    }

    public String getRedirectTo() {
        return redirectTo;
    }

    public void setRedirectTo(String redirectTo) {
        this.redirectTo = redirectTo;
    }
}
