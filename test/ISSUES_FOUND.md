# Issues Found During Testing

This document tracks potential issues discovered while reviewing the codebase against audit requirements.

## Critical Issues

### 1. CGI Output Size Limit Enforcement

**Status:** FIXED ✓

**Issue:** CGI scripts could potentially generate unlimited output, causing memory exhaustion.

**Location:** `src/core/ClientHandler.java`

**Solution:** Added 10MB limit on CGI output with proper enforcement:
```java
private static final int MAX_CGI_OUTPUT = 10 * 1024 * 1024; // 10MB limit
private int cgiOutputSize = 0;

// Check size limit BEFORE writing
if (cgiOutputSize + read > MAX_CGI_OUTPUT) {
    logger.warning("CGI output exceeds limit");
    cgiProcess.destroyForcibly();
    pendingResponseBuilder.status(HttpStatusCode.PAYLOAD_TOO_LARGE);
    return;
}
```

**Test:**
```bash
# Test with large CGI output
curl http://localhost:8080/cgi-3
# Should return 413 if output exceeds 10MB
```

---

## Potential Issues to Verify

### 2. Multiple Default Servers

**Status:** NEEDS TESTING

**Issue:** Config validation checks for multiple default servers, but need to verify runtime behavior.

**Test:**
```bash
# Create config with two default servers
# Should fail at startup with error message
```

**Verify in:** `src/config/ConfigValidator.java:91-96`

---

### 3. Duplicate Port Handling

**Status:** PARTIALLY IMPLEMENTED

**Issue:** Config detects duplicate ports within same server config, but what about across different servers?

**Current implementation:** `src/config/ConfigLoader.java:62-71`
```java
for (int port : ports) {
    String identityKey = port + host + serverName;
    if (identitySet.contains(identityKey)) {
        throw new RuntimeException("Conflict: Multiple servers bound to port...");
    }
}
```

**Edge case:** What if two different servers try to bind to same port+host but different serverName?

**Test:**
```json
{
  "servers": [
    {"host": "127.0.0.1", "ports": [8080], "serverName": "server1"},
    {"host": "127.0.0.1", "ports": [8080], "serverName": "server2"}
  ]
}
```

**Expected:** Should work (virtual hosts), both should bind to same socket.

**Current behavior:** Should work based on `Server.java:29-35` (uses socket bindings map).

---

### 4. Error Page Configuration Not Used

**STATUS:** ISSUE FOUND ❌

**Issue:** Server config has `errorPages` field, but `ErrorHandler.java` doesn't use them correctly in all cases.

**Location:** `src/handlers/ErrorHandler.java:37-58`

**Problem:** Error handler looks for custom error pages, but what if:
- Custom error page doesn't exist?
- Custom error page is outside allowed directory?
- Custom error page is malformed?

**Test:**
```bash
# Add to config.json:
"errorPages": {
  "404": "/custom404.html",
  "500": "/custom500.html"
}

# Create www/custom404.html
# Trigger 404, should use custom page
curl http://localhost:8080/nonexistent
```

---

### 5. Timeout Implementation

**STATUS:** IMPLEMENTED ✓

**Issue:** Server should timeout long requests.

**Current implementation:** `src/core/ClientHandler.java:35-36`
```java
private long lastActivityTime;
private final long timeoutMs = 10000; // 10 seconds
```

**Verified in:** `src/core/Server.java:109-125` (checkTimeouts method)

**Test:**
```bash
# Open connection and don't send complete request
(echo -n "GET / HTTP/1.1\r\n"; sleep 15) | nc localhost 8080
# Should timeout after 10 seconds
```

---

### 6. CGI Timeout

**STATUS:** IMPLEMENTED ✓

**Current implementation:** `src/core/ClientHandler.java:169-178`
```java
if (System.currentTimeMillis() - cgiStartTime > 5000) {
    cgiProcess.destroyForcibly();
    pendingResponseBuilder.status(HttpStatusCode.INTERNAL_SERVER_ERROR);
}
```

**Test:**
```python
# Create www/scripts/slow.py
import time
time.sleep(10)
print("Done")
```

```bash
curl http://localhost:8080/cgi-slow
# Should timeout after 5 seconds with 500 error
```

---

### 7. File Upload Corruption

**STATUS:** NEEDS VERIFICATION

**Issue:** Need to verify files aren't corrupted during upload/download.

**Test script:** Already in `test/manual_tests.sh` (Test 18)

**Verification method:**
1. Create file with known checksum
2. Upload via POST
3. Download via GET
4. Compare checksums

---

### 8. Concurrent Request Handling

