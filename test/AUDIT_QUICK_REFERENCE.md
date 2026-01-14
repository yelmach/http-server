# Audit Quick Reference Guide

Quick answers to audit questions with code references.

## Architecture Questions

### Q1: How does an HTTP server work?

**Answer:**
1. Server binds to TCP port (8080)
2. Waits for client connections using Selector (NIO)
3. Accepts connection, creates SocketChannel
4. Reads HTTP request from socket (non-blocking)
5. Parses request (method, path, headers, body)
6. Routes to appropriate handler based on config
7. Handler generates response
8. Writes response to socket
9. Keeps connection alive or closes based on HTTP version

**Code locations:**
- `Server.java:29-85` - Main event loop
- `ClientHandler.java:68-121` - Request handling
- `RequestParser.java` - HTTP parsing
- `Router.java:14-94` - Request routing

---

### Q2: Which function is used for I/O Multiplexing?

**Answer:** `Selector.select()` from `java.nio.channels.Selector`

**Code:**
```java
// Server.java:58
selector.select(50); // Wait up to 50ms for events
Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
```

**How it works:**
- Single Selector monitors all channels (server sockets + client sockets)
- Blocks until at least one channel is ready (with 50ms timeout)
- Returns set of ready keys (ACCEPT, READ, or WRITE)
- Process each ready key in event loop

---

### Q3: Is the server using only one select?

**Answer:** Yes, single Selector instance for everything.

**Code:**
```java
// Server.java:25
private Selector selector;

// Server.java:29
selector = Selector.open(); // Single instance

// Server.java:46-48 - All servers register with same selector
serverSocket.register(selector, SelectionKey.OP_ACCEPT, virtualHosts);
```

---

### Q4: Why is using only one select important?

**Answer:**
1. **Scalability:** Single thread handles thousands of connections
2. **Efficiency:** No thread-per-connection overhead (context switching, memory)
3. **Simplicity:** No race conditions, easier to reason about
4. **Performance:** Non-blocking I/O means thread never blocks

**Alternative (bad):** Thread-per-connection model:
- 1000 connections = 1000 threads
- Each thread: 1MB stack = 1GB memory just for stacks
- Context switching overhead
- Complex synchronization

**Our approach:**
- 1000 connections = 1 thread
- Minimal memory overhead
- No context switching
- No synchronization needed

---

### Q5: Is there only one read/write per client per select?

**Answer:** Yes, one read() or write() per client per select iteration.

**Code:**
```java
// ClientHandler.java:68-75
public void read() throws IOException {
    readBuffer.clear();
    int bytesRead = client.read(readBuffer); // Single read call
    // Process what was read
}

// ClientHandler.java:194-206
public void write() throws IOException {
    ByteBuffer responseBuffer = responseQueue.peek();
    if (responseBuffer != null) {
        client.write(responseBuffer); // Single write call
    }
}
```

**Why:** Non-blocking I/O means read/write returns immediately. If more data available, it will be handled in next select iteration.

---

### Q6: Are return values for I/O functions checked?

**Answer:** Yes, all return values are checked.

**Code:**
```java
// ClientHandler.java:72-76
int bytesRead = client.read(readBuffer);
if (bytesRead == -1) {
    close(); // Client disconnected
    logger.info("Client disconnected.");
    return;
}

// ClientHandler.java:78-91
if (bytesRead > 0) {
    readBuffer.flip();
    ParsingResult result = parser.parse(readBuffer);
    // Handle result
}
```

**Checked cases:**
- `-1`: Client disconnected (EOF)
- `0`: No data available (non-blocking)
- `> 0`: Data read successfully

---

### Q7: If an error is returned, is the client removed?

**Answer:** Yes, on any error, client is closed and removed.

**Code:**
```java
// ClientHandler.java:107-111
if (result.isError()) {
    logger.severe("Parsing error: " + result.getErrorMessage());
    close(); // Remove client
}

// Server.java:78-82
} catch (IOException e) {
    logger.severe(e.getMessage());
    key.cancel(); // Remove from selector
    key.channel().close(); // Close socket
}

// ClientHandler.java:268-273
private void close() throws IOException {
    if (cgiProcess != null && cgiProcess.isAlive()) {
        cgiProcess.destroyForcibly();
    }
    selectionKey.cancel(); // Remove from selector
    client.close(); // Close socket
}
```

