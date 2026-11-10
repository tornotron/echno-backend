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
    /** The owner type of an evidence file of the company profile. */
    public static final String EVIDENCE_ENTITY_TYPE = "DOC_INGEST_COMPANY_EVIDENCE";
    public static final String META_FILE = "meta.json";
    public static final String MANIFEST_FILE = "manifest.json";
    public static final String TABLES_FILE = "tables.jsonl";
    public static final String PAGES_FILE = "pages.jsonl";
    /** Worker 0.3.0: the title-block region of each PDF page. */
    public static final String TITLEBLOCK_FILE = "titleblock.jsonl";
    /** Worker 0.3.0: the block attributes and layouts of a DXF. */
    public static final String DXF_FILE = "dxf.json";

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

    /** The image of a PDF page, relative to the output prefix, as the worker writes it. */
    public static String pageImage(int page) {
        return "page-images/" + page + ".webp";
    }

    /** The title-block crop of a page, relative to the output prefix, as the worker writes it. */
    public static String titleBlockImage(int page) {
        return "titleblock/" + page + ".webp";
    }

    public static AttachmentOwner evidenceOwnerOf(UUID evidenceId) {
        return AttachmentOwner.of(EVIDENCE_ENTITY_TYPE, evidenceId);
    }

    /** An evidence file of the organization's company profile, outside every package. */
    public static String evidenceKey(Long organizationId, UUID evidenceId) {
        return "doc-ingest/" + organizationId + "/company-profile/" + evidenceId + "/source";
    }

    public static String memberKey(Long organizationId, UUID packageId, String sha256) {
        return packagePrefix(organizationId, packageId) + "members/" + sha256;
    }
}
