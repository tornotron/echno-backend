package org.tornotron.echno_backend.modules.assistant.api;

/**
 * The write side of the contract, designed and deliberately not built. Providers read; they never
 * change anything, and nothing in this release proposes or executes an action.
 *
 * <p>When the assistant is allowed to act, the shape is a separate interface beside
 * {@link AssistantProvider}: {@code describe()} like a provider, {@code propose(Intent)} returning a
 * typed proposal that changes nothing, and {@code execute(Proposal, Authorisation)} that requires a
 * named authoriser. Keeping it a different type, from the first line, is what lets reading and
 * writing be separated later without a rewrite. No implementation and no endpoint exists.
 */
public interface AssistantAction {
}
