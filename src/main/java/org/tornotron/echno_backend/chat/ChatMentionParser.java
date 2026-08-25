package org.tornotron.echno_backend.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the rich references out of a message body. Two token shapes are recognised, matching
 * the vocabulary the web composer writes:
 *
 * <ul>
 *   <li>{@code @[Name](employeeId)} - an employee mention, yielding the employee id.</li>
 *   <li>{@code #[label](type:id)} - an entity mention (task, issue or project), yielding the
 *       type, id and the label cached as written.</li>
 * </ul>
 *
 * Both lists are de-duplicated in order of first appearance so a body that names the same
 * target twice stores it once.
 */
public final class ChatMentionParser {

    /** {@code @[Name](employeeId)} - the captured group is the employee id. */
    private static final Pattern MENTION_PATTERN = Pattern.compile("@\\[[^\\]]*]\\((\\d+)\\)");

    /** {@code #[label](type:id)} - groups are label, type and id. */
    private static final Pattern ENTITY_MENTION_PATTERN =
            Pattern.compile("#\\[([^\\]]*)]\\((\\w+):(\\d+)\\)");

    private ChatMentionParser() {
    }

    /** Employee ids from {@code @[Name](id)} tokens, de-duplicated in order of first appearance. */
    public static List<Long> parseMentions(String content) {
        List<Long> ids = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return ids;
        }
        Matcher matcher = MENTION_PATTERN.matcher(content);
        while (matcher.find()) {
            Long id = Long.valueOf(matcher.group(1));
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Entity references from {@code #[label](type:id)} tokens, de-duplicated on (type, id) in
     * order of first appearance. The label is kept exactly as written.
     */
    public static List<ChatEntityMention> parseEntityMentions(String content) {
        List<ChatEntityMention> mentions = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return mentions;
        }
        Matcher matcher = ENTITY_MENTION_PATTERN.matcher(content);
        while (matcher.find()) {
            String label = matcher.group(1);
            String type = matcher.group(2);
            Long id = Long.valueOf(matcher.group(3));
            boolean seen = mentions.stream()
                    .anyMatch(m -> m.getEntityType().equals(type) && m.getEntityId().equals(id));
            if (!seen) {
                mentions.add(new ChatEntityMention(type, id, label));
            }
        }
        return mentions;
    }
}
