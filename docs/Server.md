# Server Core & Event Loop

## Overview

Single-threaded, non-blocking HTTP server using Java NIO. Handles multiple concurrent connections on multiple ports using a **selector-based event loop**.

```
Selector.select() → Accept/Read/Write events → Handle → Repeat
```

**Key characteristics:**
- Non-blocking I/O (no thread per connection)
- Event-driven architecture
- Zero-copy file transfers
- Connection timeouts and CGI process monitoring

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                        Server                             │
│  ┌────────────┐                                          │
│  │  Selector  │  Monitors all channels                   │
│  └─────┬──────┘                                          │
│        │                                                  │
│   ┌────┴─────────────────────────────────┐              │
│   │                                       │              │
│   ▼                                       ▼              │
│ ServerSocketChannel (8080)   ServerSocketChannel (8081) │
│   │                                       │              │
│   └──┬──┬──┬───...                       └──┬──┬───...  │
│      │  │  │                                 │  │        │
│      ▼  ▼  ▼                                 ▼  ▼        │
│   SocketChannel (clients)                SocketChannel   │
│      │                                       │           │
│      ▼                                       ▼           │
│   ClientHandler                          ClientHandler   │
└──────────────────────────────────────────────────────────┘
```

## Server Class

### Initialization

```java
public class Server {
    private Selector selector;
    
    public void start(List<ServerConfig> configs) throws IOException {
        selector = Selector.open();
        
        // Group configs by IP:port
        Map<String, List<ServerConfig>> socketBindings = new HashMap<>();
        for (ServerConfig config : configs) {
            String host = config.getHost();
            for (int port : config.getPorts()) {
                String bindKey = host + ":" + port;
                socketBindings.computeIfAbsent(bindKey, k -> new ArrayList<>())
                             .add(config);
            }
        }
        
        // Bind server sockets
        for (Map.Entry<String, List<ServerConfig>> entry : socketBindings.entrySet()) {
            String[] parts = entry.getKey().split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            List<ServerConfig> virtualHosts = entry.getValue();
            
            ServerSocketChannel serverSocket = ServerSocketChannel.open();
            serverSocket.configureBlocking(false);
            serverSocket.bind(new InetSocketAddress(host, port));
            serverSocket.register(selector, SelectionKey.OP_ACCEPT, virtualHosts);
            
            logger.info("Server bound to " + host + ":" + port);
        }
        
        // Enter event loop
        runEventLoop();
    }
}
```

**Virtual host support:**
- Multiple `ServerConfig` objects can bind to same IP:port
- Stored as attachment on `SelectionKey`
- Resolved later by `Host` header in request

### Event Loop

```java
private void runEventLoop() {
    while (true) {
        selector.select(50);  // 50ms timeout
        
        // Check timeouts and pending CGI processes
        checkTimeouts();
        checkPendingCGI();
        
        // Process I/O events
        Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
        while (iterator.hasNext()) {
            SelectionKey key = iterator.next();
            iterator.remove();
            
            if (!key.isValid()) continue;
            
            try {
                if (key.isAcceptable()) {
                    handleAccept(key);
                } else if (key.isReadable()) {
                    handleRead(key);
                } else if (key.isWritable()) {
                    handleWrite(key);
                }
            } catch (IOException e) {
                logger.severe("Client error: " + e.getMessage());
                closeConnection(key);
            }
        }
    }
}
```

**Event types:**
- `OP_ACCEPT` - New connection ready to accept
- `OP_READ` - Data available to read from socket
- `OP_WRITE` - Socket ready to write (buffer space available)

### Accept New Connections

```java
private void handleAccept(SelectionKey key) throws IOException {
    List<ServerConfig> virtualHosts = (List<ServerConfig>) key.attachment();
    
    ServerSocketChannel server = (ServerSocketChannel) key.channel();
    SocketChannel client = server.accept();
    client.configureBlocking(false);
    
    SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ);
    ClientHandler handler = new ClientHandler(client, clientKey, virtualHosts);
    clientKey.attach(handler);
    
    logger.info("New connection: " + client.getRemoteAddress());
}
```

**Key points:**
- Set client socket to non-blocking mode
- Register for `OP_READ` (ready to receive request)
- Attach `ClientHandler` to selection key
- Virtual host configs passed to handler

### Read from Socket

```java
private void handleRead(SelectionKey key) throws IOException {
    ClientHandler handler = (ClientHandler) key.attachment();
    handler.read();
}
```

Delegates to `ClientHandler` (covered in next section).

### Write to Socket

```java
private void handleWrite(SelectionKey key) throws IOException {
    ClientHandler handler = (ClientHandler) key.attachment();
    handler.write();
}
```

Delegates to `ClientHandler`.

### Timeout Checking

```java
private void checkTimeouts() {
    for (SelectionKey key : selector.keys()) {
        if (key.isValid() && key.attachment() instanceof ClientHandler) {
            ClientHandler handler = (ClientHandler) key.attachment();
            
            if (handler.isTimedOut()) {
                logger.info("Connection timed out: " + handler.getIpAddress());
                try {
                    key.cancel();
                    key.channel().close();
                } catch (IOException e) {
                    logger.severe("Error closing timed-out connection");
                }
            }
        }
    }
}
```

**Timeout policy:**
- 10 seconds of inactivity → close connection
- Prevents resource leaks from stalled clients
- Checked every 50ms (selector timeout)

### CGI Process Monitoring

```java
private void checkPendingCGI() {
    for (SelectionKey key : selector.keys()) {
        if (key.isValid() && key.attachment() instanceof ClientHandler) {
            ClientHandler handler = (ClientHandler) key.attachment();
            handler.checkCgiProcess();
        }
    }
}
```

Polls CGI processes to check if they've finished (non-blocking).

## ClientHandler Class

### State Management

```java
public class ClientHandler {
    private final SocketChannel client;
    private final SelectionKey selectionKey;
    private final ByteBuffer readBuffer;              // 8KB
    private final RequestParser parser;
    private final Queue<ByteBuffer> responseQueue;
    private boolean keepAlive;
    private long lastActivityTime;
    private final long timeoutMs = 10000;
    
