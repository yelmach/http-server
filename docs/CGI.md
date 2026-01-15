# CGI Execution Feature

## Overview

Executes external scripts (Python, Perl, etc.) and streams their output back to clients. Designed for **non-blocking operation** - the main event loop never blocks waiting for scripts to finish.

## Architecture

```
Client Request → CGIHandler.handle() → Start Process → Return to Event Loop
                                              ↓
Event Loop → checkCgiProcess() → Read Output → Complete Response
```

**Key Design:** CGI processes run asynchronously while the server continues handling other clients.

## CGI Handler

### Script Detection

Router automatically selects CGI handler when:
- Route has `cgiExtension` configured (e.g., `".py"`)
- Requested file matches extension

```json
{
  "path": "/cgi-bin",
  "root": "./scripts",
  "methods": ["GET", "POST"],
  "cgiExtension": "py"
}
```

### Environment Setup

CGI scripts receive standard CGI environment variables:

```java
Map<String, String> env = pb.environment();
env.put("SERVER_PROTOCOL", "HTTP/1.1");
env.put("REQUEST_METHOD", "GET");
env.put("QUERY_STRING", "name=test&id=123");
env.put("PATH_INFO", "/full/path/to/script.py");
env.put("CONTENT_TYPE", "application/json");
env.put("CONTENT_LENGTH", "42");
```

### Process Execution

```java
ProcessBuilder pb = new ProcessBuilder("python3", resource.getAbsolutePath());
pb.directory(resource.getParentFile());

Process process = pb.start();

// Write request body to script's stdin
try (OutputStream os = process.getOutputStream()) {
    if (request.getBody() != null) {
        os.write(request.getBody());
    } else if (request.getBodyTempFile() != null) {
        // Stream large body from disk
        try (FileInputStream fis = new FileInputStream(request.getBodyTempFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
        }
    }
}

// Don't wait for process - return control to event loop
response.setPendingProcess(process);
```

### Non-Blocking Execution

**Handler returns immediately** after starting the process. The `ClientHandler` stores the process and checks it periodically.

## Client Handler Integration

### Process Polling

Called every 50ms from server event loop:

```java
public void checkCgiProcess() {
    if (!isCgiRunning) return;
    
    // 1. Read available output (non-blocking)
    InputStream is = cgiProcess.getInputStream();
    while (is.available() > 0) {
        byte[] chunk = new byte[8192];
        int read = is.read(chunk);
        if (read > 0) {
            cgiOutputBuffer.write(chunk, 0, read);
            cgiOutputSize += read;
        }
    }
    
    // 2. Check timeout (5 seconds)
    if (System.currentTimeMillis() - cgiStartTime > 5000) {
        cgiProcess.destroyForcibly();
        sendError(HttpStatusCode.REQUEST_TIMEOUT);
        return;
    }
    
    // 3. Check if process finished
    if (!cgiProcess.isAlive()) {
        // Read remaining output, finalize response
        byte[] fullOutput = cgiOutputBuffer.toByteArray();
        response.body(fullOutput).status(HttpStatusCode.OK);
        finishRequest(response, request);
    }
}
```

### State Management

```java
private Process cgiProcess;
private boolean isCgiRunning = false;
private long cgiStartTime;
private ResponseBuilder pendingResponseBuilder;
private ByteArrayOutputStream cgiOutputBuffer;
private int cgiOutputSize = 0;
```

### Interest Operations

While CGI is running, socket interest ops are cleared:
```java
selectionKey.interestOps(0); // Don't read/write until CGI completes
```

This prevents timeouts and allows the connection to wait for the script.

## Security & Limits

### Output Size Limit

```java
private static final int MAX_CGI_OUTPUT = 10 * 1024 * 1024; // 10MB

if (cgiOutputSize + read > MAX_CGI_OUTPUT) {
    cgiProcess.destroyForcibly();
    response.status(HttpStatusCode.PAYLOAD_TOO_LARGE);
    return;
}
```

Prevents memory exhaustion from runaway scripts.

### Timeout Protection

Scripts must complete within **5 seconds** or are forcibly killed:

```java
if (System.currentTimeMillis() - cgiStartTime > 5000) {
    cgiProcess.destroyForcibly();
    response.status(HttpStatusCode.REQUEST_TIMEOUT);
}
```

### File Permissions

```java
if (!resource.canExecute()) {
    response.status(HttpStatusCode.FORBIDDEN)
            .body("CGI script not executable");
    return;
}
```

Scripts must have execute permission (`chmod +x script.py`).

## Example CGI Scripts

### Simple HTML Output

```python
#!/usr/bin/env python3
print("""
<!DOCTYPE html>
<html>
<body>
    <h1>Hello from CGI!</h1>
</body>
</html>
""")
```

### Environment Variables

```python
#!/usr/bin/env python3
import os
print("<html><body>")
print(f"<p>Method: {os.environ.get('REQUEST_METHOD')}</p>")
print(f"<p>Query: {os.environ.get('QUERY_STRING')}</p>")
print("</body></html>")
```

### Reading POST Data

```python
#!/usr/bin/env python3
import sys
data = sys.stdin.read()
print(f"<html><body><p>Received: {len(data)} bytes</p></body></html>")
```

## Usage Examples

### GET Request

```bash
curl "http://localhost:8080/cgi-bin/env.py?name=test&id=123"
```

### POST Request

```bash
curl -X POST -d "Hello CGI" "http://localhost:8080/cgi-bin/echo.py"
```

### Large Output

```python
# Generate 5MB of output
for i in range(1000):
    print(f"<p>Line {i}: Large response test</p>")
```

## Error Scenarios

| Error | Status Code | Cause |
|-------|-------------|-------|
| Script not found | 404 | File doesn't exist |
| Not executable | 403 | Missing execute permission |
| Timeout | 408 | Script runs > 5 seconds |
| Output too large | 413 | Output exceeds 10MB |
| Launch failed | 500 | ProcessBuilder error |

## Performance Notes

**Memory:**
- Output buffered in RAM (max 10MB)
- Large request bodies stream from disk to script's stdin

**Concurrency:**
- Multiple CGI processes can run simultaneously
- Each connection has independent process/buffer
- Server remains responsive during script execution

**Limitations:**
- No support for FastCGI or SCGI protocols
- No connection pooling (process per request)
- Limited to local scripts (no remote execution)

## Configuration

```json
{
  "path": "/cgi-bin",
  "root": "./scripts",
  "methods": ["GET", "POST"],
  "cgiExtension": "py"
}
```

**Script location:** `./scripts/script.py`  
**URL:** `http://localhost:8080/cgi-bin/script.py`

## Testing

```bash
# Make script executable
chmod +x scripts/test.py

# Test GET
curl -v "http://localhost:8080/cgi-bin/test.py?param=value"

# Test POST with body
echo "test data" | curl -X POST --data-binary @- "http://localhost:8080/cgi-bin/echo.py"

# Test timeout
curl "http://localhost:8080/cgi-bin/sleep.py" # Should timeout after 5s
```

## Integration Flow

1. **Router** detects CGI extension → creates `CGIHandler`
2. **CGIHandler** starts process → returns to `ClientHandler`
3. **ClientHandler** stores process, clears interest ops
4. **Server loop** calls `checkCgiProcess()` every 50ms
5. **Process completes** → output sent to client
6. **Connection** resumes normal operation (keep-alive or close)