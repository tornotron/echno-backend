package org.tornotron.echno_backend.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.organization.Organization;

import java.util.List;


public interface UserRepository extends JpaRepository<User,Long> {
    @Query("SELECT DISTINCT e.organization FROM Employee  e WHERE e.user.id = :userId")
    List<Organization> findOrganizationsByUserId(@Param("userId") Long userId);
}
