package org.tornotron.echno_backend.modules.toolboxtalks.domain;

/**
 * Where a toolbox talk is in its short life. A talk is drafted before or during the briefing
 * and recorded once, when the supervisor signs it off; a recorded talk is the safety record
 * and no longer changes.
 */
public enum ToolboxTalkStatus {
    DRAFT,
    RECORDED
}
