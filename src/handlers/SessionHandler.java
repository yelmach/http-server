package handlers;

import http.HttpRequest;
import http.HttpStatusCode;
import http.ResponseBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import session.Cookie;
import session.SessionManager;

public class SessionHandler implements Handler {

    @Override
    public void handle(HttpRequest request, ResponseBuilder response) {
        SessionManager sm = SessionManager.getInstance();
        String sessionId = request.getCookie("JSESSIONID");

        String body;

        // 1. Check if session exists
        if (sessionId != null && sm.isValid(sessionId)) {
            Map<String, Object> data = sm.getSession(sessionId);
            int views = (int) data.getOrDefault("views", 0) + 1;
            data.put("views", views);

            body = "<html><body><h1>Session Found!</h1><p>Views: " + views + "</p></body></html>";
            response.status(HttpStatusCode.OK).body(body.getBytes(StandardCharsets.UTF_8));
        } else {
            // 2. Create new session
            sessionId = sm.createSession();
            sm.getSession(sessionId).put("views", 1);

            body = "<html><body><h1>New Session Created!</h1><p>Views: 1</p></body></html>";
            response.status(HttpStatusCode.OK).body(body.getBytes(StandardCharsets.UTF_8));

            // 3. Set the cookie
            response.header("Set-Cookie", new Cookie("JSESSIONID", sessionId).toString());
        }

        response.header("Content-Type", "text/html");
        response.header("Content-Length", String.valueOf(body.length()));
    }
}
