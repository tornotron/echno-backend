package org.tornotron.echno_backend.billing.gateway.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.gateway.BillingCustomer;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayException;
import org.tornotron.echno_backend.billing.gateway.GatewayPlanMapping;
import org.tornotron.echno_backend.billing.gateway.MandatePolicy;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.gateway.dto.ChangePlanCommand;
import org.tornotron.echno_backend.billing.gateway.dto.CreateSubscriptionCommand;
import org.tornotron.echno_backend.billing.gateway.dto.GatewayCustomer;
import org.tornotron.echno_backend.billing.gateway.dto.GatewayPlanRef;
import org.tornotron.echno_backend.billing.gateway.dto.GatewaySubscription;
import org.tornotron.echno_backend.billing.gateway.dto.InitiateMandateCommand;
import org.tornotron.echno_backend.billing.gateway.dto.MandateAuthorization;
import org.tornotron.echno_backend.billing.gateway.dto.NormalizedBillingEvent;
import org.tornotron.echno_backend.billing.gateway.dto.OrgBillingProfile;
import org.tornotron.echno_backend.billing.repositories.BillingCustomerRepository;
import org.tornotron.echno_backend.billing.repositories.GatewayPlanMappingRepository;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.organization.Organization;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link BillingGateway} over Razorpay's REST API. The only class that knows Razorpay's
 * endpoints, field names and status words; everything it returns is in the port's vocabulary.
 *
 * <p>Two things worth knowing about how Razorpay is driven here. A Razorpay plan is immutable,
 * so {@link #ensurePlan} keys the mapping table on the internal plan code and interval and
 * creates a fresh Razorpay plan (a new row, the old one marked not current) when none is
 * current; a price change on the internal plan therefore means a new Razorpay plan, never an
 * edit. And {@link #createSubscription} is also the mandate registration: Razorpay registers
 * the UPI Autopay / e-mandate / card standing instruction as part of the first authorization
 * on the {@code short_url} it returns, which is why {@link #initiateMandate} for a customer
 * with no subscription is not something Razorpay offers on its own and refuses here.
 *
 * <p>Every request that a subscription will debit against runs through {@link MandatePolicy}
 * first, so a plan above the RBI cap or a buyer with no pre-debit notification channel never
 * reaches the provider.
 */
@Slf4j
public class RazorpayBillingGateway implements BillingGateway {

    private final RazorpayRestClient client;
    private final RazorpayWebhookSignature signature;
    private final RazorpayEventParser mapper;
    private final MandatePolicy mandatePolicy;
    private final String currency;
    private final BillingCustomerRepository customers;
    private final GatewayPlanMappingRepository planMappings;
    private final PlanRepository plans;

    public RazorpayBillingGateway(RazorpayRestClient client,
                                  RazorpayWebhookSignature signature,
                                  RazorpayEventParser mapper,
                                  MandatePolicy mandatePolicy,
                                  String currency,
                                  BillingCustomerRepository customers,
                                  GatewayPlanMappingRepository planMappings,
                                  PlanRepository plans) {
        this.client = client;
        this.signature = signature;
        this.mapper = mapper;
        this.mandatePolicy = mandatePolicy;
        this.currency = currency;
        this.customers = customers;
        this.planMappings = planMappings;
        this.plans = plans;
    }

    @Override
    public ProviderId providerId() {
        return ProviderId.RAZORPAY;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public GatewayCustomer ensureCustomer(OrgBillingProfile org) {
        return customers.findByOrganizationIdAndProvider(org.organizationId(), ProviderId.RAZORPAY)
                .map(existing -> new GatewayCustomer(org.organizationId(), existing.getProviderCustomerId()))
                .orElseGet(() -> createCustomer(org));
    }

    private GatewayCustomer createCustomer(OrgBillingProfile org) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", org.legalName());
        putIfPresent(body, "email", org.email());
        putIfPresent(body, "contact", org.phone());
        putIfPresent(body, "gst_number", org.gstNumber());
        // "0" returns the existing customer when one with the same email and contact already
        // exists, instead of failing; the mapping row is what makes the org-to-customer link
        // unique on our side.
        body.put("fail_existing", "0");
        body.put("notes", Map.of(RazorpayEventParser.NOTE_ORGANIZATION_ID, String.valueOf(org.organizationId())));
        JsonNode created = client.post("/customers", body);
        String customerId = requireText(created, "id", "customer");
        Organization organization = new Organization();
        organization.setId(org.organizationId());
        customers.save(BillingCustomer.builder()
                .organization(organization)
                .provider(ProviderId.RAZORPAY)
                .providerCustomerId(customerId)
                .build());
        log.info("Razorpay customer {} created for organization {}", customerId, org.organizationId());
        return new GatewayCustomer(org.organizationId(), customerId);
    }

    @Override
    public GatewayPlanRef ensurePlan(Plan plan, BillingPeriod interval) {
        long amountPaise = MandatePolicy.cycleAmountPaise(plan, interval);
        Optional<GatewayPlanMapping> current = planMappings.findByProviderAndPlanCodeAndBillingIntervalAndIsCurrentTrue(
                ProviderId.RAZORPAY, plan.getCode(), interval);
        if (current.isPresent() && current.get().getAmountPaise() == amountPaise) {
            return new GatewayPlanRef(plan.getCode(), interval, current.get().getProviderPlanId());
        }
        current.ifPresent(stale -> {
            // The internal price moved. Razorpay plans are immutable, so the old one is retired
            // (subscriptions already on it keep their id) and a new one is created below.
            stale.setIsCurrent(false);
            planMappings.save(stale);
            log.info("Razorpay plan {} for {} ({}) retired: amount moved from {} to {} paise",
                    stale.getProviderPlanId(), plan.getCode(), interval, stale.getAmountPaise(), amountPaise);
        });
        return createPlan(plan, interval);
    }

    private GatewayPlanRef createPlan(Plan plan, BillingPeriod interval) {
        long amountPaise = MandatePolicy.cycleAmountPaise(plan, interval);
        if (amountPaise <= 0) {
            throw new BillingGatewayException(
                    "Plan '" + plan.getCode() + "' has no " + interval + " price to create a Razorpay plan from");
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", plan.getName());
        item.put("amount", amountPaise);
        item.put("currency", plan.getCurrency() == null ? currency : plan.getCurrency());
        putIfPresent(item, "description", plan.getDescription());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("period", interval == BillingPeriod.ANNUAL ? "yearly" : "monthly");
        body.put("interval", 1);
        body.put("item", item);
        body.put("notes", Map.of(RazorpayEventParser.NOTE_PLAN_CODE, plan.getCode()));
        JsonNode created = client.post("/plans", body);
        String planId = requireText(created, "id", "plan");
        planMappings.save(GatewayPlanMapping.builder()
                .planCode(plan.getCode())
                .provider(ProviderId.RAZORPAY)
                .billingInterval(interval)
                .providerPlanId(planId)
                .amountPaise(amountPaise)
                .isCurrent(true)
                .build());
        log.info("Razorpay plan {} created for plan {} ({}) at {} paise", planId, plan.getCode(), interval, amountPaise);
        return new GatewayPlanRef(plan.getCode(), interval, planId);
    }

    /**
     * Creates the Razorpay subscription. The organization's customer and the plan's Razorpay
     * plan must already exist ({@link #ensureCustomer}, {@link #ensurePlan}); the RBI rules are
     * checked against the cycle amount before anything is posted.
     */
    @Override
    public GatewaySubscription createSubscription(CreateSubscriptionCommand cmd) {
        BillingCustomer customer = customers.findByOrganizationIdAndProvider(cmd.organizationId(), ProviderId.RAZORPAY)
                .orElseThrow(() -> new BillingGatewayException(
                        "Organization " + cmd.organizationId() + " has no Razorpay customer; call ensureCustomer first"));
        GatewayPlanMapping planRef = planMappings.findByProviderAndPlanCodeAndBillingIntervalAndIsCurrentTrue(
                        ProviderId.RAZORPAY, cmd.planCode(), cmd.interval())
                .orElseThrow(() -> new BillingGatewayException(
                        "No current Razorpay plan for '" + cmd.planCode() + "' (" + cmd.interval() + "); call ensurePlan first"));
        Plan plan = plans.findByCodeWithFeatures(cmd.planCode())
                .orElseThrow(() -> new BillingGatewayException("Plan '" + cmd.planCode() + "' does not exist"));
        mandatePolicy.validateCreate(cmd, MandatePolicy.cycleAmountPaise(plan, cmd.interval()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan_id", planRef.getProviderPlanId());
        body.put("customer_id", customer.getProviderCustomerId());
        body.put("quantity", cmd.quantity());
        // Razorpay caps total_count at 100 for monthly and 10 for yearly cycles.
        body.put("total_count", cmd.totalCycles() != null ? cmd.totalCycles()
                : (cmd.interval() == BillingPeriod.ANNUAL ? 10 : 100));
        // The pre-debit notification is Razorpay's to send; 1 makes it send one for every charge.
        body.put("customer_notify", 1);
        if (cmd.trialDays() > 0) {
            body.put("start_at", Instant.now().plus(cmd.trialDays(), ChronoUnit.DAYS).getEpochSecond());
        }
        Map<String, String> notes = new HashMap<>();
        notes.put(RazorpayEventParser.NOTE_ORGANIZATION_ID, String.valueOf(cmd.organizationId()));
        notes.put(RazorpayEventParser.NOTE_PLAN_CODE, cmd.planCode());
        body.put("notes", notes);
        JsonNode created = client.post("/subscriptions", body);
        requireText(created, "id", "subscription");
        return mapper.toSubscription(created);
    }

    @Override
    public GatewaySubscription fetchSubscription(String providerSubscriptionId) {
        return mapper.toSubscription(client.get("/subscriptions/" + providerSubscriptionId));
    }

    @Override
    public void pauseSubscription(String providerSubscriptionId) {
        client.post("/subscriptions/" + providerSubscriptionId + "/pause", Map.of("pause_at", "now"));
    }

    @Override
    public void resumeSubscription(String providerSubscriptionId) {
        client.post("/subscriptions/" + providerSubscriptionId + "/resume", Map.of("resume_at", "now"));
    }

    @Override
    public void cancelSubscription(String providerSubscriptionId, boolean atCycleEnd) {
        client.post("/subscriptions/" + providerSubscriptionId + "/cancel",
                Map.of("cancel_at_cycle_end", atCycleEnd ? 1 : 0));
    }

    /**
     * Razorpay has no proration, so a plan change is scheduled at cycle end unless the caller
     * asks for it now, in which case Razorpay charges the new plan from the next cycle and the
     * remaining part of the current one is neither credited nor refunded. The projection
     * changes only when the resulting {@code subscription.updated} / {@code charged} arrives.
     */
    @Override
    public GatewaySubscription changePlan(ChangePlanCommand cmd) {
        GatewayPlanMapping target = planMappings.findByProviderAndPlanCodeAndBillingIntervalAndIsCurrentTrue(
                        ProviderId.RAZORPAY, cmd.newPlanCode(), cmd.interval())
                .orElseThrow(() -> new BillingGatewayException(
                        "No current Razorpay plan for '" + cmd.newPlanCode() + "' (" + cmd.interval() + "); call ensurePlan first"));
        mandatePolicy.validateChange(cmd, target.getAmountPaise());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan_id", target.getProviderPlanId());
        body.put("quantity", Math.max(1, cmd.quantity()));
        body.put("schedule_change_at", cmd.atCycleEnd() ? "cycle_end" : "now");
        body.put("customer_notify", 1);
        return mapper.toSubscription(client.patch("/subscriptions/" + cmd.providerSubscriptionId(), body));
    }

    @Override
    public MandateAuthorization initiateMandate(InitiateMandateCommand cmd) {
        mandatePolicy.validateMandate(cmd);
        // Razorpay registers the mandate inside the subscription's first authorization; the
        // hosted page is the subscription's short_url. A standalone registration would be a
        // Razorpay "registration link" (an order with a token), which nothing here needs yet.
        throw new BillingGatewayException(
                "Razorpay registers the mandate during subscription authorization; use the authUrl from createSubscription");
    }

    @Override
    public boolean verifySignature(byte[] rawBody, String signatureHeader) {
        return signature.verify(rawBody, signatureHeader);
    }

    @Override
    public List<NormalizedBillingEvent> parseEvents(byte[] rawBody) {
        return mapper.parse(rawBody);
    }

    /** Exposed for the runbook's smoke check and for tests that need to sign a fixture. */
    public RazorpayWebhookSignature signature() {
        return signature;
    }

    private static void putIfPresent(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    private static String requireText(JsonNode node, String field, String what) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            throw new BillingGatewayException("Razorpay returned a " + what + " without an id");
        }
        return value.asText();
    }
}
