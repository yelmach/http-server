package http;

public interface IRequestHandler {
    void handle(HttpRequest request, HttpResponse response);
}
