package config;

import java.util.List;

public class RouteConfig {

    private String path;
    private String root;
    private List<String> methods;

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
        this.methods = methods;
    }

    @Override
    public String toString() {
        return "RouteConfig {"
                + "path='" + path + '\''
                + ", root='" + root + '\''
                + ", methods=" + methods
                + '}';
    }

}
