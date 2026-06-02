package space.orbit.backend.stream;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the raw catalog WebSocket endpoint (Decision 10/13). Plain text
 * frames — NOT STOMP, NOT SockJS — so a browser {@code new WebSocket(...)} (or
 * Cesium consumer) speaks to it directly.
 *
 * <p>{@code setAllowedOriginPatterns("*")} is the WS-level CORS gate (separate
 * from the servlet CORS bean). Permissive in dev because the browser reaches
 * this through the Vite proxy as a same-origin {@code /api/stream/catalog};
 * tightened to specific origins in Phase 10.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CatalogStreamHandler catalogStreamHandler;

    public WebSocketConfig(CatalogStreamHandler catalogStreamHandler) {
        this.catalogStreamHandler = catalogStreamHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(catalogStreamHandler, StreamContract.CATALOG_ENDPOINT)
                .setAllowedOriginPatterns("*");
    }
}
