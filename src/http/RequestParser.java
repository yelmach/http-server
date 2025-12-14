package http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class RequestParser {

    private enum State {
        PARSING_REQUEST_LINE,
        PARSING_HEADERS,
        PARSING_BODY_FIXED_LENGTH,
        PARSING_BODY_CHUNKED,
        PARSING_CHUNK_SIZE,
        PARSING_CHUNK_DATA,
        PARSING_CHUNK_TRAILER,
        COMPLETE,
        ERROR
    }

    private static final int MAX_REQUEST_LINE_LENGTH = 8 * 1024;
    private static final int MAX_HEADER_SIZE = 16 * 1024;
    private static final int MAX_URI_LENGTH = 4 * 1024;
    private final int maxBodySize;

    private State currentState;
    private HttpRequest HttpRequest;
    private ByteArrayOutputStream accumulationBuffer;

    private int headerBytesRead;
    private int bodyBytesRead;
    private int expectedBodyLength;

    private int currentChunkSize;
    private int currentChunkBytesRead;

    private String errorMessage;

    public RequestParser(int maxBodySize) {
        this.maxBodySize = maxBodySize;
        this.accumulationBuffer = new ByteArrayOutputStream();

        reset();
    }

    public RequestParser() {
        this(10 * 1024 * 1024);
    }

    public void reset() {
        currentState = State.PARSING_REQUEST_LINE;
        HttpRequest = new HttpRequest();
        accumulationBuffer.reset();
        headerBytesRead = 0;
        bodyBytesRead = 0;
        expectedBodyLength = 0;
        currentChunkSize = 0;
        currentChunkBytesRead = 0;
        errorMessage = null;
    }

    public ParsingResult parse(ByteBuffer buffer) {
        if (currentState == State.ERROR) {
            return ParsingResult.error(errorMessage);
        }

        byte[] newData = new byte[buffer.remaining()];
        buffer.get(newData);
        accumulationBuffer.write(newData, 0, newData.length);

        return parseAccumulatedData();
    }

    private ParsingResult parseAccumulatedData() {
        byte[] data = accumulationBuffer.toByteArray();
        int position = 0;

        while (position < data.length) {
            switch (currentState) {
                case PARSING_REQUEST_LINE:
                    break;
                case PARSING_HEADERS:
                    break;
                case PARSING_BODY_FIXED_LENGTH:
                    break;
                case PARSING_CHUNK_SIZE:
                    break;
                case PARSING_CHUNK_DATA:
                    break;
                case PARSING_CHUNK_TRAILER:
                    break;
                default:
                    break;
            }
        }

        return ParsingResult.complete(HttpRequest);
    }

}
