# Router & Handler Architecture

## Overview

The **Router** maps incoming HTTP requests to appropriate **Handlers** based on path, method, and resource type. Handlers process requests and build responses.

```
HttpRequest → Router.route() → Handler → ResponseBuilder
```

## Handler Interface

All handlers implement a single method:

```java
public interface Handler {
    void handle(HttpRequest request, ResponseBuilder response) throws Exception;
}
```

**Simple contract:**
- Input: `HttpRequest` (parsed request data)
- Output: `ResponseBuilder` (response configuration)
- Exceptions: Caught by `ClientHandler`, converted to 500 errors

## Available Handlers

| Handler | Purpose | Trigger |
|---------|---------|---------|
| `StaticFileHandler` | Serve files | File exists |
| `DirectoryHandler` | List directory contents | Directory + listing enabled |
| `UploadHandler` | Save uploaded files | POST request |
| `DeleteHandler` | Delete files | DELETE request |
| `CGIHandler` | Execute scripts | File matches CGI extension |
| `RedirectHandler` | Redirect URLs | Route has `redirectTo` |
| `ErrorHandler` | Display error pages | 4xx/5xx errors |
| `SessionHandler` | Demo session handling | Path = `/session` |

## Router Logic

### Routing Flow

```java
public Handler route(HttpRequest request, ServerConfig config) {
    String requestPath = request.getPath();
    
    // 1. Find matching route
    Optional<RouteConfig> matchingRoute = findMatchingRoute(requestPath, config);
    if (matchingRoute.isEmpty()) {
        return new ErrorHandler(HttpStatusCode.NOT_FOUND, config);
    }
    RouteConfig route = matchingRoute.get();
    
    // 2. Handle redirection (early return)
    if (route.getRedirectTo() != null) {
        HttpStatusCode code = route.getRedirectStatusCode() == 302 
            ? HttpStatusCode.FOUND 
            : HttpStatusCode.MOVED_PERMANENTLY;
        return new RedirectHandler(route.getRedirectTo(), code);
    }
    
    // 3. Validate HTTP method
    if (route.getMethods() != null && !route.getMethods().contains(request.getMethod().toString())) {
        return new ErrorHandler(HttpStatusCode.METHOD_NOT_ALLOWED, config);
    }
    
    // 4. Resolve filesystem path
    String fsPath = resolveFilesystemPath(requestPath, route);
    File resource = new File(fsPath);
    
    // 5. Security check (path traversal)
    if (!isPathSafe(resource, route.getRoot())) {
        return new ErrorHandler(HttpStatusCode.FORBIDDEN, config);
    }
    
    // 6. CGI detection
    if (route.getCgiExtension() != null 
        && resource.isFile() 
        && resource.getName().endsWith(route.getCgiExtension())) {
        return new CGIHandler(route, resource);
    }
    
    // 7. HTTP method-based routing
    if (request.getMethod().name().equals("POST")) {
        return new UploadHandler(route, resource);
    }
    if (request.getMethod().name().equals("DELETE")) {
        return new DeleteHandler(route, resource);
    }
    
    // 8. Handle directories
    if (resource.isDirectory()) {
        if (Boolean.TRUE.equals(route.getDirectoryListing())) {
            return new DirectoryHandler(route, resource);
        }
        
        File indexFile = new File(resource, route.getIndex() != null ? route.getIndex() : "index.html");
        if (indexFile.exists()) {
            return new StaticFileHandler(indexFile);
        }
        return new ErrorHandler(HttpStatusCode.FORBIDDEN, config);
    }
    
    // 9. Serve static file
    if (resource.exists()) {
        return new StaticFileHandler(resource);
    }
    
    // 10. Not found
    return new ErrorHandler(HttpStatusCode.NOT_FOUND, config);
}
```

### Route Matching

**Algorithm:** Find the longest matching prefix.

```java
private Optional<RouteConfig> findMatchingRoute(String requestPath, ServerConfig config) {
    List<RouteConfig> routes = config.getRoutes();
    
    return routes.stream()
        .filter(route -> {
            String routePath = route.getPath();
            
            // Root route matches everything
            if (routePath.equals("/")) {
                return true;
            }
            
            // Check if request path starts with route path
            // AND either matches exactly or has a slash after
            return requestPath.startsWith(routePath)
                && (requestPath.length() == routePath.length() 
                    || requestPath.charAt(routePath.length()) == '/');
        })
        // Select longest match
        .reduce((a, b) -> a.getPath().length() > b.getPath().length() ? a : b);
}
```

**Examples:**

```
Request: /api/users/123

Routes:
  / → matches (length 1)
  /api → matches (length 4)
  /api/users → matches (length 10) ← SELECTED (longest)
  /api/admin → no match

Result: /api/users route is chosen
```

**Edge cases:**
```
Request: /api         → Matches /api ✓
Request: /apidocs     → Does NOT match /api ✗ (no slash after)
Request: /api/v1      → Matches /api ✓
```

### Path Resolution