---

### Q8: Is writing and reading ALWAYS done through select?

**Answer:** Yes for client I/O. CGI is special case (ProcessBuilder, but async).

**Read path:**
1. `select()` returns with OP_READ
2. `handleRead()` called
3. `ClientHandler.read()` performs single read()

**Write path:**
1. Handler queues response
2. Change interest to OP_WRITE
3. `select()` returns with OP_WRITE
4. `handleWrite()` called
5. `ClientHandler.write()` performs single write()

**Code:**
```java
// Server.java:58-86
while (true) {
    selector.select(50);
    // ...
    if (key.isAcceptable()) {
        handleAccept(key);
    } else if (key.isReadable()) {
        handleRead(key); // Read through select
    } else if (key.isWritable()) {
        handleWrite(key); // Write through select
    }
}
```

**CGI special case:**
- CGI output read using `Process.getInputStream()`
- Done asynchronously in `checkPendingCGI()` method
- Not blocking the main event loop

---

## Configuration Tests

### Single Server, Single Port

**Test:**
```bash
# Use default config.json
make run
curl http://localhost:8080/
```

**Expected:** 200 OK

---

### Multiple Servers, Different Ports

**Test:**
```bash
# Edit config.json:
{
  "servers": [
    {"ports": [8080], ...},
    {"ports": [8081], ...}
  ]
}

make run
curl http://localhost:8080/  # Should work
curl http://localhost:8081/  # Should work
```

---

### Virtual Hosts (Same Port, Different Hostnames)

**Test:**
```bash
# Use configs/config_multiple_hosts.json
make run-config CONFIG=configs/config_multiple_hosts.json

# Test with different Host headers
curl -H "Host: primary.local" http://127.0.0.1:8080/
curl -H "Host: secondary.local" http://127.0.0.1:8080/
```

**Code:** `ClientHandler.java:215-228` - Virtual host resolution

---

### Custom Error Pages

**Test:**
```bash
# Ensure config has errorPages section
curl http://localhost:8080/nonexistent
# Should return custom 404 page if configured
```

**Code:** `ErrorHandler.java:37-58`

---

### Body Size Limit

**Test:**
```bash
# Use test/configs/test_body_limit.json (1KB limit)
make run-config CONFIG=test/configs/test_body_limit.json

# Small body (should succeed)
curl -X POST -d "small" http://localhost:8080/upload/test.txt

# Large body (should fail with 413)
dd if=/dev/zero bs=1K count=2 | curl -X POST --data-binary @- http://localhost:8080/upload/large.txt
```

**Code:** `RequestParser.java:168-172`, `ClientHandler.java:39`

---

### Route Configuration

**Show routes are taken into account:**
```bash
# GET to /images/ (directory listing enabled)
curl http://localhost:8080/images/
# Should return directory listing

# POST to /images/ (not allowed)
curl -X POST http://localhost:8080/images/
# Should return 405 Method Not Allowed
```

**Code:** `Router.java:14-94`

---

### Default Index File

**Test:**
```bash
# Access directory
curl http://localhost:8080/
# Should serve index.html from root
```

**Code:** `Router.java:62-66`

---

### Method Restrictions

**Test:**
```bash
# Use test/configs/test_method_restrictions.json
make run-config CONFIG=test/configs/test_method_restrictions.json

# Allowed: GET to /read-only
curl http://localhost:8080/read-only/

# Not allowed: POST to /read-only
curl -X POST http://localhost:8080/read-only/
# Should return 405
```

**Code:** `Router.java:34-37`

---

## HTTP Methods

### GET Request

**Test:**
```bash
curl -v http://localhost:8080/index.html
```

**Expected:**
- Status: 200 OK
- Content-Type: text/html
- Content-Length: [size]
- Body: HTML content

**Code:** `StaticFileHandler.java`

---

### POST Request (Upload)

