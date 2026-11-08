package org.tornotron.echno_backend.modules.workprogress;

import java.util.List;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;

/**
 * The manifest of the Work Progress module: progress inspections of schedule activities, and the
 * sub-contract billing chain that rests on them, running account and milestone bills from the
 * claim through measurement and certification to final approval
 * ({@code docs/specs/2026-09-28-work-progress-inspection.md},
 * {@code docs/specs/2026-10-04-ra-milestone-billing.md},
 * {@code docs/specs/2026-11-08-billing-in-inspection-and-payments.md}).
 *
 * <p>Paywalled on {@value #FEATURE_KEY}, which the module's feature seed grants on every plan, so
 * no tenant goes dark on release. The schedule it inspects is the core WBS; this module holds only
 * what is recorded against it. It publishes no nav entry: its screens sit on the project page's
 * WBS tab.
 */
@Component
public class WorkProgressModule implements EchnoModule {

    public static final String ID = "work-progress";
    public static final String FEATURE_KEY = "MODULE_WORK_PROGRESS";

    public static final String PERMISSION_READ = ID + ":read";
    public static final String PERMISSION_RECORD = ID + ":record";
    public static final String PERMISSION_BILLING_SETUP = ID + ":billing-setup";
    public static final String PERMISSION_BILL = ID + ":bill";
    public static final String PERMISSION_CERTIFY = ID + ":certify";
    public static final String PERMISSION_APPROVE = ID + ":approve";
    public static final String PERMISSION_PAY = ID + ":pay";

    // Any member of the tenant reads the record. The project team records it: the project
    // manager, the site engineer and the system admin, as the product owner asked that the project
    // team maintain the actuals. One string each, so the twin controllers cannot drift.
    public static final String READ_GUARD = "@orgSecurity.isMemberOfCurrentTenant()";
    public static final String RECORD_GUARD =
            "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager','site-engineer')";

    // Billing. The contract's commercial set-up (BOQ, deduction rules) and the two signatures on a
    // bill (certification and final approval) belong to the project manager and the admin. The
    // site team prepares a bill, records the joint measurement and keeps the milestone
    // requirements, as it does the progress record the bill rests on.
    public static final String BILLING_SETUP_GUARD =
            "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')";
    public static final String BILL_PREPARE_GUARD = RECORD_GUARD;
    public static final String BILL_VERIFY_GUARD = RECORD_GUARD;
    public static final String BILL_CERTIFY_GUARD =
            "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')";
    public static final String BILL_APPROVE_GUARD =
            "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')";

    // Paying an approved bill takes two signatures. The office raises a payment. The second
    // signature, and every step after it, belongs to the employee the project names as its payment
    // authorizer, which no organization role grants: PaymentAuthorizers checks it per project under
    // the payment's lock, so the endpoint gate is membership of the tenant. Naming that person is
    // managing the project, so it is the same pair of roles that edits a project. A system admin
    // can always name someone, which is what keeps an organization from locking itself out.
    public static final String BILL_PAY_REQUEST_GUARD =
            "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')";
    public static final String BILL_PAY_AUTHORIZE_GUARD = READ_GUARD;
    public static final String PAYMENT_AUTHORIZER_SETUP_GUARD =
            "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')";

    static final List<String> PERMISSIONS = List.of(PERMISSION_READ, PERMISSION_RECORD, PERMISSION_BILLING_SETUP,
            PERMISSION_BILL, PERMISSION_CERTIFY, PERMISSION_APPROVE, PERMISSION_PAY);

    private static final ModuleManifest MANIFEST = new ModuleManifest(
            ID,
            "Work Progress",
            "0.2.0",
            FEATURE_KEY,
            List.of(),
            PERMISSIONS,
            List.of(),
            false);

    @Override
    public ModuleManifest manifest() {
        return MANIFEST;
    }
}
