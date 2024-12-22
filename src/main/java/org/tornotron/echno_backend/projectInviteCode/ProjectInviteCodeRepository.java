package org.tornotron.echno_backend.projectInviteCode;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectInviteCodeRepository extends JpaRepository<ProjectInviteCode,Long> {
    Optional<ProjectInviteCode> findByCode(int code);
}
