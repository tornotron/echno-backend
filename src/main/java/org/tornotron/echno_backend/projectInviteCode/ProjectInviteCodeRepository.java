package org.tornotron.echno_backend.projectInviteCode;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for {@link ProjectInviteCode} entities.
 * Provides methods to perform database operations on project invite codes.
 */
public interface ProjectInviteCodeRepository extends JpaRepository<ProjectInviteCode,Long> {
    /**
     * Finds a project invite code by its unique code.
     *
     * @param code The five-digit invite code.
     * @return An {@link Optional} containing the found {@link ProjectInviteCode}, or {@link Optional#empty()} if no code matches.
     */
    Optional<ProjectInviteCode> findByCodeAndOrganization_Id(int code, Long organizationId);

    List<ProjectInviteCode> findByOrganization_Id(Long organizationId);

    Optional<ProjectInviteCode> findByIdAndOrganization_Id(Long id, Long organizationId);
}