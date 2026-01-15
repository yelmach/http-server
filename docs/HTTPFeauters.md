# HTTP Features: Redirection, Directory Listing, Sessions & Cookies

## 1. HTTP Redirection

### Overview

Redirects clients from one URL to another using 301 (permanent) or 302 (temporary) status codes.

### Configuration

```json
{
  "path": "/old-page",
  "redirectTo": "/new-page",
  "redirectStatusCode": 301
}
```

**Status codes:**
- `301` - Moved Permanently (default, SEO-friendly)
- `302` - Found (temporary redirect)

### Implementation

```java
public class RedirectHandler implements Handler {
    private final String redirectTo;
    private final HttpStatusCode statusCode;
    
    @Override
    public void handle(HttpRequest request, ResponseBuilder response) {
        response.status(statusCode)
                .header("Location", redirectTo);
    }
}
```

**Response:**
```http
HTTP/1.1 301 Moved Permanently
Location: /new-page
Connection: keep-alive
```

### Router Integration

Redirection is checked **before** file handling:

```java
// 1. Find matching route
RouteConfig route = findMatchingRoute(requestPath);

// 2. Handle Redirection (early return)
if (route.getRedirectTo() != null) {
    HttpStatusCode code = route.getRedirectStatusCode() == 302 
        ? HttpStatusCode.FOUND 
        : HttpStatusCode.MOVED_PERMANENTLY;
    return new RedirectHandler(route.getRedirectTo(), code);
}

// 3. Continue with normal file handling...
```

### Usage Examples

```bash
# Permanent redirect
curl -I http://localhost:8080/old-page
# HTTP/1.1 301 Moved Permanently
# Location: /new-page

# Temporary redirect
curl -I http://localhost:8080/temp-redirect
# HTTP/1.1 302 Found
# Location: /
```

---

## 2. Directory Listing

### Overview

Generates HTML page showing directory contents when `directoryListing: true` and no index file exists.

### Configuration

```json
{
  "path": "/images",
  "root": "./www/images",
  "methods": ["GET"],
  "directoryListing": true
}
```

### Implementation

```java
public class DirectoryHandler implements Handler {
    private final File resource;
    
    @Override
    public void handle(HttpRequest request, ResponseBuilder response) {
        File[] files = resource.listFiles();
        
        // Sort: directories first, then alphabetically
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        
        String html = generateDirectoryPage(request, files);
        response.status(HttpStatusCode.OK)
                .contentType("text/html")
                .body(html);
    }
}
```

### Generated HTML

```html
<!DOCTYPE html>
<html>
<head>
    <title>/images/</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 40px; }
        .file-list { list-style: none; padding: 0; display: flex; flex-wrap: wrap; gap: 16px; }
        .file-item { border: 1px solid #eee; padding: 12px 16px; min-width: 200px; }
        .dir { font-weight: bold; }
    </style>
</head>
<body>
    <h1>Index of /images/</h1>
    <ul class="file-list">
        <li class="file-item dir"><a href="../">../</a></li>
        <li class="file-item dir"><a href="/images/photos/">photos/</a></li>
        <li class="file-item"><a href="/images/logo.png">logo.png</a></li>
    </ul>
</body>
</html>
```

### Router Logic

```java
if (resource.isDirectory()) {
    // Option 1: Directory listing enabled
    if (Boolean.TRUE.equals(route.getDirectoryListing())) {
        return new DirectoryHandler(route, resource);
    }
    
    // Option 2: Serve index file if exists
    File indexFile = new File(resource, route.getIndex() != null ? route.getIndex() : "index.html");
    if (indexFile.exists()) {
        return new StaticFileHandler(indexFile);
    }
    
    // Option 3: Forbidden
    return new ErrorHandler(HttpStatusCode.FORBIDDEN, serverConfig);
}
```

### Features

**Parent Directory Link:**
- Shows `../` unless already at root (`/`)

**Sorting:**
- Directories appear first
- Case-insensitive alphabetical order

**Trailing Slashes:**
- Directories automatically get `/` suffix in URLs

### Usage

```bash
# Access directory
curl http://localhost:8080/images/

# Click on subdirectory
curl http://localhost:8080/images/photos/
```

---

## 3. Session Management

### Overview

Stateful session tracking using cookies. Each session has a unique ID and stores key-value data on the server.

### Architecture

```
Client Cookie (SESSIONID=abc123) → SessionManager.getSession(id) → Map<String, Object>
```

**Components:**
- `SessionManager` - Singleton, stores all sessions in ConcurrentHashMap
- `Cookie` - Parses/formats cookie strings
- `SessionHandler` - Demo handler showing session usage

### SessionManager

```java
public class SessionManager {
    private static final SessionManager instance = new SessionManager();
    private final Map<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();
    
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new HashMap<>());
        return sessionId;
    }
    
    public Map<String, Object> getSession(String sessionId) {
        return sessions.get(sessionId);
    }
    
    public boolean isValid(String sessionId) {
        return sessions.containsKey(sessionId);
    }
}
```

**Thread-safe:** Uses `ConcurrentHashMap` for concurrent access.

### Cookie Class

```java
public class Cookie {
    private final String name;
    private final String value;
    
    // Parse: "SESSIONID=abc123"
    public static Cookie parse(String cookieString) {
        String[] parts = cookieString.split("=", 2);
        if (parts.length == 2) {
            return new Cookie(parts[0].trim(), parts[1].trim());
        }
        return null;
    }
    
    // Format for response
    @Override
    public String toString() {
        return name + "=" + value + "; Path=/; HttpOnly";
    }
}
```

