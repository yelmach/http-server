#!/bin/bash

# Script to run integration tests
# Make sure the server is running before executing this script

echo "=========================================="
echo "HTTP Server Integration Test Runner"
echo "=========================================="
echo ""

# Check if server is running
if ! curl -s http://127.0.0.1:8080/ > /dev/null 2>&1; then
    echo "ERROR: Server is not running!"
    echo "Please start the server first with: make run"
    exit 1
fi

echo "Server is running. Starting integration tests..."
echo ""

# Compile integration tests
echo "Compiling integration tests..."
javac -cp build -d build test/integration/IntegrationTestSuite.java

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to compile integration tests"
    exit 1
fi

# Run integration tests
echo "Running integration tests..."
java -cp build integration.IntegrationTestSuite

exit $?
