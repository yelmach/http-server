# HTTP Request Parser Documentation

The HTTP request parser is the critical component that transforms raw TCP bytes into structured `HttpRequest` objects. It supports **incremental parsing** (handling fragmented requests across multiple socket reads), **HTTP pipelining** (multiple requests in a single buffer), and **keep-alive connections** with timeout management.

## Architecture Overview

The parsing system consists of three main components:

1. **RequestParser** ([RequestParser.java](src/http/RequestParser.java)) - State machine that parses HTTP protocol
2. **ClientHandler** ([ClientHandler.java](src/core/ClientHandler.java)) - Connection manager that orchestrates parsing and pipelining
3. **ParsingResult** ([ParsingResult.java](src/http/ParsingResult.java)) - Result object containing status and parsed request

## The State Machine

`RequestParser` implements a finite state machine with 8 states:

| State | Description |
|-------|-------------|
| `PARSING_REQUEST_LINE` | Parses `METHOD URI HTTP/VERSION` |
| `PARSING_HEADERS` | Parses headers until `\r\n\r\n` is found |
| `PARSING_BODY_FIXED_LENGTH` | Reads exact bytes specified by `Content-Length` |
| `PARSING_CHUNK_SIZE` | Reads hex size of next chunk (chunked encoding) |
| `PARSING_CHUNK_DATA` | Reads chunk data bytes |
| `PARSING_CHUNK_TRAILER` | Handles final `\r\n` after last chunk |
| `COMPLETE` | Request fully parsed, ready for processing |
| `ERROR` | Parsing failed, connection will be closed |

## Data Flow: Socket → Parsed Request

```
CLIENT SOCKET
     ↓
SocketChannel.read()
     ↓
readBuffer (8KB ByteBuffer)
     ↓
parser.parse(readBuffer)
     ↓
AccumulationBuffer (ByteArrayOutputStream)
     ↓
parseAccumulatedData() ← State Machine Processing
     ↓
     ├→ COMPLETE: Request ready, leftover bytes preserved
     ├→ NEED_MORE_DATA: Wait for next socket read
     └→ ERROR: Close connection
```

## The Accumulation Buffer Strategy

The accumulation buffer is the **KEY** to supporting both incremental parsing and pipelining:

```java
public ParsingResult parse(ByteBuffer buffer) {
    // Append new socket data to accumulation buffer
    byte[] newData = new byte[buffer.remaining()];
    buffer.get(newData);  // Consumes the ByteBuffer
    accumulationBuffer.write(newData, 0, newData.length);

    // Process accumulated data through state machine
    return parseAccumulatedData();
}
```

**Critical behaviors:**
- All incoming data is accumulated before processing
- When a request completes mid-buffer, `removeProcessedData()` preserves leftover bytes
- The accumulation buffer persists across `resetState()` calls (used for pipelining)
- Only full `reset()` clears the accumulation buffer

## HTTP Pipelining Implementation

HTTP pipelining allows clients to send multiple requests without waiting for responses (e.g., `GET /first HTTP/1.1\r\n...\r\nGET /second HTTP/1.1\r\n...`).

**How it works in `ClientHandler.read()`:**

```java
public void read() throws IOException {
    readBuffer.clear();
    int bytesRead = client.read(readBuffer);
    readBuffer.flip();

    // Initial parse with fresh socket data
    ParsingResult result = parser.parse(readBuffer);

    // PIPELINING LOOP: Keep parsing until no more complete requests
    while (result.isComplete()) {
        HttpRequest request = result.getRequest();
        handleRequest(request);

        // Reset state but keep accumulation buffer intact
        parser.resetState();

        // Parse again with EMPTY buffer (uses leftover bytes)
        result = parser.parse(ByteBuffer.allocate(0));
    }
}
```

**Example with 2 pipelined requests:**

```
Socket read: "GET /first HTTP/1.1\r\nHost: localhost\r\n\r\nGET /second HTTP/1.1\r\nHost: localhost\r\n\r\n"

Iteration 1:
  → parse(readBuffer) → Accumulates all bytes
  → parseAccumulatedData() → Finds first request complete at position X
  → removeProcessedData(X) → Keeps only "GET /second..." in buffer
  → Returns COMPLETE with first request

Iteration 2:
  → resetState() → Clears state vars, preserves accumulation buffer
  → parse(ByteBuffer.allocate(0)) → No new data, uses remaining bytes
  → parseAccumulatedData() → Finds second request complete
  → Returns COMPLETE with second request

Iteration 3:
  → parse(ByteBuffer.allocate(0)) → Nothing left
  → Returns NEED_MORE_DATA → Exit loop
```

## Incremental Parsing (Fragmented Requests)

Handles requests arriving across multiple socket reads:

**Example: 16KB POST body arriving in 4 reads of 4KB each:**

```
Read 1: Headers + 4KB body
  → State: PARSING_BODY_FIXED_LENGTH
  → bodyBytesRead: 4000 / 16000
  → Returns: NEED_MORE_DATA

Read 2: Next 4KB
  → bodyBytesRead: 8000 / 16000
  → Returns: NEED_MORE_DATA

Read 3: Next 4KB
  → bodyBytesRead: 12000 / 16000
  → Returns: NEED_MORE_DATA

Read 4: Final 4KB
  → bodyBytesRead: 16000 / 16000
  → State: COMPLETE
  → Returns: COMPLETE
```

The accumulation buffer holds partial data across reads, and the state machine tracks progress through `bodyBytesRead`, `currentChunkSize`, etc.