**Security flags:**
- `Path=/` - Cookie valid for entire site
- `HttpOnly` - Prevents JavaScript access (XSS protection)

### Session Handler Example

```java
public class SessionHandler implements Handler {
    @Override
    public void handle(HttpRequest request, ResponseBuilder response) {
        SessionManager sm = SessionManager.getInstance();
        String sessionId = request.getCookie("SESSIONID");
        
        String body;
        
        // Check if session exists
        if (sessionId != null && sm.isValid(sessionId)) {
            Map<String, Object> data = sm.getSession(sessionId);
            int views = (int) data.getOrDefault("views", 0) + 1;
            data.put("views", views);
            
            body = "<h1>Session Found!</h1><p>Views: " + views + "</p>";
            response.status(HttpStatusCode.OK).body(body);
            
        } else {
            // Create new session
            sessionId = sm.createSession();
            sm.getSession(sessionId).put("views", 1);
            
            body = "<h1>New Session Created!</h1><p>Views: 1</p>";
            response.status(HttpStatusCode.OK).body(body);
            
            // Set cookie
            response.header("Set-Cookie", new Cookie("SESSIONID", sessionId).toString());
        }
        
        response.header("Content-Type", "text/html");
    }
}
```

### Cookie Parsing in RequestParser

Cookies are extracted from the `Cookie` header during parsing:

```java
private void addHeader(String name, String val) {
    httpRequest.getHeaders().add(name, val);
    
    if (name.equalsIgnoreCase("Cookie")) {
        for (String p : val.split(";")) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2) {
                httpRequest.addCookie(kv[0].trim(), kv[1].trim());
            }
        }
    }
}
```

**Request example:**
```http
GET /session HTTP/1.1
Host: localhost
Cookie: SESSIONID=abc123; theme=dark
```

**Parsed:**
```java
request.getCookie("SESSIONID") // → "abc123"
request.getCookie("theme")      // → "dark"
```

### Usage Flow

**First Request:**
```bash
curl -c cookies.txt http://localhost:8080/session
# Response:
# HTTP/1.1 200 OK
# Set-Cookie: SESSIONID=550e8400-e29b-41d4-a716-446655440000; Path=/; HttpOnly
# <h1>New Session Created!</h1><p>Views: 1</p>
```

**Subsequent Requests:**
```bash
curl -b cookies.txt http://localhost:8080/session
# Request includes: Cookie: SESSIONID=550e8400-e29b-41d4-a716-446655440000
# Response:
# <h1>Session Found!</h1><p>Views: 2</p>
```

### Session Data Storage

Sessions store any data type:

```java
Map<String, Object> session = sm.getSession(sessionId);
session.put("username", "alice");
session.put("loginTime", System.currentTimeMillis());
session.put("cart", new ArrayList<String>());

String username = (String) session.get("username");
List<String> cart = (List<String>) session.get("cart");
```

### Limitations & Production Notes

**Current Implementation:**
- No session expiration (sessions live forever)
- No persistence (sessions lost on restart)
- No session size limits
- No HTTPS-only flag (Secure attribute)

**Production Enhancements:**
```java
// Expiration
public String toString() {
    return name + "=" + value 
         + "; Path=/; HttpOnly; Max-Age=3600"; // 1 hour
}

// HTTPS only
return name + "=" + value + "; Path=/; HttpOnly; Secure";

// SameSite protection
return name + "=" + value + "; Path=/; HttpOnly; SameSite=Strict";
```

### Configuration

Session endpoint is hardcoded in router (demo only):

```java
if (httpRequest.getPath().equals("/session")) {
    return new SessionHandler();
}
```

For production, add proper route configuration:
```json
{
  "path": "/session",
  "handler": "SessionHandler",
  "methods": ["GET"]
}
```

---

## Testing All Features

### Redirection
```bash
# Test 301 redirect
curl -I http://localhost:8080/old-page
# Should show: Location: /new-page

# Follow redirect
curl -L http://localhost:8080/old-page
```

### Directory Listing
```bash
# Create test directory
mkdir -p www/test-dir
touch www/test-dir/file1.txt
touch www/test-dir/file2.txt

# Access directory
curl http://localhost:8080/test-dir/
```

### Sessions
```bash
# Create session
curl -c /tmp/cookies.txt http://localhost:8080/session

# Use session
curl -b /tmp/cookies.txt http://localhost:8080/session
curl -b /tmp/cookies.txt http://localhost:8080/session  # Views: 3
```

### Combined Test
```bash
# Session with redirect
curl -c /tmp/cookies.txt -L http://localhost:8080/old-page
# Sets cookie, follows redirect, reaches new page
```

---

## Summary Table

| Feature | Handler | Route Config | Response |
|---------|---------|--------------|----------|
| Redirection | `RedirectHandler` | `redirectTo`, `redirectStatusCode` | 301/302 + Location header |
| Directory Listing | `DirectoryHandler` | `directoryListing: true` | HTML page with file list |
| Sessions | `SessionHandler` | Hardcoded `/session` | Set-Cookie header |
| Cookies | RequestParser | Automatic parsing | Stored in HttpRequest |

## Integration with Router

```java
// Priority order in Router.route():
1. Find matching route
2. Handle redirection (early return)
3. Validate HTTP method
4. Check if directory:
   - If directoryListing enabled → DirectoryHandler
   - If index file exists → StaticFileHandler
   - Otherwise → ErrorHandler (403)
5. Handle file/CGI/upload/delete
```

All features integrate seamlessly through the router's routing logic.