**Test:**
```bash
echo "test content" > test.txt
curl -v -X POST --data-binary @test.txt http://localhost:8080/upload/test.txt
```

**Expected:**
- Status: 201 Created or 200 OK
- File saved to www/uploads/test.txt

**Code:** `UploadHandler.java`

---

### DELETE Request

**Test:**
```bash
# Create file first
curl -X POST -d "test" http://localhost:8080/upload/deleteme.txt

# Delete it
curl -v -X DELETE http://localhost:8080/upload/deleteme.txt
```

**Expected:**
- Status: 204 No Content (if successful)
- Status: 404 (if not found)

**Code:** `DeleteHandler.java`

---

### Wrong Request (Malformed)

**Test:**
```bash
(echo -e "INVALID REQUEST\r\n\r\n"; sleep 1) | nc localhost 8080
```

**Expected:**
- Connection closed or error response
- Server continues running (doesn't crash)

**Code:** `RequestParser.java:160-164`, `ClientHandler.java:107-111`

---

### File Upload Integrity

**Test:**
```bash
# Create test file
dd if=/dev/urandom of=/tmp/test.bin bs=1K count=100
md5sum /tmp/test.bin

# Upload
curl -X POST --data-binary @/tmp/test.bin http://localhost:8080/upload/test.bin

# Download
curl http://localhost:8080/upload/test.bin -o /tmp/downloaded.bin
md5sum /tmp/downloaded.bin

# MD5 sums should match
```

---

### Sessions and Cookies

**Test:**
```bash
# First request (creates session)
curl -v -c cookies.txt http://localhost:8080/session

# Check Set-Cookie header
cat cookies.txt

# Second request (uses session)
curl -v -b cookies.txt http://localhost:8080/session
# View count should increment
```

**Code:**
- `SessionHandler.java` - Session logic
- `SessionManager.java` - Session storage
- `Cookie.java` - Cookie handling
- `RequestParser.java:529-537` - Cookie parsing

---

## Browser Interaction

### Headers Check

**Test in browser:**
1. Open http://localhost:8080/
2. Open Developer Tools (F12)
3. Go to Network tab
4. Refresh page
5. Click on request

**Expected headers:**
- Response: HTTP/1.1 200 OK
- Content-Type: text/html
- Content-Length: [size]
- Date: [current date]
- Connection: keep-alive

---

### Wrong URL

**Test:**
```bash
curl -v http://localhost:8080/nonexistent.html
```

**Expected:**
- Status: 404 Not Found
- Custom error page (HTML)

---

### Directory Listing

**Test:**
```bash
curl http://localhost:8080/images/
```

**Expected:** HTML listing of files (if enabled)

---

### Redirect

**Test:**
```bash
curl -v http://localhost:8080/old-page
```

**Expected:**
- Status: 301 Moved Permanently
- Location: /

---

### CGI with Chunked/Unchunked

**Test unchunked:**
```bash
curl -X POST -H "Content-Length: 10" -d "0123456789" http://localhost:8080/cgi-3
```

**Test chunked:**
```bash
curl -X POST -H "Transfer-Encoding: chunked" --data "test" http://localhost:8080/cgi-3
```

**Code:** `RequestParser.java:135-162` (chunked), `CGIHandler.java:50-59` (body to stdin)

---

## Port Configuration

### Multiple Ports

**Test:**
```bash
# Edit config to have ports: [8080, 8081, 8082]
make run

# Test each port
curl http://localhost:8080/
curl http://localhost:8081/
curl http://localhost:8082/
```

---

### Duplicate Port (Should Fail)

**Test:**
```bash
make run-config CONFIG=test/configs/test_duplicate_port.json
```

**Expected:**
- Error message: "Duplicate port: 8080"
- Server doesn't start

**Code:** `ConfigValidator.java:72-79`

---

### Multiple Servers, Common Ports

**Scenario:** Two servers on same port+IP but different serverNames (virtual hosts)

**Test:**
```bash
make run-config CONFIG=configs/config_multiple_hosts.json

curl -H "Host: primary.local" http://127.0.0.1:8080/
curl -H "Host: secondary.local" http://127.0.0.1:8080/
```

**Expected:** Both work, serve different content

**Why it works:**
- Single socket binds to port
- Multiple ServerConfig attached to same key
- Host header used to resolve which config to use

**Code:** `Server.java:29-48`, `ClientHandler.java:215-228`

---

## Stress Testing

### Siege Test

**Command:**
```bash
make stress-test
# Or manually:
siege -b -c 50 -t 30S http://127.0.0.1:8080/
```

**Expected:**
- Availability: >= 99.5%
- No server crashes
- No hanging connections

---

### Check Hanging Connections

**Before test:**
```bash
netstat -an | grep 8080 | wc -l
```

**During test:**
```bash
siege -b -c 100 -t 10S http://localhost:8080/ &
sleep 5
netstat -an | grep 8080 | grep ESTABLISHED | wc -l
```

**After test (wait 15 seconds):**
```bash
sleep 15
netstat -an | grep 8080 | wc -l
```

**Expected:** Connection count returns to normal, no accumulation

---

## Quick Test Commands

```bash
# Run all tests
make test                    # Unit tests (103 tests)
make run &                   # Start server
make test-integration        # Integration tests
make test-manual             # Manual tests (comprehensive)
make stress-test             # Siege stress test
kill %1                      # Stop server

# Individual tests
curl http://localhost:8080/                              # GET
curl -X POST -d "data" http://localhost:8080/upload/f   # POST
curl -X DELETE http://localhost:8080/upload/f           # DELETE
curl http://localhost:8080/nonexistent                  # 404
curl http://localhost:8080/images/                      # Directory
curl http://localhost:8080/old-page                     # Redirect
curl http://localhost:8080/cgi-1                        # CGI
curl -c c.txt -b c.txt http://localhost:8080/session   # Session

# Virtual hosts
curl -H "Host: test.local" http://127.0.0.1:8080/

# Chunked encoding
curl -X POST -H "Transfer-Encoding: chunked" --data "x" http://localhost:8080/upload/f

# Body limit
dd if=/dev/zero bs=1M count=200 | curl -X POST --data-binary @- http://localhost:8080/upload/f

# Malformed request
(echo "INVALID"; sleep 1) | nc localhost 8080
```

---

## Code Reference Index

| Component | File | Key Methods |
|-----------|------|-------------|
| Event Loop | `Server.java:58-86` | `select()`, `handleAccept()`, `handleRead()`, `handleWrite()` |
| Request Parsing | `RequestParser.java` | `parse()`, `parseRequestLine()`, `parseHeaders()` |
| Client Handling | `ClientHandler.java` | `read()`, `write()`, `handleRequest()` |
| Routing | `Router.java:14-94` | `route()`, `findMatchingRoute()` |
| Static Files | `StaticFileHandler.java` | `handle()` |
| Upload | `UploadHandler.java` | `handle()`, `handleMultipartUpload()` |
| Delete | `DeleteHandler.java` | `handle()` |
| CGI | `CGIHandler.java` | `handle()` |
| Sessions | `SessionManager.java` | `createSession()`, `getSession()` |
| Cookies | `Cookie.java` | `parse()`, `toString()` |
| Config | `ConfigLoader.java` | `load()` |
| Validation | `ConfigValidator.java` | `validateServersFields()` |

---

## Summary Checklist

- [x] Single threaded (1 thread)
- [x] Event-driven NIO (Selector.select)
- [x] Single Selector for all I/O
- [x] Non-blocking I/O
- [x] HTTP/1.1 compliant
- [x] GET, POST, DELETE methods
- [x] Static file serving
- [x] File uploads
- [x] Directory listing
- [x] Sessions and cookies
- [x] Error pages (400, 403, 404, 405, 413, 500)
- [x] HTTP redirects (301, 302)
- [x] Keep-alive connections
- [x] Chunked encoding (requests)
- [x] CGI execution
- [x] Request timeout (10s)
- [x] CGI timeout (5s)
- [x] Body size limits
- [x] Virtual hosts
- [x] Multiple ports
- [x] Path traversal prevention
- [x] Configuration validation

---

Good luck with your audit!
