package session;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    private static final SessionManager instance = new SessionManager();
    // using ConcurrentHashMap for thread safety
    private final Map<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();

    private SessionManager() {}

    public static SessionManager getInstance() {
        return instance;
    }

    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new HashMap<>());
        return sessionId;
    }

    public Map<String, Object> getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public boolean isValid(String sessionId) {
        return sessions.containsKey(sessionId);
    }
}