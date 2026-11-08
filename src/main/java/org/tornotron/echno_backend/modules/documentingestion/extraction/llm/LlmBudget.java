package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestOrgSettings;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestOrgSettingsRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.LlmCallRepository;

/**
 * The budget of design section 7.3, read from the ledger before each call: a package's spend against
 * its limit, and the organization's spend this calendar month (UTC) against its own. Each is held in
 * estimated dollars and in tokens. Dollars count only while the configured rates are above zero; the
 * token limit always holds, so a deployment that does not know its price still stops.
 */
@Component
public class LlmBudget {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final LlmCallRepository ledger;
    private final IngestPackageRepository packages;
    private final IngestOrgSettingsRepository settings;
    private final IngestLlmProperties properties;
    private final TransactionRetryTemplate retry;

    public LlmBudget(LlmCallRepository ledger, IngestPackageRepository packages, IngestOrgSettingsRepository settings,
                     IngestLlmProperties properties, TransactionRetryTemplate retry) {
        this.ledger = ledger;
        this.packages = packages;
        this.settings = settings;
        this.properties = properties;
        this.retry = retry;
    }

    /** Spend and limits of a package and of its organization's month. */
    public record Standing(long packageTokens, BigDecimal packageUsd, long packageCalls, long packageTokenLimit,
                           BigDecimal packageUsdLimit, long monthTokens, BigDecimal monthUsd, long monthCalls,
                           long monthTokenLimit, BigDecimal monthUsdLimit, boolean pricesKnown) {

        /** Why no further call fits, or empty. */
        public Optional<String> exceeded() {
            if (packageTokens >= packageTokenLimit || (pricesKnown && packageUsd.compareTo(packageUsdLimit) >= 0)) {
                return Optional.of("The package has used its AI reading budget. What is done is kept; an admin can "
                        + "raise the package's limit to go on.");
            }
            if (monthTokens >= monthTokenLimit || (pricesKnown && monthUsd.compareTo(monthUsdLimit) >= 0)) {
                return Optional.of("The organization has used this month's AI reading budget. What is done is kept; "
                        + "an admin can raise the monthly limit, or the job resumes next month.");
            }
            return Optional.empty();
        }
    }

    public Standing standing(Long organizationId, UUID packageId) {
        return retry.execute("DocumentIngestion.budget", () -> {
            LlmCallRepository.Spend pkg = ledger.spendForPackage(organizationId, packageId);
            LlmCallRepository.Spend month = ledger.spendSince(organizationId, monthStart());
            IngestPackage p = packages.findByIdScoped(packageId).orElse(null);
            IngestOrgSettings s = settings.findForOrganization(organizationId).orElse(null);
            long pkgTokenLimit = p != null && p.getLlmBudgetTokens() != null ? p.getLlmBudgetTokens()
                    : properties.getPackageBudgetTokens();
            BigDecimal pkgUsdLimit = p != null && p.getLlmBudgetUsd() != null ? p.getLlmBudgetUsd()
                    : properties.getPackageBudgetUsd();
            long monthTokenLimit = s != null && s.getMonthlyBudgetTokens() != null ? s.getMonthlyBudgetTokens()
                    : properties.getMonthlyBudgetTokens();
            BigDecimal monthUsdLimit = s != null && s.getMonthlyBudgetUsd() != null ? s.getMonthlyBudgetUsd()
                    : properties.getMonthlyBudgetUsd();
            return new Standing(nz(pkg.getTokens()), nz(pkg.getCostUsd()), nz(pkg.getCalls()), pkgTokenLimit,
                    pkgUsdLimit, nz(month.getTokens()), nz(month.getCostUsd()), nz(month.getCalls()),
                    monthTokenLimit, monthUsdLimit, pricesKnown());
        });
    }

    /** Why no further call fits the budget, or empty. */
    public Optional<String> exceeded(Long organizationId, UUID packageId) {
        return standing(organizationId, packageId).exceeded();
    }

    /** The estimated cost of a call, or null when the rates are zero or the tokens were not reported. */
    public BigDecimal cost(Integer promptTokens, Integer completionTokens) {
        if (!pricesKnown() || (promptTokens == null && completionTokens == null)) {
            return null;
        }
        BigDecimal in = properties.getInputUsdPerMillion().multiply(BigDecimal.valueOf(promptTokens == null ? 0 : promptTokens));
        BigDecimal out = properties.getOutputUsdPerMillion()
                .multiply(BigDecimal.valueOf(completionTokens == null ? 0 : completionTokens));
        return in.add(out).divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    boolean pricesKnown() {
        return properties.getInputUsdPerMillion().signum() > 0 || properties.getOutputUsdPerMillion().signum() > 0;
    }

    static LocalDateTime monthStart() {
        return LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay();
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
