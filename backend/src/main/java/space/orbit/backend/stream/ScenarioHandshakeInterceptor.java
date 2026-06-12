package space.orbit.backend.stream;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Captures the per-scenario stream's identity <em>at handshake time</em>, when
 * we are still on the servlet request thread and the security context is live.
 *
 * <p>Why here and not in the handler: WebSocket handler callbacks
 * ({@code afterConnectionEstablished}, message handling) run outside the servlet
 * filter window, and {@link space.orbit.backend.security.DevUserAuthenticationFilter}
 * clears {@code SecurityContextHolder} in a {@code finally} — so resolving the
 * caller on the WS thread would fail. We stash the principal name and the
 * path's trailing scenario-id segment in the session attributes; the handler
 * reads them off the WS thread.
 */
@Component
public class ScenarioHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        int slash = path.lastIndexOf('/');
        String idSegment = (slash >= 0 && slash < path.length() - 1) ? path.substring(slash + 1) : "";
        attributes.put(StreamContract.ATTR_SCENARIO_ID, idSegment);

        Principal principal = request.getPrincipal();
        attributes.put(StreamContract.ATTR_PRINCIPAL_NAME, principal != null ? principal.getName() : null);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                              WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
