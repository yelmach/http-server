# File Upload Feature

## Overview

The file upload system supports two upload methods: **multipart/form-data** (with multiple files and form fields) and **raw binary uploads** (single file, direct PUT/POST). Large files automatically stream to disk to prevent memory exhaustion.

## Architecture

### Components

```
┌─────────────────┐
│  HttpRequest    │  Contains parsed upload data
└────────┬────────┘
         │
         ├──→ MultipartParser  (parses multipart boundaries)
         │
         └──→ UploadHandler    (saves files to disk)
```

**Flow:**
1. `RequestParser` detects `Content-Type: multipart/form-data`
2. Extracts boundary from header
3. `MultipartParser` parses parts during request parsing
4. `UploadHandler` saves files to target directory

## Multipart Parser

### Purpose

Parses `multipart/form-data` requests into individual parts (files and form fields) according to RFC 7578.

### Core Logic

**Boundary Detection:**
```
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW
```

Each part is separated by:
```
------WebKitFormBoundary7MA4YWxkTrZu0gW
Content-Disposition: form-data; name="file"; filename="image.jpg"
Content-Type: image/jpeg

[binary data]
------WebKitFormBoundary7MA4YWxkTrZu0gW--
```

### State Management

The parser maintains state across multiple `parse()` calls:

```java
private List<MultipartPart> multipartParts;
private MultipartPart currentPart;
private File currentPartTempFile;
private FileOutputStream currentPartOutputStream;
private ByteArrayOutputStream currentPartContent;
private boolean parsingPartHeaders;
```

**State transitions:**
1. Find boundary → Parse part headers → Read content → Find next boundary
2. Repeat until end boundary (`--boundary--`)

### Storage Strategy

The parser intelligently stores data based on size:

| Part Type | Storage | Max Size | Reason |
|-----------|---------|----------|---------|
| Form field | RAM (ByteArrayOutputStream) | 64KB | Small data, fast access |
| File | Disk (temp file) | Unlimited* | Prevents memory exhaustion |

*Limited by `maxBodySize` configuration

**Implementation:**
```java
if (currentPart.isFile()) {
    currentPartTempFile = File.createTempFile("http-part-", ".tmp");
    currentPartOutputStream = new FileOutputStream(currentPartTempFile);
    currentPart.setTempFile(currentPartTempFile);
} else {
    currentPartContent = new ByteArrayOutputStream();
}
```

### Security Features

**1. Field Size Limit (DoS Protection)**
```java
private static final int MAX_FIELD_SIZE = 64 * 1024; // 64KB

if (currentPartContent.size() + length > MAX_FIELD_SIZE) {
    return false; // Reject oversized field
}
```

Prevents attackers from sending huge form fields to exhaust RAM.

**2. Total Body Size Check**
```java
bodyBytesRead += bytesToWrite;
if (bodyBytesRead > maxBodySize) {
    return ParsingResult.error("Total body size exceeded");
}
```

**3. Boundary Validation**

All boundaries must match exactly - prevents injection attacks.

### Part Headers Parsing

Each part has headers like:
```
Content-Disposition: form-data; name="avatar"; filename="photo.jpg"
Content-Type: image/jpeg
```

**Extracted fields:**
- `name` - Form field name (required)
- `filename` - Original filename (indicates file upload)
- `contentType` - MIME type of uploaded file

```java
private MultipartPart parsePartHeaders(byte[] data, int start, int end) {
    MultipartPart part = new MultipartPart();
    
    // Parse each header line
    if (headerName.equals("content-disposition")) {
        String name = extractParameter(headerValue, "name");
        String filename = extractParameter(headerValue, "filename");
        part.setName(name);
        part.setFilename(filename);
    } else if (headerName.equals("content-type")) {
        part.setContentType(headerValue);
    }
    
    return part;
}
```

### Incremental Parsing

The parser handles partial data seamlessly:

```java
public ParsingResult parse(byte[] data, int startPos, HttpRequest httpRequest) {
    int position = startPos;
    
    while (position < data.length) {
        if (parsingPartHeaders) {
            int boundaryPos = findBoundary(data, position, boundary);
            if (boundaryPos == -1) {
                return ParsingResult.needMoreData(position); // ← Wait for more data
            }
            // ... parse headers
        } else {
            // Reading part content
            int nextBoundaryPos = findBoundary(data, position, boundary);
            if (nextBoundaryPos == -1) {
                writeToPart(data, position, data.length - position);
                return ParsingResult.needMoreData(data.length); // ← Partial write
            }
            // ... finish part
        }
    }
}
```

