#!/bin/bash

# Manual Test Script for HTTP Server
# Run this script while the server is running to perform manual tests

BASE_URL="http://127.0.0.1:8080"
COLORS=true

# Color codes
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_test() {
    echo -e "\n${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
}

print_pass() {
    echo -e "${GREEN}[PASS]${NC} $1"
}

print_fail() {
    echo -e "${RED}[FAIL]${NC} $1"
}

print_info() {
    echo -e "${YELLOW}[INFO]${NC} $1"
}

# Test 1: Basic GET request
print_test "Test 1: Basic GET Request"
response=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/")
if [ "$response" -eq 200 ]; then
    print_pass "GET / returned 200 OK"
else
    print_fail "GET / returned $response (expected 200)"
fi

# Test 2: GET non-existent file (404)
print_test "Test 2: 404 Not Found"
response=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/nonexistent.html")
if [ "$response" -eq 404 ]; then
    print_pass "GET /nonexistent.html returned 404"
else
    print_fail "GET /nonexistent.html returned $response (expected 404)"
fi

# Test 3: Directory listing
print_test "Test 3: Directory Listing"
response=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/images/")
if [ "$response" -eq 200 ] || [ "$response" -eq 403 ]; then
    print_pass "GET /images/ returned $response"
    if [ "$response" -eq 200 ]; then
        print_info "Directory listing is enabled"
    else
        print_info "Directory listing is disabled (403)"
    fi
else
    print_fail "GET /images/ returned $response"
fi

# Test 4: POST file upload
print_test "Test 4: POST File Upload"
echo "Test file content" > /tmp/test_upload.txt
response=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: text/plain" --data-binary "@/tmp/test_upload.txt" "$BASE_URL/upload/test_$(date +%s).txt")
if [ "$response" -eq 201 ] || [ "$response" -eq 200 ]; then
    print_pass "POST upload returned $response"
else
    print_fail "POST upload returned $response (expected 201 or 200)"
fi
rm -f /tmp/test_upload.txt

# Test 5: DELETE request
print_test "Test 5: DELETE Request"
response=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE_URL/")
if [ "$response" -ge 200 ] && [ "$response" -lt 500 ]; then
    print_pass "DELETE returned $response"
else
    print_fail "DELETE returned $response"
fi

# Test 6: Method not allowed
print_test "Test 6: Method Not Allowed (405)"
response=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/images/")
if [ "$response" -eq 405 ]; then
    print_pass "POST to /images/ returned 405"
else
    print_fail "POST to /images/ returned $response (expected 405)"
fi

# Test 7: Redirect
print_test "Test 7: HTTP Redirect"
response=$(curl -s -o /dev/null -w "%{http_code}" -L "$BASE_URL/old-page")
location=$(curl -s -I "$BASE_URL/old-page" | grep -i "Location:" | awk '{print $2}' | tr -d '\r')
if [ "$response" -eq 301 ] || [ "$response" -eq 302 ]; then
    print_pass "Redirect returned $response"
    print_info "Location: $location"
else
    print_fail "Redirect returned $response (expected 301 or 302)"
fi

# Test 8: Large body (should be rejected if exceeds limit)
print_test "Test 8: Body Size Limit"
print_info "Creating 200MB file (this may take a moment)..."
dd if=/dev/zero of=/tmp/large_file.bin bs=1M count=200 2>/dev/null
response=$(curl -s -o /dev/null -w "%{http_code}" -X POST --data-binary "@/tmp/large_file.bin" "$BASE_URL/upload/large.bin" --max-time 10)
if [ "$response" -eq 413 ] || [ -z "$response" ]; then
    print_pass "Large body rejected (413 or connection closed)"
else
    print_fail "Large body returned $response (expected 413)"
fi
rm -f /tmp/large_file.bin

# Test 9: Chunked encoding
print_test "Test 9: Chunked Transfer Encoding"
response=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Transfer-Encoding: chunked" --data "chunked data test" "$BASE_URL/upload/chunked.txt")
if [ "$response" -eq 201 ] || [ "$response" -eq 200 ]; then
    print_pass "Chunked encoding accepted ($response)"
else
    print_fail "Chunked encoding returned $response"
fi

# Test 10: CGI execution
print_test "Test 10: CGI Script Execution"
response=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/cgi-1")
if [ "$response" -eq 200 ]; then
    print_pass "CGI script executed successfully"
    content=$(curl -s "$BASE_URL/cgi-1" | head -n 20)
    print_info "CGI output preview:"
    echo "$content" | head -n 5
else
    print_fail "CGI script returned $response (expected 200)"
fi

