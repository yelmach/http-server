package http;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class RequestBuffer {
    private ByteBuffer buffer;
    private static final int INITIAL_CAPACITY = 4096;
    private static final int MAX_HEADER_CAPACITY = 16 * 1024;

    public RequestBuffer() {
        this.buffer = ByteBuffer.allocate(INITIAL_CAPACITY);
    }

    public void append(ByteBuffer source) {
        ensureCapacity(source.remaining());
        buffer.put(source);
    }

    public int indexOf(String sequence) {
        byte[] needle = sequence.getBytes(StandardCharsets.US_ASCII);
        int limit = buffer.position();

        for (int i = 0; i <= limit - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (buffer.get(i + j) != needle[j]) {
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

    public String readLine(int crlfPos) {
        byte[] line = new byte[crlfPos];
        buffer.flip();
        buffer.get(line);
        buffer.get(); // Skip \r
        buffer.get(); // Skip \n
        buffer.compact();
        return new String(line, StandardCharsets.UTF_8);
    }

    public byte[] readBytes(int count) {
        byte[] data = new byte[count];
        buffer.flip();
        buffer.get(data);
        buffer.compact();
        return data;
    }

    public void skip(int count) {
        buffer.flip();
        buffer.position(buffer.position() + count);
        buffer.compact();
    }

    public int remaining() {
        return buffer.position();
    }

    public int position() {
        return buffer.position();
    }

    private void ensureCapacity(int additional) {
        if (buffer.remaining() < additional) {
            int newCapacity = Math.min(buffer.capacity() * 2, MAX_HEADER_CAPACITY);
            if (newCapacity <= buffer.capacity()) {
                throw new BufferOverflowException();
            }
            ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
            buffer.flip();
            newBuffer.put(buffer);
            buffer = newBuffer;
        }
    }
}
