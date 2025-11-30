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