package org.tornotron.echno_backend.common.identity.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One provider account and the Echno user it is. An issuer and a subject together name an account,
 * and a person can have one per provider while a deployment moves between providers.
 */
@Entity
@Table(name = "user_identity",
        uniqueConstraints = @UniqueConstraint(name = "uq_user_identity_issuer_subject",
                columnNames = {"issuer", "subject"}))
@Getter
@NoArgsConstructor
public class UserIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false)
    private String issuer;

    @Column(nullable = false)
    private String subject;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
