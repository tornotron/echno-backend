package org.tornotron.echno_backend.modules.documentingestion;

import java.util.UUID;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;

/**
 * The object-store layout shared with the worker by the contract. Every key begins with the
 * organization id and is composed here; a key is never accepted from a client.
 */
public final class DocumentIngestionStorageLayout {

    public static final String ATTACHMENT_ENTITY_TYPE = "DOC_INGEST_DOCUMENT";
    public static final String ATTACHMENT_FOLDER = "doc-ingest";
    public static final String META_FILE = "meta.json";
    public static final String MANIFEST_FILE = "manifest.json";
    public static final String TABLES_FILE = "tables.jsonl";

    private DocumentIngestionStorageLayout() {
    }

    public static AttachmentOwner ownerOf(UUID documentId) {
        return AttachmentOwner.of(ATTACHMENT_ENTITY_TYPE, documentId);
    }

    public static String packagePrefix(Long organizationId, UUID packageId) {
        return "doc-ingest/" + organizationId + "/" + packageId + "/";
    }

    public static String sourceKey(Long organizationId, UUID packageId, UUID documentId) {
        return packagePrefix(organizationId, packageId) + documentId + "/source";
    }

    public static String outputPrefix(Long organizationId, UUID packageId, UUID documentId) {
        return packagePrefix(organizationId, packageId) + documentId + "/output/";
    }

    public static String memberKey(Long organizationId, UUID packageId, String sha256) {
        return packagePrefix(organizationId, packageId) + "members/" + sha256;
    }
}
