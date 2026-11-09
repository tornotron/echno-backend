package org.tornotron.echno_backend.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the whole of what the {@code store-keeper} role can reach, in one list.
 *
 * <p>The role exists because the Resources domain had nothing between plain organization membership
 * and {@code system-admin}, so the only way to let somebody book a delivery was to make them an
 * administrator of the organization. A role that narrow is only worth having while it stays narrow,
 * and the way it stops being narrow is one guard at a time, each addition reasonable on its own.
 *
 * <p>So this test compares the two directions at once. The expected table below is the whole grant,
 * and it is asserted to equal what the source actually carries, which means an endpoint added to the
 * role and an endpoint removed from it both fail here. A widening is then a deliberate edit to this
 * table with a reason attached, rather than a line in a diff nobody counts.
 *
 * <p>It reads the source rather than the loaded classes on purpose. Every entry is one
 * {@code @PreAuthorize} string sitting above one mapping, which is exactly what is being pinned, and
 * a text scan covers controllers this test does not have to know the names of: a guard added
 * anywhere under {@code src/main} shows up whether or not somebody remembered this file.
 */
class StoreKeeperRoleScopeTest {

    private static final Path SOURCE_ROOT = Path.of(
            System.getProperty("main.source.root", "src/main/java"));

    private static final String ROLE = "store-keeper";

    /**
     * Every endpoint the role may reach, as {@code CONTROLLER.method VERB path}.
     *
     * <p>The reads are here because a form that cannot be filled in is the same refusal one screen
     * earlier: a receipt names a purchase order, a vendor, a project, a storage location and a
     * material, and every one of those is a lookup the browser makes before the storekeeper can type
     * anything. Opening the write and leaving the lookups shut moves the 403 rather than removing
     * it, which is the failure this codebase has already made once.
     */
    private static final Set<String> EXPECTED = new TreeSet<>(Set.of(
            // Every endpoint the role reached is on the access matrix now, so no guard names it:
            // goods receipts, material consumption, storage locations, purchase orders, vendors and
            // indents, and since step 30's stores change, materials, site transfers, stock
            // adjustments and the ledger. The store keeper's starting role holds what it had, and
            // each area's equivalence test holds it there (StoresAccessEquivalenceIT for the last
            // four). Approving, rejecting or deleting a stock adjustment, and creating, editing or
            // deleting a material, stay off the role: AccessRoleTemplateSeedTest checks the template
            // never gives it them. A guard that names the role again fails here.
    ));

    /**
     * One granted endpoint: the controller it sits on, the method, and the mapping above it.
     *
     * <p>The signature names the mapping as well as the method, because a method name alone is not
     * unique. {@code IndentControllerWeb} declares {@code getAllIndents} twice, once on
     * {@code @GetMapping("/all")} and once on the bare {@code @GetMapping}, and keying by name
     * collapsed the two into one entry: the set comparison then held with one of the pair
     * ungranted, which is the failure this test exists to catch. Ninety-nine guarded mappings must
     * produce ninety-nine signatures.
     */
    private record Granted(String controller, String method, String verb, String path) {
        String signature() {
            String endpoint = path.isEmpty() ? verb : verb + " " + path;
            return controller + "." + method + " " + endpoint;
        }
    }

    private static final Pattern PRE_AUTHORIZE = Pattern.compile("^\\s*@PreAuthorize\\((.*)\\)\\s*$");
    private static final Pattern MAPPING = Pattern.compile(
            "^\\s*@(Get|Post|Put|Patch|Delete)Mapping\\b\\s*(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\")?");
    private static final Pattern METHOD = Pattern.compile("^\\s*(?:public|protected)\\s+.*?\\b(\\w+)\\s*\\(.*");

    private List<Granted> grantedEndpoints() throws IOException {
        List<Granted> granted = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                granted.addAll(grantedIn(file));
            }
        }
        return granted;
    }

    private List<Granted> grantedIn(Path file) throws IOException {
        String controller = file.getFileName().toString().replace(".java", "");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Granted> granted = new ArrayList<>();

        boolean carriesRole = false;
        String verb = null;
        String path = null;
        for (String line : lines) {
            Matcher pre = PRE_AUTHORIZE.matcher(line);
            if (pre.matches()) {
                carriesRole = pre.group(1).contains("'" + ROLE + "'");
                continue;
            }
            Matcher map = MAPPING.matcher(line);
            if (map.find()) {
                verb = map.group(1).toUpperCase();
                path = map.group(2) == null ? "" : map.group(2);
                continue;
            }
            Matcher method = METHOD.matcher(line);
            if (method.matches()) {
                if (carriesRole) {
                    granted.add(new Granted(controller, method.group(1),
                            verb == null ? "?" : verb, path == null ? "" : path));
                }
                carriesRole = false;
                verb = null;
                path = null;
            }
        }
        return granted;
    }

    @Test
    void theRoleReachesExactlyTheEndpointsTheStoresWorkflowNeeds() throws IOException {
        List<Granted> granted = grantedEndpoints();
        Set<String> actual = new TreeSet<>();
        for (Granted g : granted) {
            actual.add(g.signature());
        }

        // The comparison below is a set comparison, so it is only as good as the key. Two guarded
        // mappings sharing a signature would collapse into one entry and the set could then hold
        // with one of the pair ungranted. Keying by mapping as well as method name is what keeps
        // them apart, and this is the assertion that says so rather than assuming it.
        assertThat(actual)
                .as("one signature per guarded mapping. A collapse here means the key is not "
                        + "unique and the comparison below can no longer see a removal")
                .hasSize(granted.size());

        assertThat(actual)
                .as("endpoints guarded on the '%s' role. Adding one is a widening of the role and "
                        + "belongs in the table in this test with a reason; removing one is a "
                        + "capability the storekeeper workflow silently lost", ROLE)
                .isEqualTo(EXPECTED);
    }

    @Test
    void theRoleNeverApprovesRejectsOrDeletesAnything() throws IOException {
        // The storekeeper counts the shelf and writes down what they found. Approving the count is
        // what posts the balance, and it is meant to be somebody else reading the document. The
        // SelfApprovalPolicy already refuses a caller approving their own, but it is consulted one
        // request too late to help if the role holds both halves: two storekeepers would simply
        // approve each other's, and the control would be gone with the policy still passing.
        List<String> forbidden = new ArrayList<>();
        for (Granted g : grantedEndpoints()) {
            String method = g.method().toLowerCase();
            if (method.startsWith("approve") || method.startsWith("reject")
                    || method.startsWith("delete") || "DELETE".equals(g.verb())) {
                forbidden.add(g.signature());
            }
        }

        assertThat(forbidden)
                .as("endpoints on the '%s' role that approve, reject or delete", ROLE)
                .isEmpty();
    }
}
