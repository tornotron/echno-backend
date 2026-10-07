package org.tornotron.echno_backend.modules.assistant.api;

/**
 * A source of evidence the assistant can ask. Implement this as a Spring bean and the registry finds
 * it: a new provider is a new class and nothing else.
 *
 * <p>{@link #retrieve} runs in the caller's request thread, with the caller's tenant and
 * authorities already in place, and calls core services, never repositories and never native SQL
 * against core tables. {@code @PreAuthorize} sits on controllers in this codebase, not services, so
 * a provider's {@code retrieve} carries the same guard as the controller endpoint it mirrors, and a
 * test fails if the two ever differ.
 */
public interface AssistantProvider {

    /** What this provider says about itself. Cheap, and the same on every call. */
    ProviderDescriptor describe();

    /** Looks for evidence under the scope, as the calling user. */
    ProviderResult retrieve(Question question, Scope scope);
}
