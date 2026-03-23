package org.tornotron.echno_backend.indent;


import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IndentRepository extends JpaRepository<Indent,Long> {
    Optional<Indent> findByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);

    void deleteByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsByIndentNumberAndOrganization_Id(String indentNumber, Long organizationId);
}
