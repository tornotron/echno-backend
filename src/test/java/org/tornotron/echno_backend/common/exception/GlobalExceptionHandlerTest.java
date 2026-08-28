package org.tornotron.echno_backend.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.expression.Expression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.ExpressionAuthorizationDecision;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the RFC 7807 problem responses. These pin both the standard
 * fields (status/title/detail) and the legacy keys the echno-core client still
 * reads (message, details, and the validation errors map), so the error contract
 * stays stable as the body moves to ProblemDetail.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private WebRequest requestWithPath(String path) {
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn(path);
        return request;
    }

    @Test
    void resourceNotFound_isA404ProblemCarryingMessageAndDetails() {
        WebRequest request = requestWithPath("uri=/api/v1/employees/9");
        ProblemDetail pd = handler.handleResourceNotFoundException(
                new ResourceNotFoundException("Employee 9 not found"), request);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getTitle()).isEqualTo("Resource Not Found");
        assertThat(pd.getDetail()).isEqualTo("Employee 9 not found");
        // Legacy keys the client reads.
        assertThat(pd.getProperties()).containsEntry("message", "Employee 9 not found");
        assertThat(pd.getProperties()).containsEntry("details", "uri=/api/v1/employees/9");
        assertThat(pd.getProperties()).containsKey("timestamp");
    }

    @Test
    void tenantAccessDenied_usesFixedMessageNotTheException() {
        ProblemDetail pd = handler.handleTenantAccessDeniedException(
                new TenantAccessDeniedException("internal detail that must not leak"),
                requestWithPath("uri=/api/v1/projects"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getTitle()).isEqualTo("Access Denied");
        assertThat(pd.getDetail()).isEqualTo("Access to this resource is not permitted for your organization");
        assertThat(pd.getProperties())
                .containsEntry("message", "Access to this resource is not permitted for your organization");
    }

    @Test
    void invalidRequest_isA400Problem() {
        ProblemDetail pd = handler.handleInvalidRequestException(
                new InvalidRequestException("Payment amount must be greater than zero"),
                requestWithPath("uri=/api/v1/payables/1/pay"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getTitle()).isEqualTo("Invalid Request");
        assertThat(pd.getProperties()).containsEntry("message", "Payment amount must be greater than zero");
    }

    @Test
    @SuppressWarnings("unchecked")
    void validationErrors_areExposedAsFieldMap() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getAllErrors()).thenReturn(List.of(
                new FieldError("invoice", "customerId", "must not be null"),
                new FieldError("invoice", "amount", "must be positive")));

        ProblemDetail pd = handler.handleValidationException(ex, requestWithPath("uri=/api/v1/invoices"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getTitle()).isEqualTo("Validation Failed");
        assertThat(pd.getProperties()).containsEntry("message", "Validation Failed");
        Map<String, String> errors = (Map<String, String>) pd.getProperties().get("errors");
        assertThat(errors)
                .containsEntry("customerId", "must not be null")
                .containsEntry("amount", "must be positive");
    }

    @Test
    void duplicateResource_reportsTheDuplicateItActuallyFound() {
        ProblemDetail pd = handler.handleDuplicateResourceException(
                new DuplicateResourceException("Purchase Order with PO number PO-2026-000001 already exists"),
                requestWithPath("uri=/api/v1/purchase-orders"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getTitle()).isEqualTo("Duplicate Resource");
        assertThat(pd.getDetail()).isEqualTo("Purchase Order with PO number PO-2026-000001 already exists");
        assertThat(pd.getProperties())
                .containsEntry("message", "Purchase Order with PO number PO-2026-000001 already exists");
    }

    @Test
    void duplicateResource_withNoMessage_stillSaysSomethingUseful() {
        ProblemDetail pd = handler.handleDuplicateResourceException(
                new DuplicateResourceException(null), requestWithPath("uri=/api/v1/purchase-orders"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getDetail()).isEqualTo("Database operation failed, Data already exists");
    }

    @Test
    void unknownException_isA500Problem() {
        ProblemDetail pd = handler.handleAllUncaughtException(
                new RuntimeException("boom"), requestWithPath("uri=/api/v1/x"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(pd.getTitle()).isEqualTo("Internal Server Error");
        assertThat(pd.getDetail()).isEqualTo("An unexpected error occurred: boom");
    }

    @Test
    void authorizationDenied_namesTheRoleTheEndpointWanted() {
        // Spring's own message here is the fixed string "Access Denied", which is exactly the
        // problem: the caller learns nothing about what would have let them through.
        ProblemDetail pd = handler.handleAuthorizationDeniedException(
                deniedBy("@orgSecurity.hasAnyOrgRole(#id, 'system-admin', 'hr-admin')"),
                requestWithPath("uri=/api/v1/organization/web/3"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getTitle()).isEqualTo("Access Denied");
        assertThat(pd.getDetail())
                .startsWith("You do not have permission for this action.")
                .contains("the 'system-admin' or 'hr-admin' roles in this organization");
        assertThat(pd.getProperties())
                .containsEntry("requiredOrganizationRoles", List.of("system-admin", "hr-admin"));
    }

    @Test
    void authorizationDenied_exposesTheRequiredAuthoritiesForTheFrontend() {
        ProblemDetail pd = handler.handleAuthorizationDeniedException(
                deniedBy("hasAuthority('organization:admin')"),
                requestWithPath("uri=/api/v1/organization/creator/4"));

        assertThat(pd.getProperties()).containsEntry("requiredAuthorities", List.of("organization:admin"));
        assertThat(pd.getProperties()).doesNotContainKey("requiredOrganizationRoles");
    }

    @Test
    void authorizationDenied_fallsBackToSpringsMessageWhenNoExpressionIsAttached() {
        // A denial raised by an AuthorizationManager written in Java carries no expression, so
        // there is nothing to name and nothing should be invented.
        ProblemDetail pd = handler.handleAuthorizationDeniedException(
                new AuthorizationDeniedException("Access Denied"), requestWithPath("uri=/api/v1/x"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getDetail()).isEqualTo("Access Denied");
        assertThat(pd.getProperties()).doesNotContainKey("requiredOrganizationRoles");
    }

    @Test
    void securityAccessDenied_isA403CarryingTheServicesOwnMessage() {
        // Services throw this with a message written for the caller. It used to reach the
        // catch-all handler and come back as a 500, hiding both the status and the reason.
        ProblemDetail pd = handler.handleSecurityAccessDeniedException(
                new org.springframework.security.access.AccessDeniedException(
                        "Only the sender can edit this message"),
                requestWithPath("uri=/api/v1/chat/messages/9"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getTitle()).isEqualTo("Access Denied");
        assertThat(pd.getDetail()).isEqualTo("Only the sender can edit this message");
        assertThat(pd.getProperties()).containsEntry("message", "Only the sender can edit this message");
    }

    /** A method-security denial carrying the expression that refused it, as Spring builds one. */
    private AuthorizationDeniedException deniedBy(String expression) {
        Expression parsed = mock(Expression.class);
        when(parsed.getExpressionString()).thenReturn(expression);
        return new AuthorizationDeniedException("Access Denied",
                new ExpressionAuthorizationDecision(false, parsed));
    }

    @Test
    void illegalArgument_withEnumMessage_listsAvailableValues() {
        // Message shaped like Java's "No enum constant <fqcn>.<value>" against a real enum.
        String message = "No enum constant org.tornotron.echno_backend.finance.ledger.AccountType.BOGUS";
        ProblemDetail pd = handler.handleIllegalArgumentException(
                new IllegalArgumentException(message), requestWithPath("uri=/api/v1/accounts"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getTitle()).isEqualTo("Invalid Argument");
        assertThat(pd.getDetail())
                .startsWith("Invalid value 'BOGUS' provided for enum 'AccountType'. Available values:")
                .contains("ASSET");
    }
}