    // CGI state
    private Process cgiProcess;
    private boolean isCgiRunning = false;
    private ByteArrayOutputStream cgiOutputBuffer;
    
    // File streaming state
    private FileChannel fileChannel;
    private long filePosition;
    private long fileSize;
}
```

### Reading Data

```java
public void read() throws IOException {
    lastActivityTime = System.currentTimeMillis();
    readBuffer.clear();
    int bytesRead = client.read(readBuffer);
    
    // Client disconnected
    if (bytesRead == -1) {
        close();
        return;
    }
    
    if (bytesRead > 0) {
        readBuffer.flip();
        
        // Parse incoming data
        ParsingResult result = parser.parse(readBuffer);
        
        // Handle completed requests
        while (result.isComplete()) {
            HttpRequest request = result.getRequest();
            handleRequest(request);
            
            parser.resetState();
            result = parser.parse(ByteBuffer.allocate(0));
        }
        
        // Handle parsing errors
        if (result.isError()) {
            sendError(HttpStatusCode.BAD_REQUEST);
            return;
        }
        
        // Switch to write mode if response ready
        if (!responseQueue.isEmpty() && !isCgiRunning) {
            selectionKey.interestOps(SelectionKey.OP_WRITE);
        }
    }
}
```

**Key features:**
- Updates activity timestamp (for timeout detection)
- Incremental parsing (handles partial data)
- Multiple requests on same connection (HTTP/1.1 keep-alive)
- Error handling with appropriate status codes

### Request Handling

```java
private void handleRequest(HttpRequest request) {
    // Resolve virtual host
    String hostHeader = request.getHeaders().get("Host");
    ServerConfig config = resolveConfig(hostHeader);
    
    // Route to handler
    Router router = new Router();
    Handler handler = router.route(request, config);
    ResponseBuilder responseBuilder = new ResponseBuilder();
    
    // Execute handler
    try {
        handler.handle(request, responseBuilder);
    } catch (Exception e) {
        logger.severe("Handler Error: " + e.getMessage());
        responseBuilder.status(HttpStatusCode.INTERNAL_SERVER_ERROR);
    }
    
    // Handle CGI process (non-blocking)
    if (responseBuilder.hasPendingProcess()) {
        this.cgiProcess = responseBuilder.getPendingProcess();
        this.isCgiRunning = true;
        this.cgiStartTime = System.currentTimeMillis();
        this.cgiOutputBuffer = new ByteArrayOutputStream();
        selectionKey.interestOps(0);  // Pause I/O while waiting
        return;
    }
    
    // Handle static file streaming
    if (responseBuilder.hasFile()) {
        File file = responseBuilder.getBodyFile();
        this.fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
        this.fileSize = file.length();
        this.filePosition = 0;
    }
    
    finishRequest(responseBuilder, request);
}
```

### Writing Responses

```java
public void write() throws IOException {
    lastActivityTime = System.currentTimeMillis();
    
    // 1. Write headers/small bodies from queue
    ByteBuffer responseBuffer = responseQueue.peek();
    if (responseBuffer != null) {
        client.write(responseBuffer);
        if (!responseBuffer.hasRemaining()) {
            responseQueue.poll();
        }
        return;  // Fairness: give other clients a turn
    }
    
    // 2. Stream large files (zero-copy)
    if (fileChannel != null && fileChannel.isOpen()) {
        long count = 8192 * 4;  // 32KB per iteration
        long transferred = fileChannel.transferTo(filePosition, count, client);
        
        if (transferred > 0) {
            filePosition += transferred;
        }
        
        // File transfer complete
        if (filePosition >= fileSize) {
            fileChannel.close();
            fileChannel = null;
            
            if (!keepAlive) {
                close();
            } else {
                selectionKey.interestOps(SelectionKey.OP_READ);
            }
        }
        return;
    }
    
    // 3. Nothing to write
    if (responseQueue.isEmpty()) {
        if (keepAlive) {
            selectionKey.interestOps(SelectionKey.OP_READ);
        } else {
            close();
        }
    }
}
```

**Zero-copy file transfer:**
- Uses `FileChannel.transferTo()` (OS-level sendfile)
- No data copied to JVM heap
- 32KB chunks per event loop iteration
- Prevents blocking on large files

### CGI Process Checking

```java
public void checkCgiProcess() {
    if (!isCgiRunning) return;
    
    // 1. Read available output (non-blocking)
    try {
        InputStream is = cgiProcess.getInputStream();
        while (is.available() > 0) {
            byte[] chunk = new byte[8192];
            int read = is.read(chunk);
            if (read > 0) {
                if (cgiOutputSize + read > MAX_CGI_OUTPUT) {
                    cgiProcess.destroyForcibly();
                    sendError(HttpStatusCode.PAYLOAD_TOO_LARGE);
                    return;
                }
                cgiOutputBuffer.write(chunk, 0, read);
                cgiOutputSize += read;
            }
        }
    } catch (IOException e) {
        cgiProcess.destroyForcibly();
        sendError(HttpStatusCode.INTERNAL_SERVER_ERROR);
        return;
    }
    
    // 2. Check timeout (5 seconds)
    if (System.currentTimeMillis() - cgiStartTime > 5000) {
        cgiProcess.destroyForcibly();
        sendError(HttpStatusCode.REQUEST_TIMEOUT);
        return;
    }
    
    // 3. Check if process finished
    if (!cgiProcess.isAlive()) {
        isCgiRunning = false;
        
        // Read remaining output
        try {
            InputStream is = cgiProcess.getInputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = is.read(chunk)) != -1) {
                cgiOutputBuffer.write(chunk, 0, read);
            }
        } catch (IOException ignored) {}
        
        // Send response
        byte[] output = cgiOutputBuffer.toByteArray();
        ResponseBuilder response = pendingResponseBuilder;
        response.body(output).status(HttpStatusCode.OK);
        finishRequest(response, pendingRequest);
    }
}
```

### Timeout Detection

```java
public boolean isTimedOut() {
    long currentTime = System.currentTimeMillis();
    return (currentTime - lastActivityTime) > timeoutMs;
}
```

Updated on every `read()` and `write()` call.

### Connection Cleanup

```java
void close() throws IOException {
    // Kill CGI process
    if (cgiProcess != null && cgiProcess.isAlive()) {
        cgiProcess.destroyForcibly();
    }
    
    // Close file channel
    if (fileChannel != null) {
        fileChannel.close();
    }
    
    // Cleanup parser temp files
    if (parser != null) {
        parser.cleanup();
    }
    
    selectionKey.cancel();
    client.close();
}
```

## Keep-Alive Connections

HTTP/1.1 connections are persistent by default:

```java
boolean keepAlive = request.shouldKeepAlive();

