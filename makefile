BUILD_DIR = build
LOG_DIR = logs

.PHONY: all build test run clean rebuild help

all: build

build:
	@mkdir -p $(BUILD_DIR)
	javac -d $(BUILD_DIR) -sourcepath src src/Main.java

test: build
	javac -cp $(BUILD_DIR) -d $(BUILD_DIR) -sourcepath src:test test/http/RequestParserTest.java
	java -cp $(BUILD_DIR) http.RequestParserTest

run: build
	@rm -rf $(LOG_DIR)/*
	java -cp $(BUILD_DIR) Main

clean:
	rm -rf $(BUILD_DIR) $(LOG_DIR)/*

rebuild: clean build

help:
	@echo "Available targets:"
	@echo "  build   - Compile the Java sources"
	@echo "  test    - Compile and run unit tests"
	@echo "  run     - Run the HTTP server"
	@echo "  clean   - Remove compiled files and logs"
	@echo "  rebuild - Clean and rebuild the project"
