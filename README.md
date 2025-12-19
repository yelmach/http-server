# Lightweight Java HTTP Server (NIO)

A high-performance, event-driven HTTP/1.1 server implemented from scratch in Java. This project demonstrates a deep understanding of network programming by bypassing standard frameworks (like Spring, Netty, or Tomcat) to implement the raw HTTP protocol using Java's non-blocking I/O (`java.nio`).

## 📖 Overview

This server operates on a **Single-Threaded / Single-Process** model using the **Reactor Pattern**. Instead of spawning a thread per connection (blocking I/O), it uses a `Selector` to multiplex thousands of connections on a single thread. This architecture is similar to the internal workings of Nginx and Node.js.

### Key Features
* **Non-Blocking Core:** Built on `java.nio.channels` (Selector, SocketChannel).
* **HTTP/1.1 Compliant:** Handles persistence (Keep-Alive), chunked transfer encoding, and pipelining.
* **Dynamic Content:** Executes CGI scripts (Python, Perl, PHP) via `ProcessBuilder`.
* **Robust Configuration:** configuration file for ports, routes, error pages, and body size limits.
* **State Management:** Native support for Cookies and Sessions.
* **Strategy-Based Routing:** Modular handler system for static files, uploads, and CGI.


## 🏗 Project Architecture

The codebase is organized by strict separation of concerns:

```text
src/
├── config/                  # Configuration Layer
│   ├── ConfigLoader.java    # Parses config file
│   ├── ConfigValidator.java # Validates ports, paths, and permissions
│   ├── RouteConfig.java     # POJO for route definitions
│   └── ServerConfig.java    # POJO for global server settings
│
├── core/                    # The Engine (NIO)
│   ├── Server.java          # The Event Loop (Selector operations)
│   └── ClientHandler.java   # Manages connection state & buffers
│
├── handlers/                # Business Logic
│   ├── IRequestHandler.java # Interface for all request processors
│   ├── Router.java          # Matches URI to specific Handlers
│   ├── StaticHandler.java   # Serves .html, .css, images
│   └── CGIHandler.java      # Executes external scripts
│
├── http/                    # Protocol Layer
│   ├── HttpParser.java      # State machine for parsing raw bytes
│   ├── HttpRequest.java     # Structured request object
│   ├── HttpResponse.java    # Response builder
│   ├── Cookie.java          # Cookie management
│   ├── SessionManager.java  # In-memory session store
│   └── ...                  # Enums (Method, StatusCode, Header)
│
├── utils/
│   ├── Logger.java          # Custom logging
│   └── MimeTypes.java       # File extension to MIME type mapping
│
└── Main.java                # Entry point
```

## 🧠 The Core: HTTP Parser

The parsing engine (`RequestParser.java`) is designed to handle **TCP fragmentation** and **Packet Coalescing** inherently. Since `java.nio` is non-blocking, we cannot assume a single `read()` call contains a complete HTTP request—it might contain half a request, or three requests at once.

To solve this, the parser is implemented as a **Finite State Machine (FSM)**. for more details [parser.md](docs/parser.md)

### 1. Finite State Machine (FSM) Design
Instead of waiting for a full stream, the parser processes bytes as they arrive, transitioning through strict states. It pauses execution when data runs out and resumes exactly where it left off when new bytes arrive.

**The States:**
* `PARSING_REQUEST_LINE`: Accumulates bytes until `\r\n`. Parses Method, URI (with percent-decoding), and Version.
* `PARSING_HEADERS`: Reads key-value pairs until an empty line (`\r\n\r\n`) is found. Enforces strict RFC 7230/9112 rules (no whitespace before colon, unique Host header).
* `PARSING_BODY_FIXED_LENGTH`: Reads exactly `Content-Length` bytes.
* `PARSING_CHUNK_SIZE` & `CHUNK_DATA`: Handles `Transfer-Encoding: chunked` for streaming uploads.
* `COMPLETE`: Signals the `ClientHandler` that a request is ready for processing.

### 2. Handling TCP Anomalies
* **Fragmentation (Partial Requests):** If a packet ends in the middle of a header, the parser returns `NEED_MORE_DATA`. The bytes are stored in an `accumulationBuffer`, and the parser waits for the next `read()` event to append the missing parts.
* **Pipelining (Multiple Requests):** If a client sends multiple requests in one batch (e.g., `GET /A` and `GET /B`), the parser processes `/A`, triggers a response, and **shifts** the buffer to preserve the bytes of `/B`. The `ClientHandler` loops until the buffer is drained, ensuring no requests are dropped.

### 3. Security Features
The parser includes "Defense in Depth" mechanisms to prevent common attacks:
* **Request Smuggling:** Strictly rejects requests with conflicting `Content-Length` headers or mixed `Transfer-Encoding`.
* **Path Traversal:** Decodes URIs and blocks paths containing `..` after decoding.
* **DoS Protection:** Enforces hard limits on Request Line length (8KB), Header size (16KB), and Body size (Configurable) to prevent memory exhaustion.