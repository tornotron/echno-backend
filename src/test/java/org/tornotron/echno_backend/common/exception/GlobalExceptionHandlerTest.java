package org.tornotron.echno_backend.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
    void unknownException_isA500Problem() {
        ProblemDetail pd = handler.handleAllUncaughtException(
                new RuntimeException("boom"), requestWithPath("uri=/api/v1/x"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(pd.getTitle()).isEqualTo("Internal Server Error");
        assertThat(pd.getDetail()).isEqualTo("An unexpected error occurred: boom");
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
