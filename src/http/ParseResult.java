package http;

public class ParseResult {
    private enum Status { COMPLETE, NEED_MORE_DATA, ERROR }

    private final Status status;
    private final HttpRequest request;
    private final String errorMessage;

    private ParseResult(Status status, HttpRequest request, String errorMessage) {
        this.status = status;
        this.request = request;
        this.errorMessage = errorMessage;
    }

    public static ParseResult complete(HttpRequest req) {
        return new ParseResult(Status.COMPLETE, req, null);
    }

    public static ParseResult needMore() {
        return new ParseResult(Status.NEED_MORE_DATA, null, null);
    }

    public static ParseResult error(String msg) {
        return new ParseResult(Status.ERROR, null, msg);
    }

    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    public boolean needsMoreData() {
        return status == Status.NEED_MORE_DATA;
    }

    public boolean isError() {
        return status == Status.ERROR;
    }

    public HttpRequest getRequest() {
        return request;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
