package org.tornotron.echno_backend.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Objects;

/**
 * A reference to another domain entity woven into a message body as
 * {@code #[label](type:id)}. Stored as an element collection on {@link ChatMessage}: the
 * {@code entityType} is the web vocabulary ({@code task}/{@code issue}/{@code project}),
 * {@code entityId} the referenced row's id, and {@code label} the display text cached at the
 * time the mention was written so a later rename does not change what the message reads.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class ChatEntityMention {

    @Column(name = "entity_type", length = 30)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "label", length = 300)
    private String label;

    public ChatEntityMention(String entityType, Long entityId, String label) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.label = label;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ChatEntityMention that = (ChatEntityMention) o;
        return Objects.equals(entityType, that.entityType)
                && Objects.equals(entityId, that.entityId)
                && Objects.equals(label, that.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityType, entityId, label);
    }
}
