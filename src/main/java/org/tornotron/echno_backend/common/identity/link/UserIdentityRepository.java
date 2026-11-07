package org.tornotron.echno_backend.common.identity.link;

import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface UserIdentityRepository extends Repository<UserIdentity, UUID> {

    /** Writes the link unless it is already there. */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO user_identity (id, provider, issuer, subject, user_id)
            VALUES (gen_random_uuid(), :provider, :issuer, :subject, :userId)
            ON CONFLICT (issuer, subject) DO NOTHING
            """, nativeQuery = true)
    void link(@Param("provider") String provider, @Param("issuer") String issuer,
              @Param("subject") String subject, @Param("userId") Long userId);
}
