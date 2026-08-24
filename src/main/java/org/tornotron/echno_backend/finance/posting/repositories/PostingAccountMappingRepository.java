package org.tornotron.echno_backend.finance.posting.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.finance.posting.PostingRole;
import org.tornotron.echno_backend.finance.posting.domain.PostingAccountMapping;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostingAccountMappingRepository extends JpaRepository<PostingAccountMapping, UUID> {

    List<PostingAccountMapping> findByOrganization_Id(Long organizationId);

    Optional<PostingAccountMapping> findByRoleAndOrganization_Id(PostingRole role, Long organizationId);
}