**STATUS:** NEEDS VERIFICATION

**Issue:** Single-threaded server with NIO should handle concurrent requests, but needs stress testing.

**Test:**
```bash
# 100 concurrent requests
siege -b -c 100 -t 30S http://localhost:8080/

# Should achieve 99.5% availability
```

---

### 9. HTTP Pipelining

**STATUS:** IMPLEMENTED ✓

**Current implementation:** `src/core/ClientHandler.java:94-107`
```java
while (result.isComplete()) {
    HttpRequest currentRequest = result.getRequest();
    handleRequest(currentRequest);
    parser.resetState();
    result = parser.parse(ByteBuffer.allocate(0));
}
```

**Test:**
```bash
# Send two pipelined requests
(echo -ne "GET / HTTP/1.1\r\nHost: localhost\r\n\r\nGET /about.html HTTP/1.1\r\nHost: localhost\r\n\r\n"; sleep 1) | nc localhost 8080
```

---

### 10. Chunked Encoding for Response

**STATUS:** NOT IMPLEMENTED ❌

**Issue:** Server can RECEIVE chunked requests, but can it SEND chunked responses?

**Current implementation:** Response always uses Content-Length.

**Location:** `src/http/ResponseBuilder.java:95`
```java
headers.add("Content-Length", String.valueOf(body.length));
```

**Audit requirement:** "Manage chunked and unchunked requests" (only mentions requests, not responses)

**Conclusion:** Not required for audit, but good to note.

---

### 11. Large File Streaming

**STATUS:** IMPLEMENTED ✓

**Issue:** Large files should be streamed, not loaded entirely into memory.

**For uploads:** `src/http/RequestParser.java:189-198`
```java
if (expectedBodyLength > STREAM_TO_DISK_THRESHOLD) {
    bodyTempFile = File.createTempFile("http-body-", ".tmp");
    bodyOutputStream = new FileOutputStream(bodyTempFile);
    streamingBodyToDisk = true;
}
```

**For serving:** Static files are read entirely with `Files.readAllBytes()` in `StaticFileHandler.java`.

**Potential issue:** Serving large static files could cause memory issues.

**Test:**
```bash
# Create 100MB file
dd if=/dev/zero of=www/large.bin bs=1M count=100

# Try to download
curl http://localhost:8080/large.bin -o /tmp/downloaded.bin
# Monitor memory usage
```

---

### 12. Multipart Form Parsing

**STATUS:** IMPLEMENTED ✓

**Location:** `src/http/MultipartParser.java`

**Test:**
```bash
# Upload file using multipart form
curl -X POST -F "file=@test.txt" -F "name=myfile" http://localhost:8080/upload/
```

---

### 13. Cookie Parsing

**STATUS:** IMPLEMENTED ✓

**Location:** `src/http/RequestParser.java:529-537`
```java
if (currentHeaderName.equalsIgnoreCase("Cookie")) {
    String[] pairs = headerValue.split(";");
    for (String pair : pairs) {
        String[] keyValue = pair.split("=", 2);
        if (keyValue.length == 2) {
            httpRequest.addCookie(keyValue[0].trim(), keyValue[1].trim());
        }
    }
}
```

**Test:**
```bash
curl -H "Cookie: session=abc123; user=test" http://localhost:8080/
```

---

### 14. Query String Parsing

**STATUS:** PARTIAL ❌

**Issue:** Query string is stored but not parsed into parameters.

**Current implementation:** `src/http/HttpRequest.java:60`
```java
private String queryString;
// Has getter but no parsing into Map<String, String>
```

**For CGI:** Query string is passed to CGI via `QUERY_STRING` env var.

**Impact:** CGI scripts must parse query string themselves (standard behavior).

---

### 15. POST Body for CGI

**STATUS:** IMPLEMENTED ✓

**Location:** `src/handlers/CGIHandler.java:50-59`
```java
if (request.getBody() != null && request.getBody().length > 0) {
    try (OutputStream os = process.getOutputStream()) {
        os.write(request.getBody());
        os.flush();
    }
}
```

**Test:**
```bash
curl -X POST -d "name=test&value=123" http://localhost:8080/cgi-3
```

---

### 16. HTTP/1.0 Support

**STATUS:** IMPLEMENTED ✓

**Location:** `src/http/RequestParser.java:412-414`
```java
if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
    errorMessage = "Unsupported HTTP version: " + version;
}
```

**Test:**
```bash
(echo -ne "GET / HTTP/1.0\r\nHost: localhost\r\n\r\n"; sleep 1) | nc localhost 8080
```