Converts URL path to filesystem path:

```java
private String resolveFilesystemPath(String requestPath, RouteConfig route) {
    // Remove route prefix from request path
    String relativePath = requestPath.substring(route.getPath().length());
    
    // Ensure leading slash
    if (!relativePath.isEmpty() && !relativePath.startsWith("/")) {
        relativePath = "/" + relativePath;
    }
    
    return route.getRoot() + relativePath;
}
```

**Examples:**

```
Route: { path: "/static", root: "./www/assets" }
Request: /static/css/style.css
Result: ./www/assets/css/style.css

Route: { path: "/", root: "./www" }
Request: /index.html
Result: ./www/index.html
```

### Security: Path Traversal Prevention

Ensures resolved path stays within configured root:

```java
private boolean isPathSafe(File resource, String rootPath) {
    try {
        String resourceCanonical = resource.getCanonicalPath();
        String rootCanonical = new File(rootPath).getCanonicalPath();
        return resourceCanonical.startsWith(rootCanonical);
    } catch (Exception e) {
        return false;
    }
}
```

**Attack prevention:**

```
Request: /static/../../etc/passwd
Route root: ./www/static

Resolved: ./www/static/../../etc/passwd
Canonical: /etc/passwd
Root canonical: /home/user/project/www/static

Check: /etc/passwd starts with /home/user/project/www/static? → FALSE
Result: 403 Forbidden
```

## Handler Implementations

### StaticFileHandler

Serves files with proper MIME types and caching headers:

```java
public class StaticFileHandler implements Handler {
    private final File file;
    
    @Override
    public void handle(HttpRequest request, ResponseBuilder response) {
        if (!file.exists() || !file.isFile()) {
            response.status(HttpStatusCode.NOT_FOUND);
            return;
        }
        
        if (!file.canRead()) {
            response.status(HttpStatusCode.FORBIDDEN);
            return;
        }
        
        String mimeType = MimeTypes.getContentType(file.getName());
        
        String lastModified = DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneId.of("GMT"))
            .format(Instant.ofEpochMilli(file.lastModified()));
        
        response.status(HttpStatusCode.OK)
                .contentType(mimeType)
                .header("Last-Modified", lastModified)
                .body(file);  // File object, not bytes
    }
}
```

**Key features:**
- MIME type detection from file extension
- `Last-Modified` header for browser caching
- File passed as object (streamed later by ClientHandler)

### ErrorHandler

Generates error pages (custom or default):

```java
public class ErrorHandler implements Handler {
    private final HttpStatusCode statusCode;
    private final ServerConfig serverConfig;
    
    @Override
    public void handle(HttpRequest request, ResponseBuilder response) {
        response.status(statusCode).contentType("text/html");
        
        String errorPage = getCustomErrorPage();
        if (errorPage != null) {
            response.body(errorPage);
        } else {
            response.body(generateDefaultErrorPage());
        }
    }
    
    private String getCustomErrorPage() {
        if (serverConfig.getErrorPages() == null) {
            return null;
        }
        
        String errorPagePath = serverConfig.getErrorPages()
            .get(String.valueOf(statusCode.getCode()));
        
        if (errorPagePath != null) {
            File errorFile = new File(errorPagePath);
            if (errorFile.exists() && errorFile.isFile()) {
                return new String(Files.readAllBytes(Paths.get(errorPagePath)));
            }
        }
        return null;
    }
}
```

**Custom error pages (config.json):**
```json
{
  "errorPages": {
    "404": "./www/errors/404.html",
    "500": "./www/errors/500.html"
  }
}
```

### DeleteHandler

Deletes files from filesystem:

```java
public class DeleteHandler implements Handler {
    private final File resource;
    
    @Override
    public void handle(HttpRequest request, ResponseBuilder response) {
        if (!resource.exists()) {
            response.status(HttpStatusCode.NOT_FOUND);
            return;
        }
        
        if (resource.isDirectory() || !resource.canWrite()) {
            response.status(HttpStatusCode.FORBIDDEN);
            return;
        }
        
        boolean deleted = resource.delete();
        if (deleted) {
            response.status(HttpStatusCode.NO_CONTENT);  // 204
        } else {
            response.status(HttpStatusCode.INTERNAL_SERVER_ERROR);
        }
    }
}
```

**Safety checks:**
- File must exist
- Cannot delete directories
- File must be writable
- Returns 204 No Content on success (no body)

## Route Configuration

Routes are defined in `config.json`:

```json
{
  "routes": [
    {
      "path": "/",
      "methods": ["GET"],
      "root": "./www",
      "index": "index.html"
    },
    {
      "path": "/api",
      "methods": ["GET", "POST"],
      "root": "./www/api",
      "directoryListing": false
    },
    {
      "path": "/uploads",
      "methods": ["GET", "POST", "DELETE"],
      "root": "./www/uploads"
    },
    {
      "path": "/cgi-bin",
      "root": "./scripts",
      "methods": ["GET", "POST"],
      "cgiExtension": "py"
    },
    {
      "path": "/old-page",
      "redirectTo": "/new-page",
      "redirectStatusCode": 301
    }
  ]
}
```

