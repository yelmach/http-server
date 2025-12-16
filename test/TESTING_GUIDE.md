# Testing Guide

## Current Testing Approach

This project uses a **custom lightweight testing framework** with zero external dependencies. This is intentional for learning purposes and keeps the project simple.

### Running Tests

```bash
make test
```

### Test Structure

```java
public class RequestParserTest {
    // Static counters track results
    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        // Tests are called directly
        testSimpleGetRequest();
        testGetRequestWithPath();
        // ... 103 total tests

        // Summary printed at end
        System.exit(testsFailed == 0 ? 0 : 1);
    }
}
```

### Writing New Tests

Follow this pattern:

```java
private static void testYourFeature() {
    // 1. Arrange - Set up test data
    RequestParser parser = new RequestParser();
    String request = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
    ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

    // 2. Act - Execute the code
    ParsingResult result = parser.parse(buffer);

    // 3. Assert - Verify results
    assertTrue("Request should be complete", result.isComplete());
    assertEquals("Method should be GET", HttpMethod.GET, result.getRequest().getMethod());
}
```

### Available Assertions

- `assertTrue(message, condition)` - Check boolean is true
- `assertFalse(message, condition)` - Check boolean is false
- `assertEquals(message, expected, actual)` - Check equality
- `assertNotNull(message, object)` - Check not null
- `assertNull(message, object)` - Check is null
- `assertContains(message, string, substring)` - Check contains
- `fail(message)` - Force failure

### Current Test Coverage

- ✅ 103 tests total
- ✅ Request line parsing (11 tests)
- ✅ Header parsing including multi-line (13 tests)
- ✅ Body parsing fixed-length (3 tests)
- ✅ Chunked transfer encoding (6 tests)
- ✅ Incremental parsing (4 tests)
- ✅ Edge cases (4 tests)

### Pros of Current Approach

1. **Zero Dependencies** - Pure Java, no external libraries
2. **Simple** - Easy to understand and maintain
3. **Fast** - Compiles and runs quickly
4. **Portable** - Works anywhere Java works
5. **Educational** - Learn how testing frameworks work internally

### Cons Compared to JUnit

1. **No Test Isolation** - Tests share state (all static)
2. **No Parameterization** - Can't easily run same test with different inputs
3. **Limited IDE Integration** - Can't click to run individual tests
4. **Manual Assertions** - Have to write assertion methods yourself
5. **No Test Grouping** - All tests in one flat list
6. **No Setup/Teardown Hooks** - No automatic before/after each test

---

## Migrating to JUnit (Optional)

If this project grows larger, consider migrating to JUnit 5.

### Why Migrate?

When you need:
- Test isolation (fresh objects per test)
- Parameterized tests (same test, different data)
- Better IDE integration (IntelliJ, VS Code, etc.)
- CI/CD integration (GitHub Actions, Jenkins, etc.)
- Test grouping and filtering
- More powerful assertions

### How to Migrate

1. **Add JUnit dependency** (Maven example):
```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.0</version>
    <scope>test</scope>
</dependency>
```

2. **Convert test methods**:
```java
// Before (custom)
private static void testSimpleGet() {
    RequestParser parser = new RequestParser();
    // ...
    assertTrue("message", condition);
}

// After (JUnit)
@Test
void testSimpleGet() {
    RequestParser parser = new RequestParser();
    // ...
    assertTrue(condition, "message");
}
```

3. **Add setup/teardown**:
```java
private RequestParser parser;

@BeforeEach
void setUp() {
    parser = new RequestParser(); // Fresh for each test
}
```

## Best Practices

### 1. Test Organization

Group related tests together:
```java
// Request Line Tests
testSimpleGetRequest();
testGetRequestWithPath();
testGetRequestWithQueryString();

// Header Tests
testSingleHeader();
testMultipleHeaders();
```

### 2. Descriptive Test Names

Use names that describe what's being tested:
```java
✅ testMultiLineHeader()
✅ testChunkedEncodingSingleChunk()
❌ test1()
❌ testParser()
```

### 3. Arrange-Act-Assert Pattern

Structure each test clearly:
```java
// Arrange - Set up test data
RequestParser parser = new RequestParser();
String request = "...";

// Act - Execute the code
ParsingResult result = parser.parse(buffer);

// Assert - Verify results
assertTrue("...", result.isComplete());
```

### 4. Test One Thing Per Test

Each test should verify one specific behavior:
```java
✅ testSimpleGetRequest() - Tests basic GET parsing
✅ testPathTraversalDetection() - Tests security feature
❌ testEverything() - Tests multiple unrelated things
```

### 5. Clear Assertion Messages

Messages should describe what went wrong:
```java
assertTrue("Request should be complete", result.isComplete());
assertEquals("Method should be GET", HttpMethod.GET, actual);
```

---

## Common Testing Patterns

### Testing Error Cases

```java
private static void testInvalidMethod() {
    RequestParser parser = new RequestParser();
    String request = "INVALID / HTTP/1.1\r\nHost: localhost\r\n\r\n";
    ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

    ParsingResult result = parser.parse(buffer);

    assertTrue("Should result in error", result.isError());
    assertContains("Error message should mention method",
                  result.getErrorMessage(), "Invalid HTTP method");
}
```

### Testing Incremental Data

```java
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
```

### Testing with Different Inputs

```java
private static void testAllHttpMethods() {
    String[] methods = {"GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH"};

    for (String method : methods) {
        RequestParser parser = new RequestParser();
        String request = method + " / HTTP/1.1\r\nHost: localhost\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        ParsingResult result = parser.parse(buffer);

        assertTrue("Request with " + method + " should be complete", result.isComplete());
    }
}
```

---

## Conclusion

**For this project:** Your custom testing approach is **perfect**. It's:
- ✅ Simple and understandable
- ✅ Zero dependencies
- ✅ Comprehensive (103 tests!)
- ✅ Fast to run
- ✅ Educational

**When to switch to JUnit:**
- Project grows beyond 5,000 lines
- Multiple developers working on it
- Need CI/CD integration
- Want better IDE features
- Tests become hard to maintain

**Your tests are production-quality!** 🎉