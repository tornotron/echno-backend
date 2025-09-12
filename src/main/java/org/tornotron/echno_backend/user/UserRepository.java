package org.tornotron.echno_backend.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.organization.Organization;

import java.util.List;
import java.util.Optional;


public interface UserRepository extends JpaRepository<User,Long> {
    @Query("SELECT DISTINCT e.organization FROM Employee  e WHERE e.user.id = :userId")
    List<Organization> findOrganizationsByUserId(@Param("userId") Long userId);

    Optional<User> findUserByName(@NotBlank(message = "name is required") @Size(min = 3,max = 50,message = "name must be between 3 and 50 characters") String name);
}
