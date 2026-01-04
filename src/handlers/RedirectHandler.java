package handlers;

import http.HttpRequest;
import http.HttpStatusCode;
import http.ResponseBuilder;

public class RedirectHandler implements Handler {

    private final String redirectTo;
    private final HttpStatusCode statusCode;

    public RedirectHandler(String redirectTo, HttpStatusCode statusCode) {
        this.redirectTo = redirectTo;
        this.statusCode = statusCode;
    }

    @Override
    public void handle(HttpRequest request, ResponseBuilder response) {

        response.status(this.statusCode)
                .header("Location", this.redirectTo)
                .contentType("text/html")
                .body("redirecting to " + this.redirectTo);
    }
}
