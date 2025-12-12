package http;

import java.nio.ByteBuffer;

public class RequestParser {
    private enum State {
        PARSE_REQUEST_LINE, PARSE_HEADERS, PARSE_BODY, COMPLETE, ERROR
    }

    private State state;
    private HttpRequest HttpRequest;

    public RequestParser() {
        this.state = State.PARSE_REQUEST_LINE;
        HttpRequest = new HttpRequest();
    }

    public State parse(ByteBuffer buffer) {
        return State.COMPLETE;
    }

}
