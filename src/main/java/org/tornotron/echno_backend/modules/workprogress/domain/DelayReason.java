package org.tornotron.echno_backend.modules.workprogress.domain;

/**
 * Why an activity is behind its planned finish. A fixed list so delays can be counted by cause
 * across projects (the data a later automatic rescheduler learns from), with free-text notes on
 * the record for the detail.
 */
public enum DelayReason {
    WEATHER,
    MATERIAL,
    LABOUR,
    EQUIPMENT,
    DESIGN_CHANGE,
    CLIENT,
    SUBCONTRACTOR,
    OTHER
}
