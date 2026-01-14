package http;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MultipartParser {

    // Limit non-file fields to 64KB
    private static final int MAX_FIELD_SIZE = 64 * 1024;

    private final String boundary;
    private final int maxBodySize;

    private List<MultipartPart> multipartParts;
    private MultipartPart currentPart;
    private File currentPartTempFile;
    private FileOutputStream currentPartOutputStream;
    private ByteArrayOutputStream currentPartContent;
    private boolean parsingPartHeaders;
    private int bodyBytesRead;

    public MultipartParser(String boundary, int maxBodySize) {
        this.boundary = boundary;
        this.maxBodySize = maxBodySize;
        this.multipartParts = new ArrayList<>();
        this.parsingPartHeaders = true;
        this.bodyBytesRead = 0;
    }

    public ParsingResult parse(byte[] data, int startPos, HttpRequest httpRequest) {
        int position = startPos;

        while (position < data.length) {
            if (parsingPartHeaders) {
                int boundaryPos = findBoundary(data, position, boundary);

                if (boundaryPos == -1) {
                    return ParsingResult.needMoreData(position);
                }

                // Check for End Boundary (--boundary--)
                if (isEndBoundary(data, boundaryPos, boundary)) {
                    httpRequest.setMultipartParts(multipartParts);
                    int endBoundaryLength = ("--" + boundary + "--").length();
                    return ParsingResult.complete(httpRequest, boundaryPos + endBoundaryLength);
                }

                // Move past boundary + CRLF
                position = boundaryPos + boundary.length() + 2;

                if (position + 2 <= data.length && data[position] == '\r' && data[position + 1] == '\n') {
                    position += 2;
                }

                int partHeadersEnd = findHeadersEnd(data, position);
                if (partHeadersEnd == -1) {
                    return ParsingResult.needMoreData(position);
                }

                currentPart = parsePartHeaders(data, position, partHeadersEnd);
                if (currentPart == null)
                    return ParsingResult.error("Bad part headers");

                position = partHeadersEnd + 4; // Skip \r\n\r\n

                // Setup storage (Disk for files, RAM for small fields)
                if (currentPart.isFile()) {
                    try {
                        currentPartTempFile = File.createTempFile("http-part-", ".tmp");
                        currentPartOutputStream = new FileOutputStream(currentPartTempFile);
                        currentPart.setTempFile(currentPartTempFile);
                    } catch (IOException e) {
                        return ParsingResult.error("Temp file error: " + e.getMessage());
                    }
                } else {
                    currentPartContent = new ByteArrayOutputStream();
                }

                parsingPartHeaders = false;

            } else {
                // Reading Part Content
                int nextBoundaryPos = findBoundary(data, position, boundary);

                if (nextBoundaryPos == -1) {
                    // No boundary found, write everything to current part
                    int bytesToWrite = data.length - position;
                    if (bytesToWrite > 0) {
                        if (writeToPart(data, position, bytesToWrite)) {
                            // Write successful
                        } else {
                            return ParsingResult.error("Field size exceeded limit");
                        }

                        // Safety check on total size
                        bodyBytesRead += bytesToWrite;
                        if (bodyBytesRead > maxBodySize)
                            return ParsingResult.error("Total body size exceeded");
                    }
                    return ParsingResult.needMoreData(data.length);

                } else {
                    // Boundary found, finish this part
                    int contentEnd = nextBoundaryPos - 2; // Backtrack for CRLF
                    if (contentEnd < position)
                        contentEnd = position;

                    int bytesToWrite = contentEnd - position;
                    if (bytesToWrite > 0) {
                        if (!writeToPart(data, position, bytesToWrite)) {
                            return ParsingResult.error("Field size exceeded limit");
                        }
                    }

                    try {
                        if (currentPart.isFile()) {
                            currentPartOutputStream.close();
                        } else {
                            currentPart.setContent(currentPartContent.toByteArray());
                        }
                    } catch (IOException e) {
                        return ParsingResult.error("Close error");
                    }

                    multipartParts.add(currentPart);
                    currentPart = null;
                    currentPartOutputStream = null;
                    currentPartContent = null;

                    position = nextBoundaryPos; // Loop back to handle boundary
                    parsingPartHeaders = true;
                }
            }
        }
        return ParsingResult.needMoreData(position);
    }

    private boolean writeToPart(byte[] data, int offset, int length) {
        try {
            if (currentPart.isFile()) {
                currentPartOutputStream.write(data, offset, length);
            } else {
                // SECURITY CHECK: RAM DoS Protection
                if (currentPartContent.size() + length > MAX_FIELD_SIZE) {
                    return false;
                }
                currentPartContent.write(data, offset, length);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private int findBoundary(byte[] data, int startPos, String boundary) {
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);

        for (int i = startPos; i <= data.length - boundaryBytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < boundaryBytes.length; j++) {
                if (data[i + j] != boundaryBytes[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private boolean isEndBoundary(byte[] data, int pos, String boundary) {
        byte[] endBoundaryBytes = ("--" + boundary + "--").getBytes(StandardCharsets.UTF_8);

        if (pos + endBoundaryBytes.length > data.length) {
            return false;
        }

        for (int i = 0; i < endBoundaryBytes.length; i++) {
            if (data[pos + i] != endBoundaryBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private MultipartPart parsePartHeaders(byte[] data, int start, int end) {
        MultipartPart part = new MultipartPart();
        int position = start;

        while (position < end) {
            int lineEnd = findLineEnd(data, position);
            if (lineEnd == -1) {
                break;
            }

            String line = new String(data, position, lineEnd - position, StandardCharsets.UTF_8);

            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String headerName = line.substring(0, colonIndex).trim().toLowerCase();
                String headerValue = line.substring(colonIndex + 1).trim();

                if (headerName.equals("content-disposition")) {
                    String name = extractParameter(headerValue, "name");
                    String filename = extractParameter(headerValue, "filename");
                    part.setName(name);
                    part.setFilename(filename);
                } else if (headerName.equals("content-type")) {
                    part.setContentType(headerValue);
                }
            }

            position = lineEnd + 2;
        }

        return part;
    }

    private String extractParameter(String header, String paramName) {
        if (header == null) {
            return null;
        }

        String[] parts = header.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith(paramName + "=")) {
                String value = part.substring(paramName.length() + 1);
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

    private int findLineEnd(byte[] data, int startPos) {
        for (int i = startPos; i < data.length - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private int findHeadersEnd(byte[] data, int startPos) {
        for (int i = startPos; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    public void cleanup() {
        try {
            if (currentPartOutputStream != null) {
                currentPartOutputStream.close();
            }
        } catch (IOException e) {
            // Ignore
        }
        if (currentPartTempFile != null && currentPartTempFile.exists()) {
            currentPartTempFile.delete();
        }
        if (multipartParts != null) {
            for (MultipartPart part : multipartParts) {
                if (part.getTempFile() != null && part.getTempFile().exists()) {
                    part.getTempFile().delete();
                }
            }
        }
    }
}
