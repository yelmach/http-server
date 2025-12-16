# Variables
JAVAC = javac
JAVA = java
SRC_DIR = src
TEST_DIR = test
BUILD_DIR = build
MAIN_CLASS = Main
TEST_CLASS = http.RequestParserTest
PORT = 8080

# Find all Java source files
SOURCES = $(shell find $(SRC_DIR) -name "*.java")

# Default target
.PHONY: all
all: build

# Compile the project
.PHONY: build
build:
	@echo "Compiling Java sources..."
	@mkdir -p $(BUILD_DIR)
	$(JAVAC) -d $(BUILD_DIR) -sourcepath $(SRC_DIR) $(SRC_DIR)/$(MAIN_CLASS).java
	@echo "Build complete!"

# Compile tests
.PHONY: build-test
build-test: build
	@echo "Compiling test sources..."
	$(JAVAC) -cp $(BUILD_DIR) -d $(BUILD_DIR) -sourcepath $(SRC_DIR):$(TEST_DIR) $(TEST_DIR)/http/RequestParserTest.java
	@echo "Test compilation complete!"

# Run tests
.PHONY: test
test: build-test
	@echo "Running unit tests..."
	@echo ""
	$(JAVA) -cp $(BUILD_DIR) $(TEST_CLASS)

# Run the server
.PHONY: run
run: build
	@echo "Starting HTTP server on port $(PORT)..."
	$(JAVA) -cp $(BUILD_DIR) $(MAIN_CLASS)

# Clean build artifacts
.PHONY: clean
clean:
	@echo "Cleaning build directory..."
	rm -rf $(BUILD_DIR)
	@echo "Clean complete!"

# Rebuild (clean + build)
.PHONY: rebuild
rebuild: clean build

# Help target
.PHONY: help
help:
	@echo "Available targets:"
	@echo "  make build      - Compile the Java sources"
	@echo "  make test       - Compile and run unit tests"
	@echo "  make run        - Compile and run the HTTP server"
	@echo "  make clean      - Remove compiled files"
	@echo "  make rebuild    - Clean and rebuild the project"
	@echo "  make build-test - Compile test sources"
	@echo "  make help       - Show this help message"