// In write():
if (responseComplete) {
    if (keepAlive) {
        selectionKey.interestOps(SelectionKey.OP_READ);  // Ready for next request
    } else {
        close();
    }
}
```

**Benefits:**
- Reduces TCP handshake overhead
- Enables request pipelining
- Better performance for multiple requests

**Control:**
- HTTP/1.1: Keep-alive by default, unless `Connection: close`
- HTTP/1.0: Close by default, unless `Connection: keep-alive`

## Interest Operations

Selection key interests control when selector notifies:

```java
// Ready to read
selectionKey.interestOps(SelectionKey.OP_READ);

// Ready to write
selectionKey.interestOps(SelectionKey.OP_WRITE);

// Waiting for CGI (pause I/O)
selectionKey.interestOps(0);

// Both read and write
selectionKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
```

**State transitions:**
```
Accept → OP_READ → Parse request → OP_WRITE → Send response → OP_READ (keep-alive)
                                  ↓
                        OP_NONE (waiting for CGI)
                                  ↓
                              OP_WRITE
```

## Performance Characteristics

### Scalability

**Single thread handles:**
- 1000+ concurrent connections
- Multiple ports simultaneously
- File uploads/downloads
- CGI process execution

**Limitations:**
- CPU-bound tasks block event loop
- Long CGI scripts stall their connection (but not others)
- No work stealing between cores

### Memory Usage

**Per connection:**
- ClientHandler: ~200 bytes
- Read buffer: 8KB
- Parser accumulation buffer: varies (cleared frequently)
- Response queue: minimal (streaming mode)

**Total for 1000 connections:**
- ~8.2 MB minimum
- Scales linearly with concurrent uploads

### Fairness

**Round-robin handling:**
- Each event processed once per loop iteration
- No connection monopolizes CPU
- File transfers limited to 32KB per iteration
- CGI processes checked but don't block

## Error Recovery

### Connection Errors

```java
try {
    if (key.isReadable()) {
        handleRead(key);
    }
} catch (IOException e) {
    logger.severe("Client error: " + e.getMessage());
    
    // Cleanup resources
    ClientHandler handler = (ClientHandler) key.attachment();
    handler.close();
    
    // Remove from selector
    key.cancel();
    key.channel().close();
}
```

**Errors handled gracefully:**
- Client disconnects unexpectedly
- Socket write failures
- Network errors

### Parsing Errors

```java
if (result.isError()) {
    logger.severe("Parsing error: " + result.getErrorMessage());
    
    // Determine appropriate status code
    HttpStatusCode code = determineErrorCode(result.getErrorMessage());
    
    // Send error response
    ResponseBuilder errorResponse = new ResponseBuilder();
    errorResponse.status(code).header("Connection", "close");
    finishRequest(errorResponse, null);
}
```

### Handler Errors

```java
try {
    handler.handle(request, responseBuilder);
} catch (Exception e) {
    logger.severe("Handler Error: " + e.getMessage());
    responseBuilder.status(HttpStatusCode.INTERNAL_SERVER_ERROR);
}

