# Variables
JAVAC = javac
JAVA = java
SRC_DIR = src
BUILD_DIR = build
MAIN_CLASS = Main
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
	@echo "  make build   - Compile the Java sources"
	@echo "  make run     - Compile and run the HTTP server"
	@echo "  make clean   - Remove compiled files"
	@echo "  make rebuild - Clean and rebuild the project"
	@echo "  make help    - Show this help message"
