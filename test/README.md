# Test Suite Documentation

This directory contains a comprehensive test suite for the HTTP server project, covering all requirements from the audit checklist.

## Quick Start

```bash
# 1. Run unit tests
make test

# 2. Start server
make run &

# 3. Run integration tests
make test-integration

# 4. Run manual tests
make test-manual

# 5. Run stress test
make stress-test
```

## Test Files

### Unit Tests
- **`http/RequestParserTest.java`** - 103 tests covering HTTP request parsing
  - Request line parsing
  - Header parsing (including multi-line)
  - Body parsing (fixed-length and chunked)
  - Incremental parsing
  - Error handling
  - Keep-alive detection

### Integration Tests
- **`integration/IntegrationTestSuite.java`** - Automated integration tests
  - Tests all HTTP methods (GET, POST, DELETE)
  - Tests error handling (404, 405, 413)
  - Tests features (redirects, CGI, sessions, keep-alive)
  - Tests concurrent requests

### Manual Tests
- **`manual_tests.sh`** - Comprehensive shell script testing
  - 18 different test scenarios
  - Uses curl, nc, and standard Unix tools
  - Includes file integrity checks
  - Color-coded pass/fail output

### Configuration Tests
- **`configs/test_body_limit.json`** - Tests body size limits
- **`configs/test_duplicate_port.json`** - Tests port conflict detection
- **`configs/test_method_restrictions.json`** - Tests HTTP method restrictions

## Documentation

### Guides
1. **`COMPREHENSIVE_TEST_GUIDE.md`** - Complete testing documentation
   - Detailed test instructions
   - Expected results
   - Code references
   - Troubleshooting

2. **`AUDIT_QUICK_REFERENCE.md`** - Quick reference for auditors
   - Answers to all audit questions
   - Code locations
   - Quick test commands
   - Summary checklist

3. **`ISSUES_FOUND.md`** - Known issues and status
   - Issues discovered during testing
   - Implementation status
   - Test commands for each issue
   - Action items

4. **`TESTING_GUIDE.md`** - Testing framework documentation
   - How the custom test framework works
   - How to write new tests
   - Comparison with JUnit
   - Best practices

## Running Tests

### Unit Tests (103 tests)
```bash
make test
```

**Coverage:**
- HTTP protocol parsing
- Request line validation
- Header parsing and validation
- Body parsing (fixed and chunked)
- Error handling
- Edge cases

**Expected output:**
```
=================================================
       HTTP RequestParser Unit Tests
=================================================
...
All tests passed!
```

### Integration Tests
```bash
# Start server first
make run &

# Run integration tests
make test-integration
```

**Coverage:**
- Server availability
- Static file serving
- Error responses
- File uploads/downloads
- HTTP methods
- Redirects
- CGI execution
- Sessions and cookies
- Concurrent requests

### Manual Tests
```bash
# Start server first
make run &

# Run manual tests
make test-manual
```

**Coverage:**
- All HTTP methods with various scenarios
- File integrity (upload/download)
- Chunked encoding
- Body size limits
- Sessions and cookies
- Concurrent requests
- Malformed requests
- Virtual hosts

### Stress Test
```bash
# Install siege if needed
sudo apt install siege   # or: brew install siege

# Start server
make run &

# Run stress test
make stress-test
```

**Target:** 99.5% availability with 50 concurrent users for 30 seconds

**Expected output:**
```
Transactions:               XXXX hits
Availability:              99.XX %  <- Should be >= 99.5%
Elapsed time:              30.00 secs
Response time:             X.XX secs
```

## Test Configuration Files

### Test Body Limit
**File:** `configs/test_body_limit.json`

**Purpose:** Tests body size limit enforcement (1KB limit)

**Usage:**
```bash
make run-config CONFIG=test/configs/test_body_limit.json &

# Should succeed (small body)
curl -X POST -d "small" http://localhost:8080/upload/test.txt

# Should fail with 413 (large body)
dd if=/dev/zero bs=1K count=2 | curl -X POST --data-binary @- http://localhost:8080/upload/large.txt
```

### Test Duplicate Port
**File:** `configs/test_duplicate_port.json`

**Purpose:** Tests duplicate port detection

**Usage:**
```bash
make run-config CONFIG=test/configs/test_duplicate_port.json
# Should output error: "Duplicate port: 8080"
```

### Test Method Restrictions
**File:** `configs/test_method_restrictions.json`

