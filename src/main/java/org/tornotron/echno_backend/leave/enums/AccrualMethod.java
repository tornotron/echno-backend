package org.tornotron.echno_backend.leave.enums;

/**
 * How a policy's annual quota reaches an employee's balance.
 *
 * <p>{@link #MONTHLY} is the behaviour every policy had before the method was configurable: one
 * twelfth of the quota (or {@code accrualRatePerMonth}) is credited per month of service, scaled by
 * attendance where attendance is tracked. {@link #IN_FULL_ON_QUALIFYING} credits the whole quota
 * the month the employee becomes eligible under {@code minServiceMonths}, which is the shape a
 * maternity or paternity entitlement takes: there is no sense in which a person has accrued a
 * third of it by April.
 */
public enum AccrualMethod {
    MONTHLY,
    IN_FULL_ON_QUALIFYING
}
