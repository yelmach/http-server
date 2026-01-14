# Comprehensive Test Guide for HTTP Server

This guide covers all testing requirements from the audit checklist. Use this to validate your HTTP server implementation.

## Table of Contents

1. [Quick Start](#quick-start)
2. [Unit Tests](#unit-tests)
3. [Integration Tests](#integration-tests)
4. [Configuration Tests](#configuration-tests)
5. [Manual Tests](#manual-tests)
6. [Stress Tests](#stress-tests)
7. [Architecture Questions](#architecture-questions)

---

## Quick Start

### Prerequisites
- Java 11 or higher
- `curl` command-line tool
- `siege` (for stress testing): `sudo apt install siege` or `brew install siege`
- `nc` (netcat) for low-level socket tests

### Running All Tests

```bash
# 1. Build the project
make build

# 2. Run unit tests
make test

# 3. Start the server (in one terminal)
make run

# 4. Run integration tests (in another terminal)
./run_integration_tests.sh

# 5. Run manual tests
chmod +x test/manual_tests.sh
./test/manual_tests.sh

# 6. Run stress tests
siege -b -c 50 -t 30S http://127.0.0.1:8080/
```

---

## Unit Tests

### Request Parser Tests (103 tests)

**Location:** `test/http/RequestParserTest.java`

**What it tests:**
- HTTP request line parsing (methods, URIs, versions)
- Header parsing (single, multiple, multi-line, case-insensitive)
- Body parsing (fixed-length, chunked encoding)
- Incremental parsing (fragmented requests)
- Error handling (invalid methods, malformed requests, size limits)
- Keep-alive detection
- Path traversal prevention

**Run with:**
```bash
make test
```

**Expected output:**
```
✓ All 103 tests passed!
```

---

## Integration Tests

### Automated Integration Test Suite

**Location:** `test/integration/IntegrationTestSuite.java`

**Tests covered:**
1. Server availability
2. Static file serving (GET)
3. 404 error handling
4. Directory listing
5. Directory with index file
6. File uploads (POST)
7. File deletion (DELETE)
8. Method restrictions (405)
9. HTTP redirects (301/302)
10. Body size limits (413)
11. Chunked transfer encoding
12. CGI script execution
13. Sessions and cookies
14. Keep-alive connections
15. Concurrent requests
16. Malformed request handling
17. Custom error pages
18. Virtual host resolution

**Run with:**
```bash
# 1. Build and start server
make run

# 2. In another terminal
javac -cp build -d build test/integration/IntegrationTestSuite.java
java -cp build integration.IntegrationTestSuite
```

---

## Configuration Tests

### Test 1: Body Size Limit

**Config:** `test/configs/test_body_limit.json`

**What it tests:**
- Server rejects bodies larger than configured limit (1KB in this config)
- Returns 413 Payload Too Large

**Test commands:**
```bash
# Start server with test config
java -cp build Main test/configs/test_body_limit.json

# Should succeed (small body)
curl -X POST -d "small" http://localhost:8080/upload/test.txt

# Should fail with 413 (large body)
dd if=/dev/zero bs=1K count=2 | curl -X POST --data-binary @- http://localhost:8080/upload/large.txt
```

### Test 2: Duplicate Port Detection

**Config:** `test/configs/test_duplicate_port.json`

**What it tests:**
- Server detects and rejects duplicate port configuration

**Expected behavior:**
```bash
java -cp build Main test/configs/test_duplicate_port.json
# Should output error: "Duplicate port: 8080"
```

### Test 3: Method Restrictions

**Config:** `test/configs/test_method_restrictions.json`

**What it tests:**
- Routes enforce allowed HTTP methods
- Returns 405 Method Not Allowed for disallowed methods

**Test commands:**
```bash
java -cp build Main test/configs/test_method_restrictions.json

# Should succeed
curl http://localhost:8080/read-only/

# Should fail with 405
curl -X POST http://localhost:8080/read-only/

# Should succeed
curl -X POST -d "data" http://localhost:8080/upload-only/file.txt

# Should fail with 405
curl http://localhost:8080/upload-only/
```

### Test 4: Multiple Virtual Hosts

**Config:** `configs/config_multiple_hosts.json`

**What it tests:**
- Server handles multiple virtual hosts on same port
- Host header routing works correctly

**Test commands:**
```bash
java -cp build Main configs/config_multiple_hosts.json

# Test primary host
curl -H "Host: primary.local" http://127.0.0.1:8080/

# Test secondary host
curl -H "Host: secondary.local" http://127.0.0.1:8080/

# Test different IP
curl http://127.0.0.2:8080/

# Test different port
curl http://127.0.0.1:9090/
```

### Test 5: Multiple Ports

**What it tests:**
- Server listens on multiple ports simultaneously

**Test commands:**
```bash
# Edit config.json to have multiple ports: [8080, 8081, 8082]
java -cp build Main

# Test each port
curl http://localhost:8080/
curl http://localhost:8081/
curl http://localhost:8082/
```

---

## Manual Tests

### Comprehensive Manual Test Script

**Location:** `test/manual_tests.sh`

**Run with:**
```bash
chmod +x test/manual_tests.sh
./test/manual_tests.sh
```

**Tests included:**
- Basic GET requests (200 OK)
- 404 Not Found
- Directory listing
- File uploads (POST)
- File deletion (DELETE)
- Method not allowed (405)
- HTTP redirects (301/302)
- Body size limits (413)
- Chunked encoding
- CGI execution
- Sessions and cookies
- Keep-alive
- Concurrent requests
- Malformed requests
- Virtual hosts
- Custom error pages
- Request/response headers
- File integrity (upload/download)

### Individual Manual Tests

#### Test: GET Static File
```bash
curl -v http://localhost:8080/index.html
```
**Expected:**
- Status: 200 OK
- Content-Type: text/html
- Body: HTML content

#### Test: 404 Not Found
```bash
curl -v http://localhost:8080/nonexistent.html
```
**Expected:**
- Status: 404 Not Found
- Body: Custom error page (HTML)

#### Test: Directory Listing
```bash
curl -v http://localhost:8080/images/
```
**Expected:**
- Status: 200 OK (if enabled) or 403 Forbidden (if disabled)
- Body: HTML listing of files (if enabled)

#### Test: POST File Upload
```bash
echo "Test content" > test.txt
curl -v -X POST --data-binary @test.txt http://localhost:8080/upload/test.txt
```
**Expected:**
- Status: 201 Created or 200 OK
- File saved to www/uploads/test.txt

#### Test: Multipart Form Upload
```bash
curl -v -X POST -F "file=@test.txt" http://localhost:8080/upload/
```
**Expected:**
- Status: 201 Created
- File saved with original name

#### Test: DELETE File
```bash
curl -v -X DELETE http://localhost:8080/upload/test.txt
```
**Expected:**
- Status: 204 No Content (if successful)
- Status: 404 Not Found (if file doesn't exist)
- Status: 403 Forbidden (if not allowed)

#### Test: Method Not Allowed
```bash
curl -v -X POST http://localhost:8080/images/
```
**Expected:**
- Status: 405 Method Not Allowed

#### Test: Redirect
```bash
curl -v http://localhost:8080/old-page
```
**Expected:**
- Status: 301 Moved Permanently
- Location header: /

#### Test: Body Size Limit
```bash
dd if=/dev/zero bs=1M count=200 | curl -X POST --data-binary @- http://localhost:8080/upload/large.bin
```
**Expected:**
- Status: 413 Payload Too Large
- Or connection closed by server

#### Test: Chunked Encoding
```bash
curl -v -X POST -H "Transfer-Encoding: chunked" --data "test data" http://localhost:8080/upload/chunked.txt
```
**Expected:**
- Status: 201 Created or 200 OK
- Server correctly handles chunked encoding

#### Test: CGI Script
```bash
curl -v http://localhost:8080/cgi-1
```
**Expected:**
- Status: 200 OK
- Content-Type: text/html (or as set by CGI)
- Body: Output from Python script

#### Test: CGI with POST Data
```bash
curl -v -X POST -d "name=test&value=123" http://localhost:8080/cgi-3
```
**Expected:**
- Status: 200 OK
- CGI script receives POST data via stdin

#### Test: Sessions and Cookies
```bash
# First request (creates session)
curl -v -c cookies.txt http://localhost:8080/session

# Second request (uses existing session)
curl -v -b cookies.txt http://localhost:8080/session
```
**Expected:**
- Set-Cookie header in first response
- Session persists (view count increments)

#### Test: Keep-Alive
```bash
curl -v -H "Connection: keep-alive" http://localhost:8080/
```
**Expected:**
- Connection: keep-alive header in response
- Socket remains open

#### Test: Malformed Request
```bash
(echo -e "INVALID\r\n\r\n"; sleep 1) | nc localhost 8080
```
**Expected:**
- Server closes connection or returns 400 Bad Request
- Server doesn't crash

#### Test: Path Traversal Prevention
```bash
curl -v http://localhost:8080/../../../etc/passwd
```
**Expected:**
- Status: 400 Bad Request or 403 Forbidden
- Path traversal blocked

---

## Stress Tests

### Siege Stress Test

**Goal:** Achieve 99.5% availability

**Basic test:**
```bash
siege -b -c 50 -t 30S http://127.0.0.1:8080/
```

**Parameters:**
- `-b`: Benchmark mode (no delays)
- `-c 50`: 50 concurrent users
- `-t 30S`: Test duration 30 seconds

**Expected output:**
```
Transactions:               XXXX hits
Availability:              99.50 %  <-- Must be >= 99.5%
Elapsed time:              30.00 secs
Response time:             X.XX secs
Transaction rate:          XX.XX trans/sec
Successful transactions:   XXXX
Failed transactions:       X
```

### Different Stress Scenarios

#### Test 1: High Concurrency
```bash
siege -b -c 100 -t 60S http://localhost:8080/
```

#### Test 2: Mixed Workload
```bash
# Create urls.txt with different endpoints
cat > urls.txt << EOF
http://localhost:8080/
http://localhost:8080/index.html
http://localhost:8080/about.html
http://localhost:8080/images/
EOF

siege -b -c 50 -t 30S -f urls.txt
```

#### Test 3: POST Requests
```bash
siege -b -c 20 -t 30S "http://localhost:8080/upload/test.txt POST data=test"
```

#### Test 4: CGI Stress Test
```bash
siege -b -c 30 -t 30S http://localhost:8080/cgi-1
```

### Check for Hanging Connections

```bash
# Before test
netstat -an | grep 8080 | wc -l

# Run stress test
siege -b -c 100 -t 10S http://localhost:8080/

# After test (wait 15 seconds)
sleep 15
netstat -an | grep 8080 | wc -l
```

**Expected:**
- Connection count should return to baseline (or close to it)
- No TIME_WAIT connections accumulating

### Memory Leak Test

```bash
# Monitor memory usage
watch -n 1 'ps aux | grep java'

# Run prolonged stress test
siege -b -c 50 -t 300S http://localhost:8080/

# Memory should remain stable (not continuously growing)
```

---

## Architecture Questions

### Question 1: How does an HTTP server work?

**Expected answer:**
- Listens on TCP socket (port 80/8080/etc.)
- Accepts client connections
- Reads HTTP request from socket
- Parses request (method, path, headers, body)
- Routes to appropriate handler
- Generates HTTP response
- Writes response to socket
- Closes or keeps connection alive

**Show in code:**
- `Server.java`: Event loop and socket management
- `ClientHandler.java`: Request/response handling
- `RequestParser.java`: HTTP protocol parsing
- `Router.java`: Request routing

### Question 2: Which function is used for I/O Multiplexing?

**Expected answer:**
- `Selector.select()` from java.nio
- Allows single thread to monitor multiple channels
- Blocks until one or more channels are ready

**Show in code:**
```java
// Server.java
selector = Selector.open();
selector.select(50); // Wait up to 50ms
Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
```

### Question 3: Is the server using only one select?

**Expected answer:**
- Yes, single Selector instance in Server.java
- All ServerSocketChannel and SocketChannel registered with same Selector

**Show in code:**
```java
// Server.java
private Selector selector;

public void start(List<ServerConfig> configs) {
    selector = Selector.open(); // Single selector
    // All servers register with same selector
}
```

### Question 4: Why is single select important?

**Expected answer:**
- Single thread can handle thousands of connections
- Avoids thread-per-connection overhead
- More scalable and efficient
- Easier to reason about (no race conditions)

### Question 5: Is there only one read/write per select?

**Expected answer:**
- Yes, one read() or write() per client per select iteration
- Non-blocking I/O: read/write returns immediately
- If more data available, will be handled in next iteration

**Show in code:**
```java
// ClientHandler.java
public void read() throws IOException {
    readBuffer.clear();
    int bytesRead = client.read(readBuffer); // Single read
    // Process what was read
}

public void write() throws IOException {
    ByteBuffer responseBuffer = responseQueue.peek();
    if (responseBuffer != null) {
        client.write(responseBuffer); // Single write
    }
}
```

### Question 6: Are I/O return values checked?

**Expected answer:**
- Yes, bytesRead checked for -1 (client disconnected)
- Zero means no data available (non-blocking)
- Errors trigger connection close

**Show in code:**
```java
// ClientHandler.java
int bytesRead = client.read(readBuffer);
if (bytesRead == -1) {
    close(); // Client disconnected
    return;
}
```

### Question 7: Is client removed on error?

**Expected answer:**
- Yes, on parsing errors or I/O exceptions
- Key is cancelled and channel closed

**Show in code:**
```java
// ClientHandler.java
if (result.isError()) {
    logger.severe("Parsing error: " + result.getErrorMessage());
    close(); // Remove client
}

// Server.java (catch block)
} catch (IOException e) {
    logger.severe(e.getMessage());
    key.cancel();
    key.channel().close();
}
```

### Question 8: Is all I/O through select?

**Expected answer:**
- Yes, all client read/write operations go through select
- No blocking I/O calls
- CGI is special case (ProcessBuilder, but async handling)

---

## Missing Features Checklist

Based on the audit, here are potential issues to check:

### Configuration
- [ ] Multiple servers on different ports
- [ ] Virtual host resolution (Host header)
- [ ] Custom error pages working
- [ ] Body size limit enforced
- [ ] Method restrictions per route

### HTTP Methods
- [ ] GET returns correct status codes
- [ ] POST handles uploads properly
- [ ] DELETE works and returns appropriate status
- [ ] 405 Method Not Allowed when method not in route config

### Error Handling
- [ ] 400 Bad Request for malformed requests
- [ ] 403 Forbidden for access violations
- [ ] 404 Not Found
- [ ] 405 Method Not Allowed
- [ ] 413 Payload Too Large
- [ ] 500 Internal Server Error

### Features
- [ ] Directory listing (if enabled in config)
- [ ] Index file serving for directories
- [ ] HTTP redirects (301/302)
- [ ] Sessions and cookies working
- [ ] Keep-alive connections
- [ ] Chunked transfer encoding
- [ ] CGI script execution
- [ ] File upload integrity (no corruption)

### Performance
- [ ] No crashes under load
- [ ] 99.5% availability in siege test
- [ ] No hanging connections
- [ ] No memory leaks
- [ ] Request timeout (10 seconds)

### Security
- [ ] Path traversal prevention
- [ ] Body size limits enforced
- [ ] CGI timeout (5 seconds)
- [ ] Input sanitization

---

## Running the Full Test Suite

### Complete Test Procedure

```bash
# 1. Unit Tests
make test

# 2. Start Server
make run &
SERVER_PID=$!
sleep 2

# 3. Integration Tests
javac -cp build -d build test/integration/IntegrationTestSuite.java
java -cp build integration.IntegrationTestSuite

# 4. Manual Tests
chmod +x test/manual_tests.sh
./test/manual_tests.sh

# 5. Configuration Tests
kill $SERVER_PID

# Test body limit
java -cp build Main test/configs/test_body_limit.json &
sleep 2
dd if=/dev/zero bs=1K count=2 | curl -X POST --data-binary @- http://localhost:8080/upload/large.txt
# Should return 413
kill %1

# Test duplicate port (should fail to start)
java -cp build Main test/configs/test_duplicate_port.json
# Should show error

# Test method restrictions
java -cp build Main test/configs/test_method_restrictions.json &
sleep 2
curl -X POST http://localhost:8080/read-only/
# Should return 405
kill %1

# Test virtual hosts
java -cp build Main configs/config_multiple_hosts.json &
sleep 2
curl -H "Host: primary.local" http://127.0.0.1:8080/
curl -H "Host: secondary.local" http://127.0.0.1:8080/
kill %1

# 6. Stress Tests
make run &
sleep 2
siege -b -c 50 -t 30S http://127.0.0.1:8080/
kill %1

echo "All tests completed!"
```

---

## Troubleshooting

### Server won't start
- Check port is not already in use: `lsof -i :8080`
- Check config file syntax
- Check file permissions

### Tests failing
- Make sure server is running
- Check server logs in `logs/server.log`
- Verify config matches test expectations

### Stress test fails
- Check ulimit: `ulimit -n` (should be > 1024)
- Increase if needed: `ulimit -n 4096`
- Check system resources (CPU, memory)

### CGI not working
- Check Python is installed: `python3 --version`
- Check script has execute permission: `chmod +x www/scripts/*.py`
- Check shebang line: `#!/usr/bin/env python3`

---

## Success Criteria

Your server passes all tests if:

1. All 103 unit tests pass
2. All integration tests pass
3. Manual tests show expected behavior
4. Configuration tests work correctly
5. Siege achieves 99.5% availability
6. No memory leaks or hanging connections
7. Can answer all architecture questions with code references

Good luck!
