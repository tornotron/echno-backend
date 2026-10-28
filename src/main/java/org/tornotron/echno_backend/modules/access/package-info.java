/**
 * Access control: who may do what in an organization, and on which records.
 *
 * <p>A core module. The rest of the application, and every other module, may use only
 * {@link org.tornotron.echno_backend.modules.access.api}. Everything else here (the matrix
 * tables, the resolver, the seed, the admin endpoints) belongs to the engine currently behind
 * that API and may change or be replaced without touching its callers.
 */
package org.tornotron.echno_backend.modules.access;