This allows streaming uploads without buffering entire request.

### Chunked Multipart Support

**Rare but valid scenario:** Multipart data sent with chunked transfer encoding.

```
Transfer-Encoding: chunked
Content-Type: multipart/form-data; boundary=abc123

[chunked data containing multipart content]
```

**How it works:**
1. `RequestParser` decodes chunks first (transport layer)
2. Decoded bytes are piped to `MultipartParser` (application layer)
3. Flag `isChunkedMultipart = true` tracks this mode

```java
if (isChunkedMultipart) {
    // Feed decoded chunk data to multipart parser
    byte[] chunkData = new byte[chunkRead];
    System.arraycopy(data, position, chunkData, 0, chunkRead);
    ParsingResult mpResult = multipartParser.parse(chunkData, 0, httpRequest);
}
```

### Cleanup

Critical for temp file management:

```java
public void cleanup() {
    // Close current part's output stream
    if (currentPartOutputStream != null) {
        currentPartOutputStream.close();
    }
    
    // Delete temp files
    if (currentPartTempFile != null && currentPartTempFile.exists()) {
        currentPartTempFile.delete();
    }
    
    // Delete all parsed part temp files
    for (MultipartPart part : multipartParts) {
        if (part.getTempFile() != null && part.getTempFile().exists()) {
            part.getTempFile().delete();
        }
    }
}
```

Called automatically by `RequestParser.cleanup()`.

## MultipartPart Data Class

Represents a single part in a multipart request:

```java
public class MultipartPart {
    private String name;           // Form field name
    private String filename;       // Original filename (if file)
    private String contentType;    // MIME type
    private byte[] content;        // Small data (form fields)
    private File tempFile;         // Large data (files)
    
    public boolean isFile() {
        return filename != null;
    }
}
```

## Upload Handler

### Purpose

Processes POST requests to save uploaded files to the filesystem.

### Two Upload Modes

#### 1. Multipart Upload (Multiple Files)

**Request:**
```http
POST /uploads HTTP/1.1
Content-Type: multipart/form-data; boundary=abc123

--abc123
Content-Disposition: form-data; name="file1"; filename="doc.pdf"
Content-Type: application/pdf

[PDF data]
--abc123
Content-Disposition: form-data; name="description"

Important document
--abc123--
```

**Handler Logic:**
```java
private void handleMultipartUpload(HttpRequest request, ResponseBuilder response) {
    File uploadDir = resource;
    
    // Ensure directory exists
    if (!uploadDir.exists()) {
        uploadDir.mkdirs();
    }
    
    List<MultipartPart> parts = request.getMultipartParts();
    int savedCount = 0;
    
    for (MultipartPart part : parts) {
        if (part.isFile()) {
            saveFilePart(part, uploadDir);
            savedCount++;
        }
    }
    
    response.status(HttpStatusCode.CREATED)
            .body(savedCount + " file(s) uploaded successfully");
}
```

#### 2. Raw Binary Upload (Single File)

**Request:**
```http
POST /uploads/document.pdf HTTP/1.1
Content-Type: application/pdf
Content-Length: 1048576

[raw PDF bytes]
```

**Handler Logic:**
```java
private void handleRawBinaryUpload(HttpRequest request, ResponseBuilder response) {
    File targetFile = resource;
    
    // Target must be a file path
    if (targetFile.isDirectory()) {
        response.status(HttpStatusCode.BAD_REQUEST).body("Filename required");
        return;
    }
    
    // Use temp file if available (large upload), otherwise use body
    if (request.getBodyTempFile() != null) {
        Files.move(request.getBodyTempFile().toPath(),
                  targetFile.toPath(),
                  StandardCopyOption.REPLACE_EXISTING);
    } else {
        Files.write(targetFile.toPath(), request.getBody());
    }
    
    response.status(HttpStatusCode.CREATED).body("File uploaded");
}
```

### File Saving

```java
private void saveFilePart(MultipartPart part, File uploadDir) throws Exception {
    String sanitized = sanitizeFilename(part.getFilename());
    File targetFile = new File(uploadDir, sanitized);
    
    // Security check
    if (!isPathSafe(targetFile, uploadDir.getAbsolutePath())) {
        throw new SecurityException("Path traversal in filename");
    }
    
    // Move temp file or write content
    if (part.getTempFile() != null) {
        Files.move(part.getTempFile().toPath(),
                  targetFile.toPath(),
                  StandardCopyOption.REPLACE_EXISTING);
    } else if (part.getContent() != null) {
        Files.write(targetFile.toPath(), part.getContent());
    }
}
```

