package handlers;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import config.RouteConfig;
import http.HttpRequest;
import http.HttpStatusCode;
import http.ResponseBuilder;

public class DirectoryHandler implements Handler {

    private RouteConfig route;
    private File resource;

    public DirectoryHandler(RouteConfig route, File resource) {
        this.route = route;
        this.resource = resource;
    }

    @Override
    public void handle(HttpRequest request, ResponseBuilder response) throws IOException {
        File[] files = resource.listFiles();

        if (files == null) {
            throw new IOException("Unable to read directory: " + resource.getAbsolutePath());
        }
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory())
                return -1;
            if (!a.isDirectory() && b.isDirectory())
                return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        String htmlPage = loadDirectTemplate(request, files);

        response.status(HttpStatusCode.OK)
                .contentType("text/html")
                .body(htmlPage);
    }

    private String loadDirectTemplate(HttpRequest request, File[] files) {
        StringBuilder html = new StringBuilder();

        // Header and style
        html.append("<!DOCTYPE html>\n<html>\n<head>\n");
        html.append("<title>").append(request.getPath()).append("</title>\n");
        html.append("<style>\n");
        html.append("body { font-family: Arial, sans-serif; margin: 40px; }\n");
        html.append("h1 { color: #333; }\n");
        html.append(".file-list { list-style: none; padding: 0; display: flex; flex-wrap: wrap; gap: 16px; }\n");
        html.append(
                ".file-item { border: 1px solid #eee; padding: 12px 16px; min-width: 200px; border-radius: 6px; }\n");
        html.append(".dir { font-weight: bold; }\n");
        html.append("a { text-decoration: none; color: #667eea; }\n");
        html.append("a:hover { text-decoration: underline; }\n");
        html.append("</style>\n</head>\n<body>\n");

        // Body
        html.append("<h1>Index of ").append(request.getPath()).append("</h1>\n");
        html.append("<ul class='file-list'>\n");

        // Parent directory
        if (!request.getPath().equals("/")) {
            html.append("<li class='file-item dir'><a href='../'>../</a></li>\n");
        }

        for (File file : files) {
            String name = file.getName();
            String href = request.getPath();
            if (!href.endsWith("/"))
                href += "/";
            href += name;

            html.append("<li class='file-item'>");

            if (file.isDirectory()) {
                html.append("<span class='dir'> <a href='")
                        .append(href).append("/'>")
                        .append(name).append("/</a></span>");
            } else {
                html.append(" <a href='")
                        .append(href).append("'>")
                        .append(name).append("</a>");
            }

            html.append("</li>\n");
        }

        html.append("</ul>\n</body>\n</html>");
        return html.toString();
    }
}