# Test 11: Sessions and Cookies
print_test "Test 11: Sessions and Cookies"
cookie_jar="/tmp/cookie_jar_$$"
response=$(curl -s -o /dev/null -w "%{http_code}" -c "$cookie_jar" "$BASE_URL/session")
if [ "$response" -eq 200 ]; then
    if [ -f "$cookie_jar" ] && grep -q "SESSIONID" "$cookie_jar"; then
        print_pass "Session created and cookie set"

        # Make second request with cookie
        response2=$(curl -s -o /dev/null -w "%{http_code}" -b "$cookie_jar" "$BASE_URL/session")
        if [ "$response2" -eq 200 ]; then
            print_pass "Session persisted across requests"
        else
            print_fail "Session not persisted"
        fi
    else
        print_fail "Cookie not set properly"
    fi
else
    print_fail "Session endpoint returned $response (expected 200)"
fi
rm -f "$cookie_jar"

# Test 12: Keep-Alive
print_test "Test 12: Keep-Alive Connection"
headers=$(curl -s -I "$BASE_URL/")
if echo "$headers" | grep -qi "Connection.*keep-alive"; then
    print_pass "Keep-Alive header present"
else
    print_info "Keep-Alive not explicitly set (may be default for HTTP/1.1)"
fi

# Test 13: Concurrent requests
print_test "Test 13: Concurrent Requests"
print_info "Sending 20 concurrent requests..."
for i in {1..20}; do
    curl -s -o /dev/null -w "%{http_code}\n" "$BASE_URL/" &
done > /tmp/concurrent_results_$$
wait

success_count=$(grep "200" /tmp/concurrent_results_$$ | wc -l)
if [ "$success_count" -ge 18 ]; then
    print_pass "$success_count/20 concurrent requests succeeded"
else
    print_fail "Only $success_count/20 concurrent requests succeeded"
fi
rm -f /tmp/concurrent_results_$$

# Test 14: Malformed request
print_test "Test 14: Malformed Request Handling"
print_info "Sending malformed HTTP request..."
(echo -e "INVALID HTTP REQUEST\r\n\r\n"; sleep 1) | nc localhost 8080 > /dev/null 2>&1
if [ $? -ne 0 ]; then
    print_pass "Server closed connection for malformed request"
else
    print_info "Server handled malformed request"
fi

# Test 15: Virtual Hosts
print_test "Test 15: Virtual Host Resolution"
response=$(curl -s -o /dev/null -w "%{http_code}" -H "Host: localhost" "$BASE_URL/")
if [ "$response" -eq 200 ]; then
    print_pass "Virtual host 'localhost' resolved correctly"
else
    print_fail "Virtual host returned $response"
fi

# Test 16: Custom error pages
print_test "Test 16: Custom Error Pages"
error_content=$(curl -s "$BASE_URL/trigger-404")
if echo "$error_content" | grep -qi "html"; then
    print_pass "Custom error page is HTML"
else
    print_fail "Error page is not HTML"
fi

# Test 17: Request headers
print_test "Test 17: Request/Response Headers"
headers=$(curl -s -I "$BASE_URL/")
if echo "$headers" | grep -qi "HTTP/1.1"; then
    print_pass "HTTP/1.1 response"
fi
if echo "$headers" | grep -qi "Content-Type"; then
    print_pass "Content-Type header present"
fi
if echo "$headers" | grep -qi "Content-Length"; then
    print_pass "Content-Length header present"
fi
if echo "$headers" | grep -qi "Date"; then
    print_pass "Date header present"
fi

# Test 18: File corruption check
print_test "Test 18: File Upload/Download Integrity"
# Create test file with random data
dd if=/dev/urandom of=/tmp/test_integrity.bin bs=1K count=100 2>/dev/null
original_md5=$(md5sum /tmp/test_integrity.bin | awk '{print $1}')

# Upload file
upload_response=$(curl -s -o /dev/null -w "%{http_code}" -X POST --data-binary "@/tmp/test_integrity.bin" "$BASE_URL/upload/integrity_test.bin")
if [ "$upload_response" -eq 201 ] || [ "$upload_response" -eq 200 ]; then
    # Download file
    curl -s "$BASE_URL/upload/integrity_test.bin" -o /tmp/test_integrity_downloaded.bin
    downloaded_md5=$(md5sum /tmp/test_integrity_downloaded.bin 2>/dev/null | awk '{print $1}')

    if [ "$original_md5" == "$downloaded_md5" ]; then
        print_pass "File integrity maintained (MD5 match)"
    else
        print_fail "File corrupted during upload/download"
        print_info "Original MD5:   $original_md5"
        print_info "Downloaded MD5: $downloaded_md5"
    fi
else
    print_fail "Upload failed with status $upload_response"
fi
rm -f /tmp/test_integrity.bin /tmp/test_integrity_downloaded.bin

# Summary
print_test "Test Summary"
print_info "All manual tests completed!"
print_info "Check the output above for any failures"

echo -e "\n${BLUE}========================================${NC}"
echo -e "${BLUE}To run stress test with siege:${NC}"
echo -e "${YELLOW}siege -b -c 50 -t 30S $BASE_URL/${NC}"
echo -e "${BLUE}========================================${NC}"
