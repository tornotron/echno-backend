package org.tornotron.echno_backend.modules.inspections.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;

/**
 * The organization's trades and its per-trade checklist templates, for another module. Document
 * ingestion matches a quality requirement's activity to a trade through {@link #activeTrades()}, and
 * copies chosen requirements into the trade's template through {@link #appendItems}.
 *
 * <p>Guards in this codebase sit on controllers, so a caller must first ask {@link #DEFINE_GUARD} for
 * the signed-in person before {@link #appendItems}, exactly as the template endpoints would, and ask the
 * module registry whether {@link #MODULE_ID} is on for the organization.
 */
public interface ChecklistTemplatePort {

    /** The module that owns trades and checklist templates, for a registry check. */
    String MODULE_ID = InspectionsModule.ID;

    /**
     * The guards on {@code POST} and {@code PUT /api/v1/checklist-templates/web} together: a copy may
     * create a trade's template or add a revision to it, so it asks for both.
     */
    String DEFINE_GUARD = "@access.can('inspection-checklists', 'create') "
            + "and @access.can('inspection-checklists', 'edit')";

    /**
     * The caller's organization's active trades, in their sort order. Read only, apart from topping up the
     * organization's copy of the trade catalogue, which the trade list endpoint also does.
     */
    List<TradeRef> activeTrades();

    /** One trade of the caller's organization, active or not, or empty. */
    Optional<TradeRef> findTrade(UUID tradeId);

    /**
     * Adds check points to the end of the trade's template in the caller's transaction and organization,
     * with the template endpoint's field limits, and bumps its revision. The template's existing check
     * points keep their rows. When the trade has no template yet, one is defined under {@code nameIfNew},
     * active, with these check points only.
     *
     * @return the template after the change, with the ids of the check points added, in order
     * @throws org.tornotron.echno_backend.common.exception.InvalidRequestException when an item breaks a
     *         limit of the endpoint, or the list is empty
     * @throws org.tornotron.echno_backend.common.exception.UnprocessableRequestException when the trade is
     *         not one of the caller's organization
     */
    TemplateRef appendItems(UUID tradeId, String nameIfNew, List<TemplateItem> items);

    /** A trade as another module may see it. */
    record TradeRef(UUID id, String code, String name, String catalogueCode, boolean active) {
    }

    /**
     * A template after {@link #appendItems}.
     *
     * @param created Whether this call defined the template
     * @param addedItemIds The check points added by this call, in order
     */
    record TemplateRef(UUID id, UUID tradeId, String name, int version, boolean active, boolean created,
                       List<UUID> addedItemIds) {
    }

    /** One check point, with the fields of the template endpoint's item. */
    record TemplateItem(String category, String checkPoint, String specification, String expectedValue,
                        String acceptanceCriterion, String tolerance, boolean photosRequired, String priority) {
    }
}
