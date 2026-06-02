package space.orbit.backend.stream;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Raw WebSocket handler for the shared catalog feed (Decision 13). Holds the
 * connected sessions and the latest catalog message; {@link #broadcast} both
 * caches that message (for warm-starting new connections) and fans it out.
 *
 * <p>Each session is wrapped in a {@link ConcurrentWebSocketSessionDecorator}
 * because a raw {@code WebSocketSession} is not safe for concurrent sends — a
 * scheduled broadcast and a connect-time warm-start can otherwise collide on
 * the same session.
 */
@Component
public class CatalogStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CatalogStreamHandler.class);

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 16 * 1024 * 1024;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile String latestMessage;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession guarded =
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        sessions.add(guarded);
        log.debug("Catalog stream connected: {} ({} total)", session.getId(), sessions.size());

        String warmStart = latestMessage;
        if (warmStart != null) {
            sendTo(guarded, warmStart);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // The stored session is the decorator; remove by identity match on the underlying id.
        sessions.removeIf(s -> s.getId().equals(session.getId()));
        log.debug("Catalog stream closed: {} ({} remain)", session.getId(), sessions.size());
    }

    /** Cache as the warm-start message and fan out to all open sessions. */
    public void broadcast(String message) {
        latestMessage = message;
        for (WebSocketSession session : sessions) {
            sendTo(session, message);
        }
    }

    public int connectionCount() {
        return sessions.size();
    }

    private void sendTo(WebSocketSession session, String message) {
        if (!session.isOpen()) {
            sessions.remove(session);
            return;
        }
        try {
            session.sendMessage(new TextMessage(message));
        } catch (IOException | RuntimeException e) {
            // One dead/slow client must not abort the broadcast to everyone else.
            log.debug("Dropping catalog session {} after send failure: {}", session.getId(), e.toString());
            sessions.remove(session);
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {
                // already gone
            }
        }
    }
}
