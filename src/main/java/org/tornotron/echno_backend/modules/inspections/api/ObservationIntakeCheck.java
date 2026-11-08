package org.tornotron.echno_backend.modules.inspections.api;

/**
 * A check another module applies to every machine finding before it is stored.
 *
 * <p>{@code inspections} never depends on the module that implements this: the intake path calls
 * every bean of this type, and there are none when that module is switched off. The check runs on a
 * new finding only; a repeat of an {@code externalRef} returns the stored row without it, so
 * retiring a model does not break a producer's retry.
 */
public interface ObservationIntakeCheck {

    /**
     * @throws IntakeRejectedException to refuse the finding
     */
    void check(IntakeCandidate candidate);

    /**
     * A class a reviewer gives a region through a MODIFY, on a replacement or an added region.
     *
     * @param where    where the region sits in the request, for the message
     * @param classKey the class the reviewer chose
     * @param shape    {@code BOX} or {@code POLYGON}
     * @throws IntakeRejectedException to refuse the edit
     */
    default void checkReviewerClass(String where, String classKey, String shape) {
    }
}
