package org.tornotron.echno_backend.modules.documentingestion.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.documentingestion.domain.ClassMethod;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestDocument;
import org.tornotron.echno_backend.modules.documentingestion.domain.Segment;
import org.tornotron.echno_backend.modules.documentingestion.domain.SegmentStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.UnreadPage;
import org.tornotron.echno_backend.modules.documentingestion.extraction.classify.DeterministicClassifier.ProposedSegment;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestDocumentRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SegmentRepository;

/**
 * Writes classification's proposal: the document's class and its page segments, unconfirmed. A class a
 * person has confirmed is never overwritten, and proposing again replaces only unconfirmed segments.
 */
@Slf4j
@Service
public class ClassificationWriter {

    private final IngestDocumentRepository documents;
    private final SegmentRepository segments;

    public ClassificationWriter(IngestDocumentRepository documents, SegmentRepository segments) {
        this.documents = documents;
        this.segments = segments;
    }

    @Transactional
    public void propose(UUID documentId, DocClass docClass, ClassMethod method, String reason,
                        List<ProposedSegment> proposed, List<UnreadPage> unread) {
        IngestDocument doc = documents.findByIdScoped(documentId).orElse(null);
        if (doc == null) {
            return;
        }
        if (doc.getClassConfirmedAt() != null) {
            log.info("Document {} was classified by a person already; the proposal is not written", documentId);
            return;
        }
        doc.setDocClass(docClass == null ? null : docClass.name());
        doc.setClassMethod(method);
        doc.setClassReason(cut(reason));
        doc.setClassSetBy(null);
        doc.setUnreadPages(new ArrayList<>(unread));
        documents.save(doc);

        List<Segment> old = segments.findPageSegments(documentId).stream().filter(s -> !s.isConfirmed()).toList();
        segments.deleteAll(old);
        segments.flush();
        for (ProposedSegment p : proposed) {
            if (p.pageFrom() == null) {
                continue;
            }
            Segment s = new Segment();
            s.setOrganization(doc.getOrganization());
            s.setDocumentId(documentId);
            s.setDocClass(p.docClass().name());
            s.setPageFrom(p.pageFrom());
            s.setPageTo(p.pageTo());
            s.setStatus(SegmentStatus.PROPOSED);
            s.setMethod(method);
            s.setReason(cut(p.reason()));
            s.setUnreadPages(unread.stream()
                    .filter(u -> u.page() >= p.pageFrom() && (p.pageTo() == null || u.page() <= p.pageTo())).toList());
            segments.save(s);
        }
    }

    private static String cut(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300);
    }
}