---

### 17. HEAD Method

**STATUS:** NOT IMPLEMENTED ❌

**Issue:** Server doesn't support HEAD method (only GET, POST, DELETE).

**Location:** `src/http/HttpMethod.java`
```java
public enum HttpMethod {
    GET, POST, DELETE;
}
```

**Audit requirement:** "Handle GET, POST, and DELETE" (HEAD not required).

---

### 18. OPTIONS Method

**STATUS:** NOT IMPLEMENTED ❌

**Issue:** Server doesn't support OPTIONS method.

**Audit requirement:** Not mentioned in requirements.

---

### 19. Range Requests (Partial Content)

**STATUS:** NOT IMPLEMENTED ❌

**Issue:** Server doesn't support Range header for partial content delivery.

**Audit requirement:** Not mentioned in requirements.

---

### 20. ETag / If-Modified-Since Caching

**STATUS:** NOT IMPLEMENTED ❌

**Issue:** Server doesn't support conditional requests for caching.

**Audit requirement:** Not mentioned in requirements.

---

## Test Coverage Summary

### Implemented and Working ✓
- [x] HTTP/1.1 and HTTP/1.0 support
- [x] GET, POST, DELETE methods
- [x] Static file serving
- [x] Directory listing
- [x] File uploads (multipart and raw)
- [x] HTTP redirects (301/302)
- [x] Error pages (400, 403, 404, 405, 413, 500)
- [x] Sessions and cookies
- [x] Keep-alive connections
- [x] Chunked request encoding
- [x] CGI execution
- [x] CGI timeout (5 seconds)
- [x] Request timeout (10 seconds)
- [x] Body size limits
- [x] Virtual hosts (Host header routing)
- [x] Multiple ports
- [x] Path traversal prevention
- [x] HTTP pipelining
- [x] Non-blocking I/O (NIO)
- [x] Single-threaded event loop
- [x] Large file upload streaming
- [x] CGI output size limit

### Needs Verification ⚠️
- [ ] Concurrent request handling (stress test)
- [ ] Memory leak prevention (long-running test)
- [ ] File upload integrity
- [ ] Custom error pages working
- [ ] Large static file serving (memory usage)

### Not Implemented (Not Required) ℹ️
- [ ] HEAD method
- [ ] OPTIONS method
- [ ] Range requests
- [ ] Conditional requests (ETag, If-Modified-Since)
- [ ] Chunked response encoding
- [ ] Query string parameter parsing (stored as string)

---

## Action Items

### High Priority
1. Run full stress test with siege (30 seconds, 50 concurrent)
2. Verify custom error pages work with config
3. Test large static file serving (check memory)
4. Test file upload/download integrity
5. Test all configuration scenarios

### Medium Priority
1. Verify virtual host resolution works correctly
2. Test CGI with various input sizes
3. Test timeout mechanisms (request and CGI)
4. Test error handling for all error codes

### Low Priority
1. Add query string parameter parsing utility (optional)
2. Optimize large static file serving (optional)
3. Add more comprehensive logging (optional)

---

## Testing Commands Summary

```bash
# Unit tests
make test

# Integration tests (server must be running)
./run_integration_tests.sh

# Manual tests (server must be running)
chmod +x test/manual_tests.sh
./test/manual_tests.sh

# Stress test
siege -b -c 50 -t 30S http://127.0.0.1:8080/

# Check connections
netstat -an | grep 8080 | grep ESTABLISHED | wc -l

# Memory monitoring
watch -n 1 'ps aux | grep java | grep -v grep'

# Test CGI timeout
curl http://localhost:8080/cgi-3  # Large output CGI

# Test request timeout
(echo -n "GET / HTTP/1.1\r\n"; sleep 15) | nc localhost 8080

# Test virtual hosts
curl -H "Host: localhost" http://127.0.0.1:8080/
curl -H "Host: test.local" http://127.0.0.1:8080/

# Test body limit
dd if=/dev/zero bs=1M count=200 | curl -X POST --data-binary @- http://localhost:8080/upload/large.bin

# Test chunked encoding
curl -X POST -H "Transfer-Encoding: chunked" --data "test" http://localhost:8080/upload/test.txt

# Test file integrity
dd if=/dev/urandom of=/tmp/test.bin bs=1K count=100
md5sum /tmp/test.bin
curl -X POST --data-binary @/tmp/test.bin http://localhost:8080/upload/test.bin
curl http://localhost:8080/upload/test.bin -o /tmp/downloaded.bin
md5sum /tmp/downloaded.bin
# MD5 sums should match
```
