package org.tornotron.echno_backend.modules.assistant.pipeline;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.project.dto.ProjectSummaryDto;

/**
 * Decides which project a question is about, in Java and not in the model. The name the person
 * typed is searched among the projects the caller can list through {@link ProjectService}, which
 * runs under the tenant filter, so a project of another organization, or one the caller cannot
 * read, can never be the answer however the model phrased it. No match, or more than one, is a
 * question put back to the person.
 */
@Component
@AssistantModuleEnabled
public class ScopeResolver {

    /** More than this many matches are not listed back; the person is asked to be more specific. */
    private static final int MATCHES_SHOWN = 5;

    /** Projects read to find a match; far more than any one name should match. */
    private static final int SEARCH_PAGE_SIZE = 50;

    private final ProjectService projects;

    public ScopeResolver(ProjectService projects) {
        this.projects = projects;
    }

    /** The project of the caller's previous question, still inside the follow-up window. */
    public record PreviousScope(Long projectId, String projectName) {
    }

    public sealed interface Resolution {

        /** One project, and whether it came from the previous question rather than this one. */
        record Resolved(Long projectId, String projectName, boolean fromPreviousQuestion) implements Resolution {
        }

        /** The questions to put back to the person. */
        record Clarify(List<String> questions) implements Resolution {
        }
    }

    public Resolution resolve(String projectRef, Optional<PreviousScope> previous) {
        if (projectRef == null) {
            return previous
                    .<Resolution>map(p -> new Resolution.Resolved(p.projectId(), p.projectName(), true))
                    .orElseGet(() -> new Resolution.Clarify(List.of("Which project do you mean?")));
        }
        Page<ProjectSummaryDto> found = projects.getProjectsSummaryPaginated(0, SEARCH_PAGE_SIZE, projectRef);
        List<ProjectSummaryDto> matches = found.getContent();
        if (matches.isEmpty()) {
            return new Resolution.Clarify(List.of(
                    "I could not find a project called \"" + projectRef + "\" that you can access. Which project do you mean?"));
        }
        List<ProjectSummaryDto> exact = matches.stream()
                .filter(p -> p.getProjectName().equalsIgnoreCase(projectRef.trim()))
                .toList();
        if (exact.size() == 1) {
            return resolved(exact.get(0));
        }
        if (matches.size() == 1) {
            return resolved(matches.get(0));
        }
        String names = String.join(", ", matches.stream().limit(MATCHES_SHOWN).map(ProjectSummaryDto::getProjectName).toList());
        String more = found.getTotalElements() > MATCHES_SHOWN ? ", and others" : "";
        return new Resolution.Clarify(List.of(
                "\"" + projectRef + "\" matches more than one project: " + names + more + ". Which one do you mean?"));
    }

    private static Resolution resolved(ProjectSummaryDto project) {
        return new Resolution.Resolved(project.getId(), project.getProjectName(), false);
    }
}
