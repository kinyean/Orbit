package space.orbit.backend.stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the raw catalog WebSocket endpoint (Decision 10/13). Plain text
 * frames — NOT STOMP, NOT SockJS — so a browser {@code new WebSocket(...)} (or
 * Cesium consumer) speaks to it directly.
 *
 * <p>The WS-level origin gate (separate from the servlet CORS bean). Spring's
 * default rejects any non-same-origin handshake with 403 — which blocks the
 * browser when the frontend is served from a different host/port than the
 * backend (always true here: the browser's Origin is the frontend's IP:5174,
 * the backend is reached via the proxy). {@code setAllowedOrigins("*")} is the
 * documented allow-all for dev; lock it down via {@code orbit.stream.allowed-origins}
 * in Phase 10.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CatalogStreamHandler catalogStreamHandler;
    private final String[] allowedOrigins;

    public WebSocketConfig(
            CatalogStreamHandler catalogStreamHandler,
            @Value("${orbit.stream.allowed-origins:*}") String allowedOrigins) {
        this.catalogStreamHandler = catalogStreamHandler;
        this.allowedOrigins = allowedOrigins.split(",");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(catalogStreamHandler, StreamContract.CATALOG_ENDPOINT)
                .setAllowedOrigins(allowedOrigins);
    }
}
