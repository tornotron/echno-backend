package org.tornotron.echno_backend.inspection.pdf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;

import java.util.Base64;
import java.util.Optional;

/**
 * Turns a stored photo reference into something a PDF can draw.
 *
 * <p>The photo is read out of object storage and embedded in the document as a
 * {@code data:} URI. The alternative, printing the reference as an {@code <img
 * src>} and letting the renderer fetch it, does not work and would not be safe if
 * it did: the bucket is private, so a plain URL is a 403, and openhtmltopdf's
 * default user agent opens a URL stream with no timeout, which would hand a
 * request thread an unbounded outbound fetch driven by a string out of the
 * database. Reading through {@link FileStorageService} means the only thing that
 * can ever be fetched is a key in our own bucket.
 *
 * <p>Two limits bound the cost, both configurable:
 *
 * <ul>
 *   <li>{@code echno.inspection.report.max-annotated-photos} caps how many photos
 *       one report embeds. This is the limit that matters, because embedding is
 *       the only part of a report whose cost is not proportional to rows.</li>
 *   <li>{@code echno.inspection.report.max-photo-bytes} caps one photo. A site
 *       photo straight off a phone is several megabytes and base64 adds a third
 *       again, so without this one image decides the size of the response.</li>
 * </ul>
 *
 * <p>Anything that does not load, for any reason, comes back empty and the report
 * prints a placeholder saying so. A report that fails because one photo is missing
 * would be worse than a report that says which photo is missing.
 */
@Slf4j
@Component
public class AnnotatedPhotoLoader {

    private static final String DEFAULT_MEDIA_TYPE = "image/jpeg";

    private final FileStorageService fileStorage;
    private final int maxPhotos;
    private final long maxPhotoBytes;

    public AnnotatedPhotoLoader(
            FileStorageService fileStorage,
            @Value("${echno.inspection.report.max-annotated-photos:12}") int maxPhotos,
            @Value("${echno.inspection.report.max-photo-bytes:2000000}") long maxPhotoBytes) {
        this.fileStorage = fileStorage;
        this.maxPhotos = maxPhotos;
        this.maxPhotoBytes = maxPhotoBytes;
    }

    /** Most photos one report will embed. */
    public int maxPhotos() {
        return maxPhotos;
    }

    /**
     * Reads one photo and encodes it for embedding.
     *
     * @param storedReference The reference as the defect stores it.
     * @return A {@code data:} URI, or empty when the reference does not name an
     *         object in our bucket, the object is missing, it is over the size
     *         limit, or storage is unavailable.
     */
    public Optional<String> dataUri(String storedReference) {
        Optional<String> key = fileStorage.keyForStoredReference(storedReference);
        if (key.isEmpty()) {
            log.debug("Not embedding a defect photo: the reference does not name an object in this bucket");
            return Optional.empty();
        }

        return fileStorage.readObject(key.get(), maxPhotoBytes)
                .map(object -> {
                    String mediaType = object.contentType() == null || object.contentType().isBlank()
                            ? DEFAULT_MEDIA_TYPE
                            : object.contentType();
                    return "data:" + mediaType + ";base64,"
                            + Base64.getEncoder().encodeToString(object.content());
                });
    }
}
