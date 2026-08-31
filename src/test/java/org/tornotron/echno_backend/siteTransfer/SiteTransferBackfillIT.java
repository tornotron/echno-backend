package org.tornotron.echno_backend.siteTransfer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backfill in changeset 086, run against a real CockroachDB over rows shaped the way the ones
 * already in the database are shaped.
 *
 * <p>This is the part of the change whose correctness rests on facts about live data rather than
 * on code, so it is worth a test that executes the migration's own SQL rather than a copy of it:
 * the statements are read out of the changelog file itself, so editing the changeset and leaving
 * this test passing is not possible.
 *
 * <p>What it pins is the direction of the correction. Every transfer that already exists wrote
 * both of its inventory legs at creation, so its stock reached the receiving site whatever its
 * status said. The documents are therefore made to agree with the ledger: the lines are received
 * in full, and a transfer left reading PENDING or PARTIALLY_TRANSFERRED is moved to COMPLETED with
 * an entry saying so. Doing it the other way round, rewriting the movements to match a document
 * that has since changed shape, would break the property every balance in the system rests on,
 * which is that it can be explained from the movements that produced it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SiteTransferBackfillIT extends AbstractIntegrationTest {

    private static final String CHANGELOG = "db/changelog/v4.0/086-site-transfer-two-step-receipt.xml";

    @PersistenceContext
    private EntityManager entityManager;

    private long orgId;
    private long projectA;
    private long projectB;
    private long materialId;

    /**
     * Reads the {@code <sql>} bodies out of one changeset, so the statements executed here are the
     * migration's own and not a paraphrase of them.
     */
    private static List<String> statementsOf(String changeSetId) throws Exception {
        try (InputStream in = SiteTransferBackfillIT.class.getClassLoader().getResourceAsStream(CHANGELOG)) {
            assertThat(in).as("changelog %s is on the classpath", CHANGELOG).isNotNull();
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
            NodeList changeSets = document.getElementsByTagName("changeSet");
            for (int i = 0; i < changeSets.getLength(); i++) {
                Element changeSet = (Element) changeSets.item(i);
                if (!changeSetId.equals(changeSet.getAttribute("id"))) {
                    continue;
                }
                List<String> statements = new ArrayList<>();
                NodeList children = changeSet.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    Node child = children.item(j);
                    if (child.getNodeType() == Node.ELEMENT_NODE && "sql".equals(child.getNodeName())) {
                        statements.add(child.getTextContent().trim().replaceAll(";\\s*$", ""));
                    }
                }
                return statements;
            }
        }
        throw new AssertionError("No changeset " + changeSetId + " in " + CHANGELOG);
    }

    private long insert(String sql) {
        return ((Number) entityManager.createNativeQuery(sql + " RETURNING id").getSingleResult()).longValue();
    }

    @BeforeEach
    void seedTheShapeTheLiveRowsHave() {
        orgId = insert("INSERT INTO organization (organization_name, organization_address, "
                + "organization_email, organization_phone) VALUES ('Backfill Co', 'Chennai', "
                + "'backfill@example.com', '0000000000')");
        projectA = insert("INSERT INTO project (project_name, organization_id) VALUES ('Sending site', " + orgId + ")");
        projectB = insert("INSERT INTO project (project_name, organization_id) VALUES ('Receiving site', " + orgId + ")");
        materialId = insert("INSERT INTO material (material_name, unit, organization_id) "
                + "VALUES ('TMT Bar 12mm', 'nos', " + orgId + ")");
    }

    private long transfer(String number, String status) {
        return insert("INSERT INTO site_transfer (transfer_number, status, sending_project_id, "
                + "receiving_project_id, organization_id) VALUES ('" + number + "', '" + status + "', "
                + projectA + ", " + projectB + ", " + orgId + ")");
    }

    private long line(long transferId, int sent) {
        return insert("INSERT INTO site_transfer_item (site_transfer_id, material_id, sent_quantity, "
                + "organization_id) VALUES (" + transferId + ", " + materialId + ", " + sent + ", " + orgId + ")");
    }

    private void run(String changeSetId) throws Exception {
        for (String statement : statementsOf(changeSetId)) {
            entityManager.createNativeQuery(statement).executeUpdate();
        }
        entityManager.flush();
        entityManager.clear();
    }

    private Object scalar(String sql) {
        return entityManager.createNativeQuery(sql).getSingleResult();
    }

    /**
     * The inbound leg was written for the full quantity, so the full quantity is what arrived.
     * Leaving these lines unreceived would say the material is on a lorry when the receiving site
     * has been drawing it down for weeks.
     */
    @Test
    void everyExistingLineIsBackfilledAsReceivedInFull() throws Exception {
        long transferId = transfer("TRF-BACKFILL-001", "PENDING");
        long lineId = line(transferId, 18);
        entityManager.flush();

        run("086-02-backfill-site-transfer-received-quantity");

        assertThat(((Number) scalar("SELECT received_quantity FROM site_transfer_item WHERE id = " + lineId)).intValue())
                .isEqualTo(18);
    }

    /** A receipt recorded under the new rules must not be overwritten by a re-run. */
    @Test
    void theBackfillLeavesALineThatHasAlreadyBeenReceivedAlone() throws Exception {
        long transferId = transfer("TRF-BACKFILL-002", "PARTIALLY_TRANSFERRED");
        long lineId = line(transferId, 10);
        entityManager.createNativeQuery(
                "UPDATE site_transfer_item SET received_quantity = 8 WHERE id = " + lineId).executeUpdate();
        entityManager.flush();

        run("086-02-backfill-site-transfer-received-quantity");

        assertThat(((Number) scalar("SELECT received_quantity FROM site_transfer_item WHERE id = " + lineId)).intValue())
                .isEqualTo(8);
    }

    /**
     * A transfer left reading PENDING would say its material is in transit while the ledger says
     * it arrived, and under the new rules a receipt could later be recorded against it and post
     * the stock a second time.
     */
    @Test
    void aTransferWhoseStockAlreadyMovedIsCorrectedToCompleted() throws Exception {
        long pending = transfer("TRF-BACKFILL-003", "PENDING");
        long partial = transfer("TRF-BACKFILL-004", "PARTIALLY_TRANSFERRED");
        entityManager.flush();

        run("086-04-complete-transfers-whose-stock-already-moved");

        assertThat(scalar("SELECT status FROM site_transfer WHERE id = " + pending)).isEqualTo("COMPLETED");
        assertThat(scalar("SELECT status FROM site_transfer WHERE id = " + partial)).isEqualTo("COMPLETED");
        // Both halves of the changeset have to cover the same rows. If the entries were written
        // for a narrower set than the update moves, a transfer would change status with nothing
        // in its history saying it ever did, which is the silent change the trail exists to stop.
        assertThat(scalar("SELECT from_status FROM status_transition WHERE entity_type = 'SITE_TRANSFER' "
                + "AND entity_id = " + pending)).isEqualTo("PENDING");
        assertThat(scalar("SELECT from_status FROM status_transition WHERE entity_type = 'SITE_TRANSFER' "
                + "AND entity_id = " + partial)).isEqualTo("PARTIALLY_TRANSFERRED");
    }

    /**
     * The correction is a real change of status that the migration made, so it is recorded as one.
     * Making it silently would leave rows whose history says their status never moved.
     */
    @Test
    void theCorrectionIsRecordedAsASystemTransitionSayingWhatItWasAndWhy() throws Exception {
        long transferId = transfer("TRF-BACKFILL-005", "PENDING");
        entityManager.flush();

        run("086-04-complete-transfers-whose-stock-already-moved");

        Object[] entry = (Object[]) scalar(
                "SELECT from_status, to_status, source, note, changed_by FROM status_transition "
                        + "WHERE entity_type = 'SITE_TRANSFER' AND entity_id = " + transferId);
        assertThat(entry[0]).isEqualTo("PENDING");
        assertThat(entry[1]).isEqualTo("COMPLETED");
        assertThat(entry[2]).isEqualTo("SYSTEM");
        assertThat((String) entry[3]).contains("already been posted");
        // Nobody decided it, and inventing an actor would be worse than leaving the column empty.
        assertThat(entry[4]).isNull();
    }

    /** A transfer that was already complete is not a transition and gets no entry. */
    @Test
    void aTransferThatWasAlreadyCompletedIsLeftUntouched() throws Exception {
        long transferId = transfer("TRF-BACKFILL-006", "COMPLETED");
        entityManager.flush();

        run("086-04-complete-transfers-whose-stock-already-moved");

        assertThat(((Number) scalar("SELECT count(*) FROM status_transition WHERE entity_type = 'SITE_TRANSFER' "
                + "AND entity_id = " + transferId)).longValue()).isZero();
    }

    /**
     * The baseline runs before the correction, so a reader sees the status the transfer was
     * observed to hold and then the entry that moved it, rather than a baseline of the corrected
     * value and no record that anything changed.
     */
    @Test
    void theBaselineNamesTheStatusHeldBeforeTheCorrection() throws Exception {
        long transferId = transfer("TRF-BACKFILL-007", "PENDING");
        entityManager.flush();

        run("086-03-seed-site-transfer-status-baseline");
        run("086-04-complete-transfers-whose-stock-already-moved");

        assertThat(scalar("SELECT to_status FROM status_transition WHERE entity_type = 'SITE_TRANSFER' "
                + "AND source = 'BASELINE' AND entity_id = " + transferId)).isEqualTo("PENDING");
        assertThat(scalar("SELECT status FROM site_transfer WHERE id = " + transferId)).isEqualTo("COMPLETED");
    }
}
