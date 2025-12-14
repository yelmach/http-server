package http;

public class ParsingResult {
    public enum Status {
        NEED_MORE_DATA, COMPLETE, ERROR
    }

    private final Status status;
    private final HttpRequest request;
    private final String errorMessage;

    private ParsingResult(Status status, HttpRequest request, String errorMessage) {
        this.status = status;
        this.request = request;
        this.errorMessage = errorMessage;
    }

    public static ParsingResult needMoreData() {
        return new ParsingResult(Status.NEED_MORE_DATA, null, null);
    }

    public static ParsingResult complete(HttpRequest request) {
        return new ParsingResult(Status.COMPLETE, request, null);
    }

    public static ParsingResult error(String errorMessage) {
        return new ParsingResult(Status.ERROR, null, errorMessage);
    }

    public Status getStatus() {
        return status;
    }

    public HttpRequest getRequest() {
        return request;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isNeedMoreData() {
        return status == Status.NEED_MORE_DATA;
    }

    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    public boolean isError() {
        return status == Status.ERROR;
    }
}