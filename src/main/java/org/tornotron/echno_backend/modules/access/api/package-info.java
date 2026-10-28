/**
 * The access control contract: the only part of the access module anything else may use.
 *
 * <p>It speaks in decisions, not data. {@link AccessService#check} answers whether a person may
 * do something, on one record or in an area; {@link AccessService#filter} answers which records
 * they may see, as an {@link AccessFilter} a module turns into its own query; and
 * {@link AccessService#grantsFor} lists what they hold, for building navigation. Roles, groups and
 * grants are how the current engine reaches those answers and never appear here, so another
 * engine can answer the same questions in its own way.
 *
 * <p>Modules describe their records with {@link RecordFacts} (project, the people a record is
 * about or assigned to, the customer or vendor on it) and leave the rules to the engine.
 */
package org.tornotron.echno_backend.modules.access.api;
