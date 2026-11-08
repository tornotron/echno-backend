package org.tornotron.echno_backend.indent;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface IndentRepository extends JpaRepository<Indent,Long>, JpaSpecificationExecutor<Indent> {
    Optional<Indent> findByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);

    void deleteByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsByIndentNumberAndOrganization_Id(String indentNumber, Long organizationId);
}
