#!/usr/bin/env python3

print("<!DOCTYPE html>")
print("<html><body>")
print("<h1>Huge CGI Response</h1>")

# ~5 MB of output
for i in range(1000000):
    print(f"<p>Line {i}: This is a large CGI response test.</p>")

print("</body></html>")
