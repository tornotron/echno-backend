package org.tornotron.echno_backend.holiday;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The days of the week an organization works, one row per organization.
 *
 * <p>There was no organization-level working-week setting before this row: the leave accrual
 * counted Monday to Friday and nothing else asked. The row is created on first read with that
 * same Monday to Friday default, so an organization that never opens the setting behaves as it
 * always has. Stored as a comma-separated list of {@link DayOfWeek} names, which is the whole
 * value and small enough that a join table would be ceremony.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "organization_working_week", uniqueConstraints = {
        @UniqueConstraint(name = "uk_working_week_org", columnNames = {"organization_id"})
})
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class WorkingWeek implements TenantScopedEntity {

    /** Monday to Friday, the working week every organization had before the setting existed. */
    public static final Set<DayOfWeek> DEFAULT_WORKING_DAYS = Set.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "working_days", nullable = false, length = 100)
    private String workingDays = encode(DEFAULT_WORKING_DAYS);

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * The working days as a set, in weekday order.
     *
     * @return The days this organization works; Monday to Friday when the column is blank.
     */
    public Set<DayOfWeek> workingDaySet() {
        if (workingDays == null || workingDays.isBlank()) {
            return EnumSet.copyOf(DEFAULT_WORKING_DAYS);
        }
        Set<DayOfWeek> days = Arrays.stream(workingDays.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(DayOfWeek::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
        return days.isEmpty() ? EnumSet.copyOf(DEFAULT_WORKING_DAYS) : days;
    }

    /**
     * Stores a set of working days.
     *
     * @param days The days worked; must not be empty.
     */
    public void setWorkingDaySet(Set<DayOfWeek> days) {
        this.workingDays = encode(days);
    }

    static String encode(Set<DayOfWeek> days) {
        return days.stream()
                .sorted()
                .map(DayOfWeek::name)
                .collect(Collectors.joining(","));
    }
}
