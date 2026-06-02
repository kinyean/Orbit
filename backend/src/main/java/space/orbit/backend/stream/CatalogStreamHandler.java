package space.orbit.backend.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
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
 *
 * <p>Messages are <strong>gzip-compressed and sent as binary frames</strong>.
 * CZML is highly repetitive (~10x compressible), and an uncompressed multi-MB
 * frame cannot drain to a remote browser within the send-time limit (it works
 * over loopback but resets over a real network). The client inflates with the
 * native DecompressionStream. See docs/streaming-contract.md.
 */
@Component
public class CatalogStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CatalogStreamHandler.class);

    private static final int SEND_TIME_LIMIT_MS = 30_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 32 * 1024 * 1024;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile byte[] latestMessage;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession guarded =
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        sessions.add(guarded);
        log.debug("Catalog stream connected: {} ({} total)", session.getId(), sessions.size());

        byte[] warmStart = latestMessage;
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

    /** Compress once, cache as the warm-start frame, and fan out to all sessions. */
    public void broadcast(String message) {
        byte[] compressed = gzip(message);
        latestMessage = compressed;
        for (WebSocketSession session : sessions) {
            sendTo(session, compressed);
        }
    }

    public int connectionCount() {
        return sessions.size();
    }

    private static byte[] gzip(String message) {
        byte[] raw = message.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, raw.length / 8));
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        } catch (IOException e) {
            throw new IllegalStateException("gzip of catalog message failed", e);
        }
        return bos.toByteArray();
    }

    private void sendTo(WebSocketSession session, byte[] compressed) {
        if (!session.isOpen()) {
            sessions.remove(session);
            return;
        }
        try {
            session.sendMessage(new BinaryMessage(compressed));
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
