# HTTP Request Parser

## Overview

The `RequestParser` is a **stateful, incremental HTTP/1.1 parser** that handles partial data streams without blocking. It supports standard HTTP features plus advanced capabilities like chunked encoding, multipart file uploads, and large body streaming to disk.

## Core Design

### State Machine

The parser uses an enum-based state machine to track parsing progress:

```
PARSING_REQUEST_LINE → PARSING_HEADERS → PARSING_BODY_* → COMPLETE
                                       ↓
                                    ERROR
```

**States:**
- `PARSING_REQUEST_LINE` - Extract method, path, HTTP version
- `PARSING_HEADERS` - Parse headers line-by-line, handle multi-line headers
- `PARSING_BODY_FIXED_LENGTH` - Read body with known Content-Length
- `PARSING_CHUNK_SIZE` - Read chunk size in chunked transfer encoding
- `PARSING_CHUNK_DATA` - Read chunk data
- `PARSING_CHUNK_TRAILER` - Read final CRLF after last chunk
- `PARSING_MULTIPART` - Parse multipart/form-data boundaries and parts
- `COMPLETE` - Request fully parsed
- `ERROR` - Unrecoverable parsing error

### Incremental Parsing

The parser **never blocks** waiting for data. It accumulates incoming bytes in `accumulationBuffer` and processes what's available:

```java
public ParsingResult parse(ByteBuffer buffer) {
    byte[] newData = new byte[buffer.remaining()];
    buffer.get(newData);
    accumulationBuffer.write(newData, 0, newData.length);
    return parseAccumulatedData();
}
```

When data is insufficient, it returns `ParsingResult.needMoreData()` and waits for the next `parse()` call.

## Key Features

### 1. Request Line Parsing

Extracts HTTP method, URI, and version:

```
GET /api/users?id=123 HTTP/1.1
```

**Validations:**
- Maximum line length: 8KB
- Maximum URI length: 4KB
- Path traversal detection (`..`)
- URL decoding with UTF-8
- Fragment removal (`#`)
- Query string extraction

### 2. Header Parsing

**Features:**
- Case-insensitive header names (stored lowercase)
- Multi-line header folding (RFC 7230)
- Maximum total header size: 16KB
- Cookie extraction from `Cookie` header

**Multi-line Header Example:**
```
Set-Cookie: sessionid=abc123;
 expires=Wed, 21 Oct 2025 07:28:00 GMT;
 path=/
```
Parsed as: `Set-Cookie: sessionid=abc123; expires=Wed, 21 Oct 2025 07:28:00 GMT; path=/`

### 3. Body Handling

The parser supports three body transfer modes:

#### Fixed-Length Body (Content-Length)
- Reads exactly `Content-Length` bytes
- Bodies > 5MB automatically stream to disk
- Prevents memory exhaustion on large uploads

#### Chunked Transfer Encoding
- Parses hex chunk sizes
- Handles chunk extensions (`;name=value`)
- Supports trailer headers after final chunk
- Validates CRLF after each chunk

#### Multipart/Form-Data
- Extracts boundary from `Content-Type` header
- Parses part headers (Content-Disposition, Content-Type)
- Files stream to temporary disk files
- Form fields stay in memory (max 64KB per field)
- Handles chunked multipart (rare but valid)

### 4. Disk Streaming

**Automatic disk streaming triggers when:**
- Body size > 5MB (configurable threshold)
- Multipart file upload detected

**Implementation:**
```java
private void switchToDiskMode() throws IOException {
    bodyTempFile = File.createTempFile("http-body-", ".tmp");
    bodyTempFile.deleteOnExit();
    bodyOutputStream = new FileOutputStream(bodyTempFile);
    streamingBodyToDisk = true;
    httpRequest.setBodyTempFile(bodyTempFile);
}
```

Temporary files are automatically cleaned up via `cleanup()`.

### 5. Robustness Features

**Empty Line Tolerance:**
- Skips up to 10 empty lines before request (common in keep-alive connections)
- Prevents noise from affecting parsing

**Security Validations:**
- Path traversal detection
- Maximum size limits on all components
- Host header requirement (HTTP/1.1)
- Content-Length conflict detection

**Error Handling:**
- All parsing errors return descriptive messages
- Parser enters ERROR state on failure
- Resources cleaned up automatically

## API Usage

### Basic Usage

```java
RequestParser parser = new RequestParser();
ByteBuffer buffer = ByteBuffer.wrap(data);

ParsingResult result = parser.parse(buffer);

if (result.isComplete()) {
    HttpRequest request = result.getRequest();
    // Use request
} else if (result.isNeedMoreData()) {
    // Wait for more data
} else if (result.isError()) {
    String error = result.getErrorMessage();
    // Handle error
}
```

### Keep-Alive Support

```java
// After processing first request
parser.reset(); // Resets state for next request on same connection

// Parse second request
ParsingResult result2 = parser.parse(nextBuffer);
```

### Custom Body Size Limit

```java
RequestParser parser = new RequestParser(50 * 1024 * 1024); // 50MB max
```

### Cleanup

```java
// Always cleanup after request processing
parser.cleanup(); // Deletes temp files, closes streams
```

## Configuration Constants

| Constant | Value | Purpose |
|----------|-------|---------|
| `MAX_REQUEST_LINE_LENGTH` | 8 KB | Prevent memory attacks via long request lines |
| `MAX_HEADER_SIZE` | 16 KB | Limit total header section size |
| `MAX_URI_LENGTH` | 4 KB | Prevent long URI attacks |
| `MAX_EMPTY_LINES` | 10 | Tolerate noise in keep-alive connections |
| `STREAM_TO_DISK_THRESHOLD` | 5 MB | Switch to disk streaming for large bodies |
| `maxBodySize` | 10 MB (default) | Maximum body size (configurable) |

## Performance Characteristics

**Memory Efficiency:**
- Small requests: ~24 KB RAM (8KB read buffer + 16KB header buffer)
- Large requests: Constant memory via disk streaming
- No memory proportional to body size

**Zero-Copy:**
- Uses `byte[]` slicing for header parsing
- No unnecessary string allocations
- Binary-safe throughout (supports non-UTF8 data)

**Incremental Processing:**
- Processes partial data immediately
- No buffering entire request before parsing
- Suitable for slow clients (prevents timeout)

## Error Messages

The parser returns specific error messages for debugging:

| Error | Cause |
|-------|-------|
| `"Request line too large"` | Line exceeds 8KB |
| `"URI too large"` | URI exceeds 4KB |
| `"Path traversal"` | Path contains `..` |
| `"No colon in header"` | Malformed header line |
| `"Headers too large"` | Headers exceed 16KB |
| `"Missing required Host header"` | HTTP/1.1 without Host |
| `"Invalid chunk size"` | Non-hex chunk size in chunked encoding |
| `"Body too large"` | Body exceeds configured max |

## Integration Notes

**Used by:** `ClientHandler` in the server's event loop

**Thread Safety:** Not thread-safe - each connection needs its own parser instance

**Lifecycle:**
1. Create parser per connection
2. Call `parse()` on each data arrival
3. Call `reset()` for keep-alive requests
4. Call `cleanup()` when connection closes