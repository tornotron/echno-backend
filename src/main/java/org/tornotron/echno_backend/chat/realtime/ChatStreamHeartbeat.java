package org.tornotron.echno_backend.chat.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes a comment to every open chat stream on a fixed interval.
 *
 * <p>The interval is chosen against the two idle timeouts on the path, not picked round: nginx
 * closes a proxied response when the gap between reads exceeds {@code proxy_read_timeout} (90s
 * on the backend site) and Cloudflare closes an idle connection at 100s. Fifteen seconds sits
 * comfortably inside the shorter of the two with room for a slow tick, which is what allows
 * this feature to ship without raising a timeout anywhere between the browser and the pod.
 */
@Component
@RequiredArgsConstructor
public class ChatStreamHeartbeat {

    private final ChatStreamRegistry registry;

    @Scheduled(fixedRate = 15_000L)
    public void tick() {
        registry.heartbeat();
    }
}
