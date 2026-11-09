package org.tornotron.echno_backend.modules.inspections.service;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.inspections.api.ChecklistTemplatePort;
import org.tornotron.echno_backend.modules.inspections.domain.ChecklistTemplate;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateItemRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateRequest;
import org.tornotron.echno_backend.modules.inspections.repositories.OrgTradeRepository;

/**
 * The checklist template port, over the services the trade and template endpoints call. Each item is
 * checked against the template endpoint's own request constraints first, so an item the endpoint would
 * refuse is refused here with the same limits.
 */
@Service
public class ChecklistTemplatePortAdapter implements ChecklistTemplatePort {

    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    /** Stands in for the template's name and trade while its items are validated. */
    private static final String PLACEHOLDER = "-";

    private final ChecklistTemplateService templates;
    private final TradeService trades;
    private final OrgTradeRepository orgTrades;
    private final Validator validator = VALIDATORS.getValidator();

    public ChecklistTemplatePortAdapter(ChecklistTemplateService templates, TradeService trades,
                                        OrgTradeRepository orgTrades) {
        this.templates = templates;
        this.trades = trades;
        this.orgTrades = orgTrades;
    }

    @Override
    public List<TradeRef> activeTrades() {
        return trades.listOrgTrades(false).stream()
                .map(t -> new TradeRef(t.id(), t.code(), t.name(), t.catalogueCode(), t.active()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TradeRef> findTrade(UUID tradeId) {
        if (tradeId == null) {
            return Optional.empty();
        }
        return orgTrades.findByIdScoped(tradeId)
                .filter(t -> t.getOrganization().getId().equals(TenantContext.getCurrentOrgId()))
                .map(t -> new TradeRef(t.getId(), t.getCode(), t.getName(), t.getCatalogueCode(), t.isActive()));
    }

    @Override
    @Transactional
    public TemplateRef appendItems(UUID tradeId, String nameIfNew, List<TemplateItem> items) {
        List<ChecklistTemplateItemRequest> requests = new ArrayList<>();
        for (TemplateItem i : items == null ? List.<TemplateItem>of() : items) {
            requests.add(new ChecklistTemplateItemRequest(i.category(), i.checkPoint(), i.specification(),
                    i.expectedValue(), i.acceptanceCriterion(), i.tolerance(), i.photosRequired(), i.priority()));
        }
        validate(new ChecklistTemplateRequest(PLACEHOLDER, null, nameIfNew, null, null, null, null,
                requests.isEmpty() ? null : requests));
        ChecklistTemplateService.AppendedItems appended = templates.appendItems(tradeId, nameIfNew, requests);
        ChecklistTemplate t = appended.template();
        return new TemplateRef(t.getId(), t.getTradeRef().getId(), t.getName(), t.getVersion(), t.isActive(),
                appended.created(), appended.addedItemIds());
    }

    private void validate(ChecklistTemplateRequest req) {
        Set<ConstraintViolation<ChecklistTemplateRequest>> violations = validator.validate(req);
        if (!violations.isEmpty()) {
            throw new InvalidRequestException(violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; ")));
        }
    }
}
