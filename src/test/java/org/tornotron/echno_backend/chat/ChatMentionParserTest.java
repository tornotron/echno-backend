package org.tornotron.echno_backend.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChatMentionParser}: the regex extraction of employee and entity
 * mentions from a message body, independent of the database.
 */
class ChatMentionParserTest {

    @Test
    void parseMentions_extractsEmployeeIdsInOrderWithoutDuplicates() {
        String body = "Ping @[Asha](12) and @[Ravi](7), and @[Asha](12) again";

        List<Long> ids = ChatMentionParser.parseMentions(body);

        assertThat(ids).containsExactly(12L, 7L);
    }

    @Test
    void parseMentions_returnsEmptyWhenNoTokensOrNull() {
        assertThat(ChatMentionParser.parseMentions("plain text, no mentions")).isEmpty();
        assertThat(ChatMentionParser.parseMentions(null)).isEmpty();
        assertThat(ChatMentionParser.parseMentions("  ")).isEmpty();
    }

    @Test
    void parseEntityMentions_extractsTypeIdAndLabel() {
        String body = "See #[Pour slab C](task:42) blocking #[Crack in beam](issue:9)";

        List<ChatEntityMention> mentions = ChatMentionParser.parseEntityMentions(body);

        assertThat(mentions).hasSize(2);
        assertThat(mentions.get(0).getEntityType()).isEqualTo("task");
        assertThat(mentions.get(0).getEntityId()).isEqualTo(42L);
        assertThat(mentions.get(0).getLabel()).isEqualTo("Pour slab C");
        assertThat(mentions.get(1).getEntityType()).isEqualTo("issue");
        assertThat(mentions.get(1).getEntityId()).isEqualTo(9L);
    }

    @Test
    void parseEntityMentions_deduplicatesOnTypeAndId() {
        String body = "#[Project Alpha](project:3) ... #[Alpha again](project:3)";

        List<ChatEntityMention> mentions = ChatMentionParser.parseEntityMentions(body);

        assertThat(mentions).hasSize(1);
        assertThat(mentions.get(0).getEntityType()).isEqualTo("project");
        assertThat(mentions.get(0).getEntityId()).isEqualTo(3L);
    }

    @Test
    void parsers_keepEmployeeAndEntityMentionsSeparate() {
        String body = "@[Asha](12) look at #[Pour slab C](task:42)";

        assertThat(ChatMentionParser.parseMentions(body)).containsExactly(12L);
        assertThat(ChatMentionParser.parseEntityMentions(body))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getEntityType()).isEqualTo("task");
                    assertThat(m.getEntityId()).isEqualTo(42L);
                });
    }
}
