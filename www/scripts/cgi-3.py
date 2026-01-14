#!/usr/bin/env python3

print("<!DOCTYPE html>")
print("<html><body>")
print("<h1>Huge CGI Response</h1>")

# ~5 MB of output
for i in range(1000):
    print(f"<p>Line {i}: This is a large CGI response test.</p>")

print("</body></html>")


# import os
# print("--- CGI Environment Variables ---")
# for key, value in os.environ.items():
#     print(f"{key}={value}")




# import sys
# # Read from stdin
# data = sys.stdin.read()
# print("Content-Type: text/plain\r\n\r\n")
# print(f"Received Body Length: {len(data)}")
# print("--- Body Content ---")
# print(data)




# import time

# print("Content-Type: text/plain\r\n\r\n")
# print("Start Sleeping...")
# time.sleep(3) # Sleeps for 3 seconds
# print("Done Sleeping!")




# print("Content-Type: text/plain\r\n\r\n")
# # Print 1MB of 'A'
# print("A" * 1024 * 1024)


# curl -v "http://localhost:8080/scripts/env_check.py?name=test&id=123"
# curl -v -X POST -d "Hello CGI World" "http://localhost:8080/scripts/echo_body.py"
# curl -v "http://localhost:8080/scripts/large_output.py" > output.txt
# siege -b -c 10 -t 10S "http://localhost:8080/scripts/sleep.py"