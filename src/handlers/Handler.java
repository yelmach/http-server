package handlers;

import http.HttpRequest;
import http.ResponseBuilder;

public interface Handler {
    void handle(HttpRequest request, ResponseBuilder response) throws Exception;
}
