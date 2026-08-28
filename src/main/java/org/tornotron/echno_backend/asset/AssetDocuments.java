package org.tornotron.echno_backend.asset;

/**
 * The attachment entity type an asset's documents are filed under: purchase invoices,
 * warranties, insurance policies, registration papers, certifications and service records.
 *
 * <p>Nothing had to be built for the storage side of this. {@code attachment} is already keyed
 * on a free-form {@code entity_type} plus an {@code entity_id}, and the upload, presign,
 * register, list and delete endpoints all take both as path variables, so an asset's documents
 * upload through the endpoints that were already there. The storage folder those endpoints
 * derive is the part of the type before the first underscore, which is why the value follows
 * the {@code PROJECT_ATTACHMENTS} / {@code TASK_ATTACHMENTS} shape already in use: it lands
 * asset files under {@code asset/}.
 */
public final class AssetDocuments {

    /** Value of {@code attachment.entity_type} for a document filed against an asset. */
    public static final String ENTITY_TYPE = "ASSET_DOCUMENTS";

    private AssetDocuments() {
    }
}