### Route Parameters

| Parameter | Type | Description | Example |
|-----------|------|-------------|---------|
| `path` | String | URL prefix to match | `"/api"` |
| `root` | String | Filesystem directory | `"./www/api"` |
| `methods` | Array | Allowed HTTP methods | `["GET", "POST"]` |
| `index` | String | Default file for directories | `"index.html"` |
| `directoryListing` | Boolean | Enable directory browsing | `true` |
| `cgiExtension` | String | CGI script extension | `"py"` |
| `redirectTo` | String | Redirect destination | `"/new-page"` |
| `redirectStatusCode` | Integer | 301 or 302 | `301` |

## Integration with Server

### ClientHandler Usage

```java
public class ClientHandler {
    private void handleRequest(HttpRequest request) {
        // 1. Resolve virtual host configuration
        ServerConfig config = resolveConfig(request.getHeaders().get("Host"));
        
        // 2. Route request to handler
        Router router = new Router();
        Handler handler = router.route(request, config);
        
        // 3. Execute handler
        ResponseBuilder responseBuilder = new ResponseBuilder();
        try {
            handler.handle(request, responseBuilder);
        } catch (Exception e) {
            logger.severe("Handler Error: " + e.getMessage());
            responseBuilder.status(HttpStatusCode.INTERNAL_SERVER_ERROR);
        }
        
        // 4. Check for errors and use ErrorHandler if needed
        if (responseBuilder.getStatusCode().isError()) {
            ErrorHandler errorHandler = new ErrorHandler(
                responseBuilder.getStatusCode(), 
                config
            );
            errorHandler.handle(request, responseBuilder);
        }
        
        // 5. Send response
        finishRequest(responseBuilder, request);
    }
}
```

### Error Handling Flow

```
Handler throws Exception
    ↓
ClientHandler catches
    ↓
Set status = 500
    ↓
Check if status.isError()
    ↓
Create ErrorHandler
    ↓
Generate error page
    ↓
Send to client
```

## Virtual Host Resolution

Multiple server configs can bind to same IP:port with different `serverName`:

```java
private ServerConfig resolveConfig(String hostHeader) {
    String hostName = (hostHeader != null && hostHeader.contains(":"))
        ? hostHeader.split(":")[0]
        : hostHeader;
    
    // Match by Host header
    for (ServerConfig cfg : virtualHosts) {
        if (cfg.getServerName().equalsIgnoreCase(hostName)) {
            return cfg;
        }
    }
    
    // Fallback to default server
    return virtualHosts.stream()
        .filter(ServerConfig::isDefault)
        .findFirst()
        .orElse(virtualHosts.get(0));
}
```

**Example:**

```json
{
  "servers": [
    {
      "host": "127.0.0.1",
      "ports": [8080],
      "serverName": "site1.local",
      "defaultServer": true,
      "routes": [...]
    },
    {
      "host": "127.0.0.1",
      "ports": [8080],
      "serverName": "site2.local",
      "routes": [...]
    }
  ]
}
```

**Routing:**
```
Request: GET / HTTP/1.1
Host: site1.local
→ Uses first server config

Request: GET / HTTP/1.1
Host: site2.local
→ Uses second server config

Request: GET / HTTP/1.1
Host: unknown.local
→ Uses default server (first config)
```

## Testing Routes

```bash
# Test route matching
curl http://localhost:8080/                      # → / route
curl http://localhost:8080/api/users            # → /api route
curl http://localhost:8080/static/style.css     # → /static route

# Test method validation
curl -X POST http://localhost:8080/             # → 405 if only GET allowed
curl -X DELETE http://localhost:8080/file.txt   # → Calls DeleteHandler

# Test path traversal protection
curl http://localhost:8080/../../../etc/passwd  # → 403 Forbidden

# Test virtual hosts
curl -H "Host: site1.local" http://localhost:8080/  # → site1 config
curl -H "Host: site2.local" http://localhost:8080/  # → site2 config
```

## Performance Characteristics

**Route Matching:**
- O(n) where n = number of routes
- Typically < 10 routes, negligible overhead
- Could optimize with trie/prefix tree for 100+ routes

**Handler Instantiation:**
- New handler instance per request
- Lightweight objects (no state)
- Garbage collected after response sent

**File System Checks:**
- Canonical path resolution done once per request
- File existence/permissions checked by handler
- OS-level caching benefits repeated checks

## Summary

**Router responsibilities:**
1. Match request path to route
2. Validate HTTP method
3. Resolve filesystem path
4. Check security (path traversal)
5. Detect resource type (file/directory/CGI)
6. Select appropriate handler

**Handler responsibilities:**
1. Process request
2. Configure response (status, headers, body)
3. Handle errors gracefully

**Clean separation:**
- Router = routing logic
- Handlers = business logic
- No handler knows about routing
- Handlers are independently testable