// Always check for error status
if (responseBuilder.getStatusCode().isError()) {
    ErrorHandler errorHandler = new ErrorHandler(statusCode, config);
    errorHandler.handle(request, responseBuilder);
}
```

## Configuration Example

```json
{
  "servers": [
    {
      "host": "127.0.0.1",
      "ports": [8080, 8443],
      "serverName": "localhost",
      "defaultServer": true,
      "maxBodySize": 104857600,
      "routes": [...]
    },
    {
      "host": "127.0.0.2",
      "ports": [8080],
      "serverName": "site2.local",
      "maxBodySize": 10485760,
      "routes": [...]
    }
  ]
}
```

**Result:**
- 3 server sockets: 127.0.0.1:8080, 127.0.0.1:8443, 127.0.0.2:8080
- Virtual hosts on 127.0.0.1:8080
- All managed by single selector

## Testing

```bash
# Test concurrent connections
siege -c 100 -t 30S http://localhost:8080/

# Test keep-alive
curl -v -H "Connection: keep-alive" http://localhost:8080/

# Test timeout
telnet localhost 8080
# Wait 11 seconds without sending data → connection closed

# Test multiple ports
curl http://localhost:8080/
curl http://localhost:8443/
```

## Summary

**Server responsibilities:**
1. Bind server sockets to configured ports
2. Run event loop with selector
3. Accept new connections
4. Monitor timeouts and CGI processes
5. Delegate I/O to ClientHandler

**ClientHandler responsibilities:**
1. Parse HTTP requests incrementally
2. Route and execute handlers
3. Build and send responses
4. Stream large files (zero-copy)
5. Monitor CGI processes
6. Manage connection lifecycle

**Key design principles:**
- Non-blocking I/O (never blocks event loop)
- Fair scheduling (round-robin)
- Resource cleanup (timeouts, error handling)
- Zero-copy file transfers
- Keep-alive connection reuse