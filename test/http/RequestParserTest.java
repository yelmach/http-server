package http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Comprehensive unit tests for the HTTP RequestParser.
 * Tests all functionality including:
 * - Request line parsing
 * - Header parsing (including multi-line headers)
 * - Fixed-length body parsing
 * - Chunked transfer encoding
 * - Error handling and edge cases
 */
public class RequestParserTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("       HTTP RequestParser Unit Tests");
        System.out.println("=================================================\n");

        // Request Line Tests
        testSimpleGetRequest();
        testGetRequestWithPath();
        testGetRequestWithQueryString();
        testPostRequest();
        testAllHttpMethods();
        testInvalidHttpMethod();
        testMalformedRequestLine();
        testRequestLineTooLong();
        testUriTooLong();
        testPathTraversalDetection();
        testHttp10Support();

        // Header Tests
        testSingleHeader();
        testMultipleHeaders();
        testCaseInsensitiveHeaders();
        testHeadersWithSpaces();
        testMultiLineHeader();
        testMultipleMultiLineHeaders();
        testMultiLineHeaderWithTab();
        testMultiLineHeaderWithoutPrecedingHeader();
        testHeaderWithoutColon();
        testEmptyHeaderName();
        testHeadersTooLarge();
        testMissingHostHeader();

        // Body Tests
        testPostWithFixedLengthBody();
        testPostWithLargeBody();
        testBodyExceedsMaxSize();

        // Chunked Encoding Tests
        testChunkedEncodingSingleChunk();
        testChunkedEncodingMultipleChunks();
        testChunkedEncodingWithExtensions();
        testChunkedEncodingEmptyChunks();
        testChunkedEncodingInvalidSize();
        testChunkedBodyExceedsMaxSize();

        // Incremental Parsing Tests
        testIncrementalParsing();
        testIncrementalParsingAcrossMultipleReads();
        testIncrementalBodyParsing();
        testIncrementalChunkedParsing();

        // Edge Cases
        testEmptyRequest();
        testRequestWithOnlyRequestLine();
        testCompleteRequestInMultipleParts();
        testParserReset();

        // Print Summary
        System.out.println("\n=================================================");
        System.out.println("                 Test Summary");
        System.out.println("=================================================");
        System.out.println("Tests Passed: " + testsPassed);
        System.out.println("Tests Failed: " + testsFailed);
        System.out.println("Total Tests:  " + (testsPassed + testsFailed));
        System.out.println("=================================================");

        if (testsFailed == 0) {
            System.out.println("\n✓ All tests passed!");
            System.exit(0);
        } else {
            System.out.println("\n✗ Some tests failed!");
            System.exit(1);
        }
    }

    // ========================================
    // Request Line Tests
    // ========================================

    private static void testSimpleGetRequest() {
        RequestParser parser = new RequestParser();
        String request = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Simple GET request should be complete", result.isComplete());
        assertNotNull("Request should not be null", result.getRequest());
        assertEquals("Method should be GET", HttpMethod.GET, result.getRequest().getMethod());
        assertEquals("Path should be /", "/", result.getRequest().getPath());
        assertEquals("HTTP version should be HTTP/1.1", "HTTP/1.1", result.getRequest().getHttpVersion());
    }

    private static void testGetRequestWithPath() {
        RequestParser parser = new RequestParser();
        String request = "GET /api/users/123 HTTP/1.1\r\nHost: localhost\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Request should be complete", result.isComplete());
        assertEquals("Path should be /api/users/123", "/api/users/123", result.getRequest().getPath());
    }

    private static void testGetRequestWithQueryString() {
        RequestParser parser = new RequestParser();
        String request = "GET /search?q=test&limit=10 HTTP/1.1\r\nHost: localhost\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Request should be complete", result.isComplete());
        assertEquals("Path should be /search", "/search", result.getRequest().getPath());
        assertEquals("Query string should be q=test&limit=10", "q=test&limit=10", result.getRequest().getQueryString());
    }

    private static void testPostRequest() {
        RequestParser parser = new RequestParser();
        String request = "POST /api/data HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Request should be complete", result.isComplete());
        assertEquals("Method should be POST", HttpMethod.POST, result.getRequest().getMethod());
    }

    private static void testAllHttpMethods() {
        String[] methods = {"GET", "POST", "DELETE"};

        for (String method : methods) {
            RequestParser parser = new RequestParser();
            String request = method + " / HTTP/1.1\r\nHost: localhost\r\n\r\n";
            ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

            ParsingResult result = parser.parse(buffer);

            assertTrue("Request with " + method + " should be complete", result.isComplete());
            assertEquals("Method should be " + method,
                        HttpMethod.fromStringUnsafe(method),
                        result.getRequest().getMethod());
        }
    }

    private static void testInvalidHttpMethod() {
        RequestParser parser = new RequestParser();
        String request = "INVALID / HTTP/1.1\r\nHost: localhost\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Invalid method should result in error", result.isError());
        assertContains("Error message should mention invalid method",
                      result.getErrorMessage(), "Invalid HTTP method");
    }

    private static void testMalformedRequestLine() {
        RequestParser parser = new RequestParser();
        String request = "GETHTTP/1.1\r\nHost: localhost\r\n\r\n"; // Missing spaces
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Malformed request line should result in error", result.isError());
        assertContains("Error should mention invalid format",
                      result.getErrorMessage(), "Invalid request line format");
    }

    private static void testRequestLineTooLong() {
        RequestParser parser = new RequestParser();
        String longPath = "/" + "a".repeat(9000); // Exceeds 8KB limit
        String request = "GET " + longPath + " HTTP/1.1\r\nHost: localhost\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Too long request line should result in error", result.isError());
        assertContains("Error should mention request line length",
                      result.getErrorMessage(), "Request line exceeds maximum length");
    }

    private static void testUriTooLong() {
        RequestParser parser = new RequestParser();
        String longPath = "/" + "b".repeat(5000); // Exceeds 4KB URI limit
        String request = "GET " + longPath + " HTTP/1.1\r\nHost: localhost\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Too long URI should result in error", result.isError());
        assertContains("Error should mention URI length",
                      result.getErrorMessage(), "URI exceeds maximum length");
    }

    private static void testPathTraversalDetection() {
        RequestParser parser = new RequestParser();
        String request = "GET /../../etc/passwd HTTP/1.1\r\nHost: localhost\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Path traversal should result in error", result.isError());
        assertContains("Error should mention path traversal",
                      result.getErrorMessage(), "Path traversal detected");
    }

    private static void testHttp10Support() {
        RequestParser parser = new RequestParser();
        String request = "GET / HTTP/1.0\r\nHost: localhost\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("HTTP/1.0 request should be supported", result.isComplete());
        assertEquals("HTTP version should be HTTP/1.0", "HTTP/1.0", result.getRequest().getHttpVersion());
    }

    // ========================================
    // Header Tests
    // ========================================

    private static void testSingleHeader() {
        RequestParser parser = new RequestParser();
        String request = "GET / HTTP/1.1\r\nHost: localhost\r\nUser-Agent: TestClient\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Request should be complete", result.isComplete());
        assertTrue("Should have Host header", result.getRequest().getHeaders().has("host"));
        assertEquals("User-Agent should be TestClient", "TestClient",
                    result.getRequest().getHeaders().get("user-agent"));
    }

    private static void testMultipleHeaders() {
        RequestParser parser = new RequestParser();
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "User-Agent: TestClient\r\n" +
                        "Accept: text/html\r\n" +
                        "Content-Type: application/json\r\n" +
                        "\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Request should be complete", result.isComplete());
        assertEquals("Should have 4 headers", true, result.getRequest().getHeaders().has("host") &&
                    result.getRequest().getHeaders().has("user-agent") &&
                    result.getRequest().getHeaders().has("accept") &&
                    result.getRequest().getHeaders().has("content-type"));
    }

    private static void testCaseInsensitiveHeaders() {
        RequestParser parser = new RequestParser();
        String request = "GET / HTTP/1.1\r\nHOST: localhost\r\nUser-Agent: Test\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Request should be complete", result.isComplete());
        assertTrue("Should find host with lowercase", result.getRequest().getHeaders().has("host"));
        assertTrue("Should find HOST with uppercase", result.getRequest().getHeaders().has("HOST"));
        assertEquals("Values should match", "localhost", result.getRequest().getHeaders().get("host"));
    }

    private static void testHeadersWithSpaces() {
        RequestParser parser = new RequestParser();
        String request = "GET / HTTP/1.1\r\nHost:   localhost  \r\nUser-Agent:  TestClient  \r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Request should be complete", result.isComplete());
        assertEquals("Host should be trimmed", "localhost", result.getRequest().getHeaders().get("host"));
        assertEquals("User-Agent should be trimmed", "TestClient", result.getRequest().getHeaders().get("user-agent"));
    }

    private static void testMultiLineHeader() {
        RequestParser parser = new RequestParser();
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "X-Long-Header: This is part one\r\n" +
                        " and this is part two\r\n" +
                        "\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Request should be complete", result.isComplete());
        String headerValue = result.getRequest().getHeaders().get("x-long-header");
        assertEquals("Multi-line header should be concatenated",
                    "This is part one and this is part two", headerValue);
    }

    private static void testMultipleMultiLineHeaders() {
        RequestParser parser = new RequestParser();
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Set-Cookie: sessionid=abc123;\r\n" +
                        " expires=Wed, 21 Oct 2025 07:28:00 GMT;\r\n" +
                        " path=/\r\n" +
                        "\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Request should be complete", result.isComplete());
        String cookie = result.getRequest().getHeaders().get("set-cookie");
        assertContains("Cookie should contain all parts", cookie, "sessionid=abc123");
        assertContains("Cookie should contain expires", cookie, "expires=");
        assertContains("Cookie should contain path", cookie, "path=/");
    }

    private static void testMultiLineHeaderWithTab() {
        RequestParser parser = new RequestParser();
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "X-Folded: line1\r\n" +
                        "\tline2\r\n" +
                        "\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Request should be complete", result.isComplete());
        assertEquals("Tab-folded header should work", "line1 line2",
                    result.getRequest().getHeaders().get("x-folded"));
    }

    private static void testMultiLineHeaderWithoutPrecedingHeader() {
        RequestParser parser = new RequestParser();
        // Continuation line comes FIRST, before any header (this is the error case)
        String request = "GET / HTTP/1.1\r\n" +
                        " invalid-continuation\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Should result in error", result.isError());
        assertContains("Error should mention continuation",
                      result.getErrorMessage(), "continuation line without preceding header");
    }

    private static void testHeaderWithoutColon() {
        RequestParser parser = new RequestParser();
        String request = "GET / HTTP/1.1\r\nHost localhost\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Should result in error", result.isError());
        assertContains("Error should mention missing colon",
                      result.getErrorMessage(), "missing colon");
    }

    private static void testEmptyHeaderName() {
        RequestParser parser = new RequestParser();
        String request = "GET / HTTP/1.1\r\nHost: localhost\r\n: value\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Should result in error", result.isError());
        assertContains("Error should mention empty header name",
                      result.getErrorMessage(), "Empty header name");
    }

    private static void testHeadersTooLarge() {
        RequestParser parser = new RequestParser();
        // Create headers that exceed 16KB without completing (no \r\n\r\n)
        String largeHeader = "X-Large: " + "a".repeat(20000) + "\r\n";
        // Don't send final \r\n so headers aren't complete yet
        String request = "GET / HTTP/1.1\r\nHost: localhost\r\n" + largeHeader;
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Should result in error", result.isError());
        assertContains("Error should mention header size",
                      result.getErrorMessage(), "Headers exceed maximum size");
    }

    private static void testMissingHostHeader() {
        RequestParser parser = new RequestParser();
        String request = "GET / HTTP/1.1\r\nUser-Agent: Test\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Should result in error", result.isError());
        assertContains("Error should mention missing Host",
                      result.getErrorMessage(), "Missing required Host header");
    }

    // ========================================
    // Body Tests
    // ========================================

    private static void testPostWithFixedLengthBody() {
        RequestParser parser = new RequestParser();
        String body = "{\"key\":\"value\"}";
        String request = "POST /api HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: " + body.length() + "\r\n" +
                        "\r\n" +
                        body;
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Request should be complete", result.isComplete());
        assertNotNull("Body should not be null", result.getRequest().getBody());
        assertEquals("Body should match", body, result.getRequest().getBodyAsString());
    }

    private static void testPostWithLargeBody() {
        RequestParser parser = new RequestParser();
        String body = "x".repeat(1024 * 100); // 100 KB
        String request = "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: " + body.length() + "\r\n" +
                        "\r\n" +
                        body;
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Large body request should be complete", result.isComplete());
        assertEquals("Body length should match", body.length(), result.getRequest().getBody().length);
    }

    private static void testBodyExceedsMaxSize() {
        RequestParser parser = new RequestParser(1024); // 1 KB max
        String body = "x".repeat(2000); // 2 KB body
        String request = "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: " + body.length() + "\r\n" +
                        "\r\n" +
                        body;
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Should result in error", result.isError());
        assertContains("Error should mention body size",
                      result.getErrorMessage(), "exceeds maximum");
    }

    // ========================================
    // Chunked Encoding Tests
    // ========================================

    private static void testChunkedEncodingSingleChunk() {
        RequestParser parser = new RequestParser();
        String request = "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n" +
                        "5\r\n" +
                        "hello\r\n" +
                        "0\r\n" +
                        "\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Chunked request should be complete", result.isComplete());
        assertEquals("Body should be 'hello'", "hello", result.getRequest().getBodyAsString());
    }

    private static void testChunkedEncodingMultipleChunks() {
        RequestParser parser = new RequestParser();
        String request = "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n" +
                        "5\r\n" +
                        "hello\r\n" +
                        "6\r\n" +
                        " world\r\n" +
                        "0\r\n" +
                        "\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Chunked request should be complete", result.isComplete());
        assertEquals("Body should be 'hello world'", "hello world", result.getRequest().getBodyAsString());
    }

    private static void testChunkedEncodingWithExtensions() {
        RequestParser parser = new RequestParser();
        String request = "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n" +
                        "5;name=value\r\n" +
                        "hello\r\n" +
                        "0\r\n" +
                        "\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Chunked request with extensions should be complete", result.isComplete());
        assertEquals("Body should be 'hello'", "hello", result.getRequest().getBodyAsString());
    }

    private static void testChunkedEncodingEmptyChunks() {
        RequestParser parser = new RequestParser();
        String request = "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n" +
                        "0\r\n" +
                        "\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Empty chunked request should be complete", result.isComplete());
        assertNull("Body should be null for empty chunks", result.getRequest().getBody());
    }

    private static void testChunkedEncodingInvalidSize() {
        RequestParser parser = new RequestParser();
        String request = "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n" +
                        "INVALID\r\n" +
                        "hello\r\n" +
                        "0\r\n" +
                        "\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Should result in error", result.isError());
        assertContains("Error should mention chunk size",
                      result.getErrorMessage(), "Invalid chunk size format");
    }

    private static void testChunkedBodyExceedsMaxSize() {
        RequestParser parser = new RequestParser(10); // 10 bytes max
        String request = "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n" +
                        "14\r\n" + // 20 bytes
                        "12345678901234567890\r\n" +
                        "0\r\n" +
                        "\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Should result in error", result.isError());
        assertContains("Error should mention body size",
                      result.getErrorMessage(), "exceeds maximum");
    }

    // ========================================
    // Incremental Parsing Tests
    // ========================================

    private static void testIncrementalParsing() {
        RequestParser parser = new RequestParser();
        String request = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";

        // Send one byte at a time
        for (byte b : request.getBytes(StandardCharsets.UTF_8)) {
            ByteBuffer buffer = ByteBuffer.wrap(new byte[]{b});
            ParsingResult result = parser.parse(buffer);

            if (result.isComplete()) {
                assertTrue("Should eventually complete", true);
                return;
            }
        }

        fail("Incremental parsing should complete");
    }

    private static void testIncrementalParsingAcrossMultipleReads() {
        RequestParser parser = new RequestParser();
        String request = "GET /test HTTP/1.1\r\nHost: localhost\r\nUser-Agent: Test\r\n\r\n";
        byte[] bytes = request.getBytes(StandardCharsets.UTF_8);

        // Split into 3 parts
        int part1 = bytes.length / 3;
        int part2 = bytes.length * 2 / 3;

        ByteBuffer buffer1 = ByteBuffer.wrap(bytes, 0, part1);
        ParsingResult result1 = parser.parse(buffer1);
        assertTrue("First part should need more data", result1.isNeedMoreData());

        ByteBuffer buffer2 = ByteBuffer.wrap(bytes, part1, part2 - part1);
        ParsingResult result2 = parser.parse(buffer2);
        // May need more or be complete depending on split point

        ByteBuffer buffer3 = ByteBuffer.wrap(bytes, part2, bytes.length - part2);
        ParsingResult result3 = parser.parse(buffer3);
        assertTrue("Final part should be complete", result3.isComplete());
    }

    private static void testIncrementalBodyParsing() {
        RequestParser parser = new RequestParser();
        String body = "This is a test body";
        String request = "POST /api HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: " + body.length() + "\r\n" +
                        "\r\n" +
                        body;
        byte[] bytes = request.getBytes(StandardCharsets.UTF_8);

        // Send headers first
        int headerEnd = request.indexOf("\r\n\r\n") + 4;
        ByteBuffer buffer1 = ByteBuffer.wrap(bytes, 0, headerEnd);
        ParsingResult result1 = parser.parse(buffer1);
        assertTrue("After headers should need more data", result1.isNeedMoreData());

        // Send body in chunks
        int bodyStart = headerEnd;
        int chunkSize = 5;
        for (int i = bodyStart; i < bytes.length; i += chunkSize) {
            int len = Math.min(chunkSize, bytes.length - i);
            ByteBuffer buffer = ByteBuffer.wrap(bytes, i, len);
            ParsingResult result = parser.parse(buffer);

            if (i + len >= bytes.length) {
                assertTrue("Should be complete after full body", result.isComplete());
            }
        }
    }

    private static void testIncrementalChunkedParsing() {
        RequestParser parser = new RequestParser();
        String request = "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n" +
                        "5\r\n" +
                        "hello\r\n" +
                        "0\r\n" +
                        "\r\n";
        byte[] bytes = request.getBytes(StandardCharsets.UTF_8);

        // Parse in 10-byte chunks
        for (int i = 0; i < bytes.length; i += 10) {
            int len = Math.min(10, bytes.length - i);
            ByteBuffer buffer = ByteBuffer.wrap(bytes, i, len);
            ParsingResult result = parser.parse(buffer);

            if (i + len >= bytes.length) {
                assertTrue("Should be complete at end", result.isComplete());
            }
        }
    }

    // ========================================
    // Edge Cases
    // ========================================

    private static void testEmptyRequest() {
        RequestParser parser = new RequestParser();
        String request = "";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Empty request should need more data", result.isNeedMoreData());
    }

    private static void testRequestWithOnlyRequestLine() {
        RequestParser parser = new RequestParser();
        String request = "GET / HTTP/1.1\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Incomplete request should need more data", result.isNeedMoreData());
    }

    private static void testCompleteRequestInMultipleParts() {
        RequestParser parser = new RequestParser();

        // Part 1: Request line
        ByteBuffer buffer1 = ByteBuffer.wrap("GET / HT".getBytes(StandardCharsets.UTF_8));
        ParsingResult result1 = parser.parse(buffer1);
        assertTrue("Part 1 should need more data", result1.isNeedMoreData());

        // Part 2: Rest of request line and headers
        ByteBuffer buffer2 = ByteBuffer.wrap("TP/1.1\r\nHost: local".getBytes(StandardCharsets.UTF_8));
        ParsingResult result2 = parser.parse(buffer2);
        assertTrue("Part 2 should need more data", result2.isNeedMoreData());

        // Part 3: End of headers
        ByteBuffer buffer3 = ByteBuffer.wrap("host\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        ParsingResult result3 = parser.parse(buffer3);
        assertTrue("Part 3 should be complete", result3.isComplete());
    }

    private static void testParserReset() {
        RequestParser parser = new RequestParser();
        String request1 = "GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n";
        ByteBuffer buffer1 = ByteBuffer.wrap(request1.getBytes(StandardCharsets.UTF_8));

        ParsingResult result1 = parser.parse(buffer1);
        assertTrue("First request should be complete", result1.isComplete());
        assertEquals("Path should be /first", "/first", result1.getRequest().getPath());

        // Reset and parse another request
        parser.reset();

        String request2 = "POST /second HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n";
        ByteBuffer buffer2 = ByteBuffer.wrap(request2.getBytes(StandardCharsets.UTF_8));

        ParsingResult result2 = parser.parse(buffer2);
        assertTrue("Second request should be complete", result2.isComplete());
        assertEquals("Method should be POST", HttpMethod.POST, result2.getRequest().getMethod());
        assertEquals("Path should be /second", "/second", result2.getRequest().getPath());
    }

    // ========================================
    // Helper Methods
    // ========================================

    private static void assertTrue(String message, boolean condition) {
        if (condition) {
            System.out.println("✓ " + message);
            testsPassed++;
        } else {
            System.out.println("✗ " + message);
            testsFailed++;
        }
    }

    private static void assertFalse(String message, boolean condition) {
        assertTrue(message, !condition);
    }

    private static void assertEquals(String message, Object expected, Object actual) {
        if ((expected == null && actual == null) ||
            (expected != null && expected.equals(actual))) {
            System.out.println("✓ " + message);
            testsPassed++;
        } else {
            System.out.println("✗ " + message + " (expected: " + expected + ", actual: " + actual + ")");
            testsFailed++;
        }
    }

    private static void assertNotNull(String message, Object object) {
        if (object != null) {
            System.out.println("✓ " + message);
            testsPassed++;
        } else {
            System.out.println("✗ " + message + " (was null)");
            testsFailed++;
        }
    }

    private static void assertNull(String message, Object object) {
        if (object == null) {
            System.out.println("✓ " + message);
            testsPassed++;
        } else {
            System.out.println("✗ " + message + " (was not null: " + object + ")");
            testsFailed++;
        }
    }

    private static void assertContains(String message, String actual, String expected) {
        if (actual != null && actual.contains(expected)) {
            System.out.println("✓ " + message);
            testsPassed++;
        } else {
            System.out.println("✗ " + message + " ('" + actual + "' does not contain '" + expected + "')");
            testsFailed++;
        }
    }

    private static void fail(String message) {
        System.out.println("✗ " + message);
        testsFailed++;
    }
}
