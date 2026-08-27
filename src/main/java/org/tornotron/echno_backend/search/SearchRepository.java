package org.tornotron.echno_backend.search;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.project.Project;

import java.util.List;

/**
 * The read model behind quick search: one query per searchable kind, each returning
 * {@link SearchHit} rows directly.
 *
 * <p>Deliberately its own repository rather than three methods spread across
 * {@code ProjectRepository}, {@code TaskRepository} and {@code IssueRepository}. Search is a
 * feature with one consumer and one shape, and keeping its three queries together is what makes
 * them stay consistent with each other. The declared domain type is only what Spring Data needs to
 * bootstrap the interface; every method carries an explicit {@code @Query}, so none of them is
 * derived from it.
 *
 * <p>Each query projects straight into the record rather than loading entities and mapping them.
 * A palette needs a name and an id, and fetching whole aggregates to throw all but two fields away
 * is the cost this endpoint exists to avoid.
 *
 * <p>The tenant filter applies to these queries exactly as it does to a derived finder, so a hit
 * can only ever come from the caller's own organization.
 *
 * <p>Every search pattern is a pre-lowercased {@code %...%} LIKE built by {@link SearchService},
 * which escapes any wildcard the user typed; {@code ESCAPE '\'} is what gives that escaping effect.
 * Bounding comes from the {@link Pageable} the service passes, never from the caller directly.
 */
public interface SearchRepository extends Repository<Project, Long> {

    /**
     * Finds projects whose name matches.
     *
     * @param search   Lower-cased LIKE pattern.
     * @param pageable The row limit to apply.
     * @return Matching projects as search hits, shortest name first.
     */
    @Query("""
            SELECT new org.tornotron.echno_backend.search.SearchHit(
                     org.tornotron.echno_backend.search.SearchHitType.PROJECT, p.id, p.projectName, p.id)
            FROM Project p
            WHERE LOWER(p.projectName) LIKE :search ESCAPE '\\'
            ORDER BY LENGTH(p.projectName) ASC, p.id DESC
            """)
    List<SearchHit> findProjects(@Param("search") String search, Pageable pageable);

    /**
     * Finds tasks whose title matches.
     *
     * @param search   Lower-cased LIKE pattern.
     * @param pageable The row limit to apply.
     * @return Matching tasks as search hits, shortest title first.
     */
    @Query("""
            SELECT new org.tornotron.echno_backend.search.SearchHit(
                     org.tornotron.echno_backend.search.SearchHitType.TASK, t.id, t.title, p.id)
            FROM Task t LEFT JOIN t.project p
            WHERE LOWER(t.title) LIKE :search ESCAPE '\\'
            ORDER BY LENGTH(t.title) ASC, t.id DESC
            """)
    List<SearchHit> findTasks(@Param("search") String search, Pageable pageable);

    /**
     * Finds issues whose title matches.
     *
     * @param search   Lower-cased LIKE pattern.
     * @param pageable The row limit to apply.
     * @return Matching issues as search hits, shortest title first.
     */
    @Query("""
            SELECT new org.tornotron.echno_backend.search.SearchHit(
                     org.tornotron.echno_backend.search.SearchHitType.ISSUE, i.id, i.title, p.id)
            FROM Issue i LEFT JOIN i.task t LEFT JOIN t.project p
            WHERE LOWER(i.title) LIKE :search ESCAPE '\\'
            ORDER BY LENGTH(i.title) ASC, i.id DESC
            """)
    List<SearchHit> findIssues(@Param("search") String search, Pageable pageable);
}