**Purpose:** Tests HTTP method restrictions per route

**Usage:**
```bash
make run-config CONFIG=test/configs/test_method_restrictions.json &

# Should succeed
curl http://localhost:8080/read-only/

# Should fail with 405
curl -X POST http://localhost:8080/read-only/
```

## Architecture Verification

### Q: Which function is used for I/O Multiplexing?
**A:** `Selector.select()` in `Server.java:58`

### Q: Is the server using only one select?
**A:** Yes, single Selector instance in `Server.java:25`

### Q: Is there only one read/write per client per select?
**A:** Yes, see `ClientHandler.java:68-75` (read) and `194-206` (write)

### Q: Are I/O return values checked?
**A:** Yes, see `ClientHandler.java:72-76` (checks for -1, 0, > 0)

### Q: Is the client removed on error?
**A:** Yes, see `ClientHandler.java:107-111` and `268-273`

## Test Coverage Matrix

| Feature | Unit Test | Integration Test | Manual Test |
|---------|-----------|------------------|-------------|
| GET requests | Yes | Yes | Yes |
| POST uploads | Yes | Yes | Yes |
| DELETE | No | Yes | Yes |
| Directory listing | No | Yes | Yes |
| Redirects | No | Yes | Yes |
| Error pages | No | Yes | Yes |
| CGI execution | No | Yes | Yes |
| Sessions/Cookies | No | Yes | Yes |
| Chunked encoding | Yes | Yes | Yes |
| Body size limits | Yes | Yes | Yes |
| Keep-alive | Yes | Yes | Yes |
| Concurrent requests | No | Yes | Yes |
| Virtual hosts | No | No | Yes |
| Malformed requests | Yes | Yes | Yes |
| Path traversal | Yes | No | Yes |

## Success Criteria

Your server passes all tests if:

1. ✓ All 103 unit tests pass
2. ✓ All integration tests pass (20+ tests)
3. ✓ All manual tests show expected behavior (18 tests)
4. ✓ Configuration tests work correctly
5. ✓ Siege achieves >= 99.5% availability
6. ✓ No memory leaks or hanging connections
7. ✓ Can answer all architecture questions with code references

## Troubleshooting

### Server won't start
```bash
# Check if port is already in use
lsof -i :8080

# Kill process using port
kill $(lsof -t -i:8080)
```

### Tests failing
```bash
# Check server is running
curl http://localhost:8080/

# Check server logs
tail -f logs/server.log
```

### Siege not installed
```bash
# Ubuntu/Debian
sudo apt install siege

# macOS
brew install siege
```

### Connection limit issues
```bash
# Check current limit
ulimit -n

# Increase limit
ulimit -n 4096
```

## Audit Preparation

Before the audit, make sure:

1. All tests pass:
   ```bash
   make test
   make run &
   sleep 2
   make test-integration
   make test-manual
   make stress-test
   ```

2. You can answer all architecture questions (see `AUDIT_QUICK_REFERENCE.md`)

3. You understand the code flow:
   - How requests are received
   - How parsing works
   - How routing works
   - How responses are generated

4. You can demonstrate features:
   - Virtual hosts
   - Method restrictions
   - Body size limits
   - CGI execution
   - Sessions and cookies

5. You have reviewed:
   - `COMPREHENSIVE_TEST_GUIDE.md` - Full testing instructions
   - `AUDIT_QUICK_REFERENCE.md` - Quick answers with code refs
   - `ISSUES_FOUND.md` - Known issues and their status

## Additional Resources

- **Subject:** `docs/subject.md` - Project requirements
- **Audit:** `docs/audit.md` - Audit questions
- **Parser:** `docs/parser.md` - HTTP parser documentation
- **README:** `README.md` - Project overview

## Contributing Tests

To add new tests:

### Unit Tests
1. Add test method to `http/RequestParserTest.java`
2. Follow existing pattern (arrange-act-assert)
3. Use helper methods (assertTrue, assertEquals, etc.)
4. Run with `make test`

### Integration Tests
1. Add test method to `integration/IntegrationTestSuite.java`
2. Use HttpURLConnection for HTTP requests
3. Check response codes and content
4. Run with `make test-integration`

### Manual Tests
1. Add test to `manual_tests.sh`
2. Use curl or other command-line tools
3. Check exit codes and output
4. Run with `make test-manual`

## License

This test suite is part of the HTTP server project for educational purposes.
