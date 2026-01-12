package handlers;

import java.io.File;

import config.RouteConfig;
import http.HttpRequest;
import http.HttpStatusCode;
import http.ResponseBuilder;

public class DeleteHandler implements Handler {

    private RouteConfig route;
    private File resource;

    public DeleteHandler(RouteConfig route, File resource) {
        this.route = route;
        this.resource = resource;
    }

    @Override
    public void handle(HttpRequest request, ResponseBuilder response) {
        if (!resource.exists()) {
            response.status(HttpStatusCode.NOT_FOUND);
            return;
        }

        if (resource.isDirectory() || !resource.canWrite() ) {
            response.status(HttpStatusCode.FORBIDDEN);
            return;
        }

        boolean deleted = resource.delete();

        if (deleted) {
            response.status(HttpStatusCode.NO_CONTENT);
        } else {
            response.status(HttpStatusCode.INTERNAL_SERVER_ERROR);
        }
    }

}
