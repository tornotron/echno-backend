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
 *
 * <p>Its endpoints ask the access matrix: {@code progress} for the progress record and
 * {@code progress-bills} for the billing chain and its payments. The permissions below are the
 * manifest's own vocabulary and are not what the endpoints check.
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
