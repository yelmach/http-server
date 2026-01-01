package handlers;

import java.io.File;

import config.RouteConfig;
import http.HttpRequest;
import http.ResponseBuilder;

public class UploadHandler implements Handler {

    private RouteConfig route;
    private File resource;

    public UploadHandler(RouteConfig route, File resource) {
        this.route = route;
        this.resource = resource;
    }

    @Override
    public void handle(HttpRequest request, ResponseBuilder response) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'handle'");
    }

}
