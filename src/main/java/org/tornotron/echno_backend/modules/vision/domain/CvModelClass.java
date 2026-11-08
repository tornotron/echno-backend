package org.tornotron.echno_backend.modules.vision.domain;

import java.math.BigDecimal;

/**
 * One class a model version outputs, stored in the version's {@code classes} column.
 *
 * @param key       a key of the class taxonomy
 * @param shape     {@code BOX} or {@code POLYGON}, as the taxonomy has it
 * @param threshold the operating threshold in [0, 1] chosen for this class when the version was evaluated
 */
public record CvModelClass(String key, String shape, BigDecimal threshold) {}
