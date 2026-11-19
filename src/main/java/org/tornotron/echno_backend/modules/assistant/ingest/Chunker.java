package org.tornotron.echno_backend.modules.assistant.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.AssistantProperties;

/**
 * Splits extracted document pages into citable chunks with character offsets, target sizes,
 * and overlap.
 *
 * <p>Splits first by page, then by paragraph boundaries. Each chunk carries a SHA-256 hash of its
 * text so that a re-ingested document can skip unchanged chunks.
 */
@Component
@AssistantModuleEnabled
public class Chunker {

    private final AssistantProperties properties;
    private final Integer overrideTargetSize;
    private final Integer overrideOverlap;

    public Chunker(AssistantProperties properties) {
        this.properties = properties;
        this.overrideTargetSize = null;
        this.overrideOverlap = null;
    }

    public Chunker(int targetChunkSize, int chunkOverlap) {
        this.properties = null;
        this.overrideTargetSize = targetChunkSize;
        this.overrideOverlap = chunkOverlap;
    }

    /**
     * Chunks an extracted document according to configured target size and overlap.
     */
    public List<DocChunk> chunk(ExtractedDocument document) {
        int targetSize = overrideTargetSize != null ? overrideTargetSize
                : (properties != null ? properties.getIngest().getTargetChunkSize() : 800);
        int overlap = overrideOverlap != null ? overrideOverlap
                : (properties != null ? properties.getIngest().getChunkOverlap() : 100);
        return chunk(document, targetSize, overlap);
    }

    public List<DocChunk> chunk(ExtractedDocument document, int targetSize, int overlap) {
        if (document == null || document.pages() == null || document.pages().isEmpty()) {
            return List.of();
        }

        List<DocChunk> chunks = new ArrayList<>();
        int globalChunkIndex = 0;

        for (ExtractedPage page : document.pages()) {
            String pageText = page.text();
            if (pageText == null || pageText.isBlank()) {
                continue;
            }

            int pageLen = pageText.length();
            int currentStart = 0;

            while (currentStart < pageLen) {
                // Determine ideal chunk end
                int targetEnd = Math.min(currentStart + targetSize, pageLen);

                if (targetEnd < pageLen) {
                    // Try to break at a paragraph boundary (\n\n) or newline (\n) or sentence (. )
                    int breakPos = findNaturalBreak(pageText, currentStart, targetEnd);
                    if (breakPos > currentStart) {
                        targetEnd = breakPos;
                    }
                }

                String chunkText = pageText.substring(currentStart, targetEnd).trim();
                if (!chunkText.isEmpty()) {
                    String hash = sha256(chunkText);
                    chunks.add(new DocChunk(
                            globalChunkIndex++,
                            page.pageNo(),
                            currentStart,
                            targetEnd,
                            chunkText,
                            hash
                    ));
                }

                if (targetEnd >= pageLen) {
                    break;
                }

                // Advance with overlap
                int nextStart = targetEnd - overlap;
                if (nextStart <= currentStart) {
                    nextStart = targetEnd; // ensure forward progress
                }
                currentStart = nextStart;
            }
        }

        return chunks;
    }

    private static int findNaturalBreak(String text, int start, int maxEnd) {
        // Look backwards from maxEnd down to start + maxEnd/2 for a paragraph or sentence break
        int minAcceptableBreak = start + (maxEnd - start) / 2;

        // 1. Look for double newline (paragraph)
        int paraBreak = text.lastIndexOf("\n\n", maxEnd);
        if (paraBreak >= minAcceptableBreak) {
            return paraBreak + 2;
        }

        // 2. Look for sentence boundary (". ")
        int sentenceBreak = text.lastIndexOf(". ", maxEnd);
        if (sentenceBreak >= minAcceptableBreak) {
            return sentenceBreak + 2;
        }

        // 3. Look for single newline
        int newlineBreak = text.lastIndexOf('\n', maxEnd);
        if (newlineBreak >= minAcceptableBreak) {
            return newlineBreak + 1;
        }

        // 4. Look for whitespace
        int spaceBreak = text.lastIndexOf(' ', maxEnd);
        if (spaceBreak >= minAcceptableBreak) {
            return spaceBreak + 1;
        }

        return maxEnd;
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : encoded) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing from Java runtime", e);
        }
    }
}
