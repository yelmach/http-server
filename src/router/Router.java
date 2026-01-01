package router;

import java.io.File;
import java.util.List;
import java.util.Optional;

import config.RouteConfig;
import config.ServerConfig;
import handlers.CGIHandler;
import handlers.DeleteHandler;
import handlers.DirectoryHandler;
import handlers.ErrorHandler;
import handlers.Handler;
import handlers.RedirectHandler;
import handlers.StaticFileHandler;
import handlers.UploadHandler;
import http.HttpRequest;
import http.HttpStatusCode;

public class Router {

    public Handler route(HttpRequest httpRequest, ServerConfig serverConfig) {
        String requestPath = httpRequest.getPath();

        // 1. Find matching route
        Optional<RouteConfig> matchingRoute = findMatchingRoute(requestPath, serverConfig);
        if (matchingRoute.isEmpty()) {
            return new ErrorHandler(HttpStatusCode.NOT_FOUND, serverConfig);
        }

        RouteConfig route = matchingRoute.get();

        // 2. Handle Redirection (301/302)
        if (route.getRedirectTo() != null) {
            return new RedirectHandler(route.getRedirectTo());
        }

        // 3. Method validation
        if (!route.getMethods().contains(httpRequest.getMethod().toString())) {
            return new ErrorHandler(HttpStatusCode.METHOD_NOT_ALLOWED, serverConfig);
        }

        // 4. Resolve filesystem path
        String fsPath = resolveFilesystemPath(requestPath, route);
        File resource = new File(fsPath);

        // Sanitize path to prevent traversal attacks (../)
        if (!isPathSafe(resource, route.getRoot())) {
            return new ErrorHandler(HttpStatusCode.FORBIDDEN, serverConfig);
        }

        // 5. CGI detection (Extension Match)
        if (route.getCgiExtension() != null &&
                requestPath.endsWith(route.getCgiExtension()) &&
                resource.isFile() &&
                resource.getName().endsWith(route.getCgiExtension())) {
            return new CGIHandler(route, resource);
        }

        // 6. Handle POST (uploads)
        if (httpRequest.getMethod().name().equals("POST")) {
            return new UploadHandler(route, resource);
        }

        // 7. Handle DELETE
        if (httpRequest.getMethod().name().equals("DELETE")) {
            return new DeleteHandler(route, resource);
        }

        // 8. Handle directories
        if (resource.isDirectory()) {
            if (Boolean.TRUE.equals(route.getDirectoryListing())) {
                return new DirectoryHandler(route, resource);
            }

            // Check if index file exists
            File indexFile = new File(resource, route.getIndex() != null ? route.getIndex() : "index.html");
            if (indexFile.exists()) {
                return new StaticFileHandler(indexFile);
            }

            return new ErrorHandler(HttpStatusCode.FORBIDDEN, serverConfig);
        }

        if (resource.exists()) {
            return new StaticFileHandler(resource);
        }

        return new ErrorHandler(HttpStatusCode.NOT_FOUND, serverConfig);
    }

    private Optional<RouteConfig> findMatchingRoute(String requestPath, ServerConfig config) {
        List<RouteConfig> routes = config.getRoutes();

        return routes.stream().filter(route -> {
            String routePath = route.getPath();
            return requestPath.startsWith(routePath)
                    && (requestPath.length() == routePath.length() || requestPath.charAt(routePath.length()) == '/');
        }).reduce((a, b) -> a.getPath().length() > b.getPath().length() ? a : b);
    }

    private String resolveFilesystemPath(String requestPath, RouteConfig route) {
        String relativePath = requestPath.substring(route.getPath().length());
        if (!relativePath.isEmpty() && !relativePath.startsWith("/")) {
            relativePath = "/" + relativePath;
        }

        return route.getRoot() + relativePath;
    }

    private boolean isPathSafe(File resource, String rootPath) {
        try {
            String resourceCanonical = resource.getCanonicalPath();
            String rootCanonical = new File(rootPath).getCanonicalPath();
            return resourceCanonical.startsWith(rootCanonical);
        } catch (Exception e) {
            return false;
        }
    }
}