### Security Features

**1. Filename Sanitization**

Prevents directory traversal and invalid characters:

```java
private String sanitizeFilename(String filename) {
    if (filename == null || filename.isEmpty()) {
        return "unknown_file";
    }
    
    // Remove any path components (../../etc/passwd)
    filename = filename.replaceAll(".*[/\\\\]", "");
    
    // Allow only safe characters
    filename = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    
    // Prevent hidden files
    if (filename.startsWith(".")) {
        filename = "_" + filename.substring(1);
    }
    
    return filename;
}
```

**Examples:**
- `../../etc/passwd` → `passwd`
- `my file (1).pdf` → `my_file__1_.pdf`
- `.htaccess` → `_htaccess`
- `<script>.txt` → `_script_.txt`

**2. Path Traversal Check**

Verifies file stays within upload directory:

```java
private boolean isPathSafe(File targetFile, String rootPath) {
    try {
        String targetCanonical = targetFile.getCanonicalPath();
        String rootCanonical = new File(rootPath).getCanonicalPath();
        return targetCanonical.startsWith(rootCanonical);
    } catch (IOException e) {
        return false;
    }
}
```

**3. Directory Validation**

```java
File parentDir = targetFile.getParentFile();
if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory()) {
    response.status(HttpStatusCode.NOT_FOUND)
            .body("Directory not found");
    return;
}
```

### Error Handling

The handler wraps all operations with exception handling:

```java
try {
    if (request.getMultipartParts() != null) {
        handleMultipartUpload(request, response);
    } else {
        handleRawBinaryUpload(request, response);
    }
} catch (SecurityException e) {
    response.status(HttpStatusCode.FORBIDDEN)
            .body("Access denied: " + e.getMessage());
} catch (IllegalArgumentException e) {
    response.status(HttpStatusCode.BAD_REQUEST)
            .body("Invalid request: " + e.getMessage());
} catch (Exception e) {
    response.status(HttpStatusCode.INTERNAL_SERVER_ERROR)
            .body("Upload failed: " + e.getMessage());
}
```

## Configuration

### Route Configuration

```json
{
  "path": "/uploads",
  "methods": ["POST"],
  "root": "./www/uploads"
}
```

### Body Size Limit

Set in `ServerConfig`:
```json
{
  "maxBodySize": 104857600  // 100MB
}
```

## Usage Examples

### Upload Single File (cURL)

**Multipart:**
```bash
curl -F "file=@document.pdf" http://localhost:8080/uploads
```

**Raw binary:**
```bash
curl -T document.pdf http://localhost:8080/uploads/document.pdf
```

### Upload Multiple Files

```bash
curl -F "file1=@photo.jpg" -F "file2=@document.pdf" http://localhost:8080/uploads
```

### With Form Fields

```bash
curl -F "file=@photo.jpg" -F "description=Profile picture" http://localhost:8080/uploads
```

## Response Codes

| Code | Meaning | Example |
|------|---------|---------|
| 201 Created | Upload successful | `"2 file(s) uploaded successfully"` |
| 400 Bad Request | Invalid request format | `"Filename required in path"` |
| 403 Forbidden | Security violation | `"Path traversal in filename"` |
| 404 Not Found | Directory doesn't exist | `"Directory not found"` |
| 413 Payload Too Large | Body exceeds limit | Handled by RequestParser |
| 500 Internal Server Error | Disk/IO error | `"Upload failed: ..."` |

## Performance Characteristics

**Memory Usage:**
- Small files (< 64KB): ~128KB RAM per request
- Large files: Constant ~8KB RAM (streaming to disk)
- Multiple files: Each file streams independently

**Disk I/O:**
- Files written to temp directory first
- Atomic move to final location (prevents partial writes)
- Temp files auto-deleted via JVM shutdown hooks

**Concurrency:**
- Each connection has isolated temp files
- No shared state between uploads
- Thread-safe (each request processed independently)

## Testing

Upload functionality can be tested with:

```bash
# Test multipart upload
make run
curl -F "file=@test.pdf" http://localhost:8080/uploads

# Test raw binary
echo "test data" > test.txt
curl -T test.txt http://localhost:8080/uploads/test.txt

# Test large file
dd if=/dev/zero of=large.bin bs=1M count=50
curl -F "file=@large.bin" http://localhost:8080/uploads
```

## Integration with Router

The router automatically selects `UploadHandler` for POST requests:

```java
if (httpRequest.getMethod().name().equals("POST")) {
    return new UploadHandler(route, resource);
}
```

No manual configuration needed beyond route definition.