## Two-Level Reset Strategy

The parser has two reset methods with different purposes:

```java
// Used after completing each pipelined request
public void resetState() {
    currentState = State.PARSING_REQUEST_LINE;
    httpRequest = new HttpRequest();
    bodyBytesRead = 0;
    expectedBodyLength = 0;
    // Does NOT clear accumulation buffer!
}

// Used for complete re-initialization
public void reset() {
    resetState();
    accumulationBuffer.reset();  // Clears leftover bytes
}
```

**Why this matters:**
- `resetState()` is called between pipelined requests to clear parsing state while preserving buffered bytes
- If `reset()` was used, the second pipelined request would be lost

## Preserving Leftover Bytes

The `removeProcessedData()` method surgically removes consumed bytes while preserving unparsed data:

```java
private void removeProcessedData(byte[] data, int startPos) {
    accumulationBuffer.reset();  // Clear the buffer

    if (startPos < data.length) {
        // Rewrite only unprocessed bytes (leftover data)
        accumulationBuffer.write(data, startPos, data.length - startPos);
    }
}
```

**Example:**
```
Before: "GET / HTTP/1.1\r\nHost: localhost\r\n\r\nGET /second..."
                                              ^ startPos (first request ends)

After removeProcessedData(data, startPos):
Buffer: "GET /second..."
```

## Keep-Alive Connection Handling

The parser supports HTTP/1.1 persistent connections:

```java
public boolean shouldKeepAlive() {
    String connection = headers.get("Connection");

    if ("HTTP/1.1".equals(httpVersion)) {
        // Keep-alive is default in HTTP/1.1 unless "Connection: close"
        return connection == null || !connection.equals("close");
    } else {
        // HTTP/1.0 requires explicit "Connection: keep-alive"
        return connection != null && connection.equals("keep-alive");
    }
}
```

**Connection timeout:** Connections are closed after 10 seconds of inactivity to prevent resource exhaustion.

## Empty Line Handling (RFC 7230 Robustness)

Per RFC 7230 Section 3.5, servers should be tolerant of leading empty lines:

```java
// Skip up to 10 leading \r\n sequences before request line
while (position + 1 < data.length &&
       data[position] == '\r' &&
       data[position + 1] == '\n') {
    emptyLinesSkipped++;

    if (emptyLinesSkipped > MAX_EMPTY_LINES) {
        return error("Too many empty lines");
    }

    position += 2;
}
```

This allows graceful handling of noise bytes between pipelined requests or keep-alive requests.

## Buffer Size Limits

To prevent resource exhaustion attacks:

```java
MAX_REQUEST_LINE_LENGTH = 8 KB
MAX_HEADER_SIZE = 16 KB
MAX_URI_LENGTH = 4 KB
MAX_BODY_SIZE = 10 MB
MAX_EMPTY_LINES = 10
```

The socket read buffer is fixed at **8KB**, but the accumulation buffer can grow up to the maximum body size.

## Error Handling

Once the parser enters `ERROR` state, there is **no recovery**:

```java
if (currentState == State.ERROR) {
    return ParsingResult.error(errorMessage);
}
```

`ClientHandler` immediately closes the connection on parsing errors:

```java
if (result.isError()) {
    System.err.println("Parsing error: " + result.getErrorMessage());
    close();
}
```

## Key Architectural Decisions

1. **Separate accumulation buffer from socket buffer** - Allows accumulation to grow beyond 8KB
2. **ByteBuffer is consumed, not reused** - Parser copies bytes and ByteBuffer position advances to limit
3. **Empty ByteBuffer pattern** - Passing `ByteBuffer.allocate(0)` signals "no new data, continue with buffered bytes"
4. **Single parser instance per connection** - State and accumulation buffer persist across multiple requests
5. **Surgical data removal** - `removeProcessedData()` preserves unparsed bytes efficiently

This design enables the parser to handle:
- ✅ Requests arriving in fragments (incremental parsing)
- ✅ Multiple requests in one read (pipelining)
- ✅ Mixed scenarios (partial request 1, complete request 2)
- ✅ Keep-alive with noise bytes between requests
- ✅ Chunked transfer encoding with progressive chunk accumulation

## State Transitions Diagram

```
START
  ↓
PARSING_REQUEST_LINE
  ↓
PARSING_HEADERS
  ↓
  ├→ [No body] → COMPLETE
  ├→ [Content-Length] → PARSING_BODY_FIXED_LENGTH → COMPLETE
  └→ [Chunked] → PARSING_CHUNK_SIZE
                    ↓
                 PARSING_CHUNK_DATA
                    ↓
                    ├→ [More chunks] → (loop back to PARSING_CHUNK_SIZE)
                    └→ [Last chunk] → PARSING_CHUNK_TRAILER → COMPLETE

Any state can transition to ERROR on validation failure
```

## Testing

The parser has comprehensive test coverage in [RequestParserTest.java](test/http/RequestParserTest.java):

- ✅ Request line parsing (methods, paths, query strings, versions)
- ✅ Header parsing (case-insensitive, multi-line, tabs, validation)
- ✅ Body parsing (fixed-length, chunked, large bodies)
- ✅ Incremental parsing (fragmented requests)
- ✅ Empty line handling (robustness)
- ✅ Keep-alive detection (HTTP/1.0 vs HTTP/1.1)
- ✅ Error cases (malformed requests, size limits, invalid formats)

Run tests with: `make test`
