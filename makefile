BUILD_DIR = build
LOG_DIR = logs
TEST_DIR = test

.PHONY: all build test test-integration test-manual test-all run clean rebuild help check-server stress-test

all: build

build:
	@mkdir -p $(BUILD_DIR)
	javac -d $(BUILD_DIR) -sourcepath src src/Main.java

test: build
	@echo "Running unit tests..."
	javac -cp $(BUILD_DIR) -d $(BUILD_DIR) -sourcepath src:test test/http/RequestParserTest.java
	java -cp $(BUILD_DIR) http.RequestParserTest

test-integration: build
	@echo "Running integration tests..."
	@echo "Note: Server must be running on port 8080"
	javac -cp $(BUILD_DIR) -d $(BUILD_DIR) -sourcepath src:test test/integration/IntegrationTestSuite.java
	java -cp $(BUILD_DIR) integration.IntegrationTestSuite

test-manual: check-server
	@echo "Running manual tests..."
	@chmod +x test/manual_tests.sh
	@./test/manual_tests.sh

test-all: test
	@echo ""
	@echo "Note: Run 'make test-integration' and 'make test-manual' with server running"

check-server:
	@echo "Checking if server is running..."
	@curl -s http://127.0.0.1:8080/ > /dev/null || (echo "ERROR: Server not running on port 8080" && exit 1)
	@echo "Server is running!"

stress-test: check-server
	@echo "Running stress test with siege..."
	@echo "Target: 99.5% availability"
	@which siege > /dev/null || (echo "ERROR: siege not installed. Install with: apt install siege or brew install siege" && exit 1)
	siege -b -c 50 -t 30S http://127.0.0.1:8080/

run: build
	@rm -rf $(LOG_DIR)/*
	@mkdir -p $(LOG_DIR)
	java -cp $(BUILD_DIR) Main

run-config: build
	@rm -rf $(LOG_DIR)/*
	@mkdir -p $(LOG_DIR)
	@if [ -z "$(CONFIG)" ]; then echo "Usage: make run-config CONFIG=path/to/config.json"; exit 1; fi
	java -cp $(BUILD_DIR) Main $(CONFIG)

clean:
	rm -rf $(BUILD_DIR) $(LOG_DIR)/*

rebuild: clean build

help:
	@echo "Available targets:"
	@echo "  build            - Compile the Java sources"
	@echo "  test             - Compile and run unit tests"
	@echo "  test-integration - Run integration tests (requires server running)"
	@echo "  test-manual      - Run manual test script (requires server running)"
	@echo "  test-all         - Run all tests"
	@echo "  run              - Run the HTTP server with default config"
	@echo "  run-config       - Run with custom config: make run-config CONFIG=path/to/config.json"
	@echo "  check-server     - Check if server is running"
	@echo "  stress-test      - Run siege stress test (requires server running)"
	@echo "  clean            - Remove compiled files and logs"
	@echo "  rebuild          - Clean and rebuild the project"
	@echo ""
	@echo "Testing workflow:"
	@echo "  1. make test                    # Unit tests"
	@echo "  2. make run &                   # Start server in background"
	@echo "  3. make test-integration        # Integration tests"
	@echo "  4. make test-manual             # Manual tests"
	@echo "  5. make stress-test             # Stress test with siege"
