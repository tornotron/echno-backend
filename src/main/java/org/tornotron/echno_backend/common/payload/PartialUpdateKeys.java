package org.tornotron.echno_backend.common.payload;

import org.slf4j.Logger;

import java.util.Set;

/**
 * What a partial-update endpoint does with a key it has no field for.
 *
 * <p>These endpoints take their body as a {@code Map<String, Object>} and apply it with a
 * {@code switch} over the keys, because a partial update has to tell a field left out from a field
 * sent as an explicit null and a bean cannot. The cost is that an unrecognised key is not an error:
 * it falls off the end of the switch, the caller is answered 200, and nothing changed. Changing an
 * issue's type through the product did nothing for months for exactly that reason, and the same
 * shape sits under every other update in this family.
 *
 * <p>So every one of them ends in a {@code default} branch that comes here. The key is logged at
 * WARN naming the entity and the row, which turns a silent drop into something a log search finds.
 *
 * <p><b>Why this warns rather than refusing.</b> A 400 would be the stronger answer and is the
 * wrong one here: the deployed web client puts keys in these payloads that the endpoints have no
 * field for and never had, starting with the {@code attachments: []} it sends on every multipart
 * update to distinguish "no upload" from "untouched". Refusing an unknown key would turn ordinary
 * edits into failures the moment this deployed. The keys that are known to arrive on every request
 * are named per service instead, so the warning stays worth reading rather than firing on the two
 * things already understood. What actually enforces the contract is the request-contract check in
 * echno-core, which runs in CI and fails on a name neither side has agreed to; this is the runtime
 * backstop for whatever reaches the endpoint another way.
 */
public final class PartialUpdateKeys {

    private PartialUpdateKeys() {
    }

    /**
     * Logs a key a partial update carried that the endpoint has no field for.
     *
     * @param log               The calling service's logger, so the warning carries its name.
     * @param entity            What is being updated, lower case, e.g. {@code "task"}.
     * @param id                The id of the row being updated; may be null on a create-like path.
     * @param key               The key that was dropped.
     * @param deliberatelyDropped Keys this endpoint is known to receive and drops on purpose. These
     *                          are not logged.
     */
    public static void reportUnknown(Logger log, String entity, Object id, String key,
                                     Set<String> deliberatelyDropped) {
        if (deliberatelyDropped.contains(key)) {
            return;
        }
        log.warn("Ignoring '{}' on the update of {} {}: this endpoint changes no such field, so the "
                        + "caller was told the update succeeded and nothing about that field "
                        + "changed. Either the client is sending the wrong name or the field "
                        + "belongs here and does not exist yet.", key, entity, id);
    }

    /**
     * Logs a key a partial update carried that the endpoint has no field for, where nothing is
     * dropped on purpose.
     *
     * @param log    The calling service's logger.
     * @param entity What is being updated, lower case.
     * @param id     The id of the row being updated.
     * @param key    The key that was dropped.
     */
    public static void reportUnknown(Logger log, String entity, Object id, String key) {
        reportUnknown(log, entity, id, key, Set.of());
    }
}
