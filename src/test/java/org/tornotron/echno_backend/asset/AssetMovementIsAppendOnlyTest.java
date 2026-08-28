package org.tornotron.echno_backend.asset;

import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Append-only is a property of the ledger's types, not a convention someone has to remember.
 *
 * <p>{@link AssetMovementRepository} extends {@code Repository} rather than {@code JpaRepository},
 * so Spring Data implements only the methods it declares and there is no {@code delete} or
 * {@code deleteAll} for anything to reach for. {@link AssetMovement} is {@code @Immutable} on top
 * of that, so an entry loaded and modified is not flushed either. This test fails the moment
 * either of those is relaxed, which is the point: the guarantee should not be quietly removable
 * by widening an interface.
 */
class AssetMovementIsAppendOnlyTest {

    @Test
    void theLedgerRepositoryOffersNoWayToDeleteAnEntry() {
        String[] destructive = Arrays.stream(AssetMovementRepository.class.getMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("delete") || name.startsWith("remove"))
                .toArray(String[]::new);

        assertThat(destructive)
                .as("the asset movement ledger must expose no delete of any kind")
                .isEmpty();
    }

    @Test
    void theLedgerRepositoryDoesNotInheritTheFullJpaSurface() {
        assertThat(org.springframework.data.jpa.repository.JpaRepository.class
                .isAssignableFrom(AssetMovementRepository.class))
                .as("extending JpaRepository would hand the ledger deleteAll and saveAll")
                .isFalse();
    }

    @Test
    void anEntryCannotBeEditedOnceWritten() {
        assertThat(AssetMovement.class.getAnnotation(Immutable.class))
                .as("a ledger entry that can be updated is not a ledger entry")
                .isNotNull();
    }

    @Test
    void aCorrectionIsHowAnEntryIsPutRight() {
        assertThat(AssetMovementType.values()).contains(AssetMovementType.CORRECTION);
        assertThat(AssetMovement.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .contains("correctsMovementId");
    }
}
