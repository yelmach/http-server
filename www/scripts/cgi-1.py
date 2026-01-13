#!/usr/bin/env python3

import os
import html

print("""
<!DOCTYPE html>
<html>
<head>
    <title>CGI Environment Dump</title>
    <style>
        body { font-family: monospace; background: #f4f4f4; }
        table { border-collapse: collapse; width: 100%; }
        th, td { border: 1px solid #999; padding: 6px; }
        th { background: #ddd; }
        tr:nth-child(even) { background: #eee; }
    </style>
</head>
<body>
<h1>CGI Environment Variables</h1>
<table>
<tr><th>Variable</th><th>Value</th></tr>
""")

for key, value in sorted(os.environ.items()):
    print(
        f"<tr><td>{html.escape(key)}</td>"
        f"<td>{html.escape(value)}</td></tr>"
    )

print("""
</table>
</body>
</html>
""")
