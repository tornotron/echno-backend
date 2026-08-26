package org.tornotron.echno_backend.chat.realtime;

/**
 * Carries a chat event to every replica that might be holding one of its recipients' streams.
 *
 * <p>Two implementations, chosen by the same {@code echno.cache.provider} switch that already
 * selects the cache and the rate limiter, so real-time delivery has no separate control of its
 * own to get out of step: {@link RedisChatEventPublisher} when the deployment is multi-replica
 * and {@link LocalChatEventPublisher} when it is not.
 */
public interface ChatEventPublisher {

    /**
     * Publishes an event. Delivery is best effort: a recipient who is not connected, or whose
     * event is lost in transit, sees the change on their next fetch instead. The web client
     * keeps a slow poll for exactly this reason.
     */
    void publish(ChatEvent event);
}
