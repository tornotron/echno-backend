package org.tornotron.echno_backend.common.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.ExpressionAuthorizationDecision;
import org.springframework.validation.FieldError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.tornotron.echno_backend.common.response.SubscriptionErrorResponse;

import java.nio.file.AccessDeniedException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Translates exceptions into RFC 7807 {@link ProblemDetail} responses.
 *
 * <p>Each problem carries the standard {@code type}/{@code title}/{@code status}/
 * {@code detail} fields. For backward compatibility with the echno-core API client
 * (which reads {@code message}, {@code details} and, on validation errors,
 * {@code errors}) those keys are preserved as problem properties with their existing
 * values: {@code message} mirrors {@code detail}, {@code details} is the request
 * description, and {@code timestamp} the moment of failure.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Builds a problem carrying both the RFC 7807 fields and the legacy keys the
     * existing clients read. The HTTP status still comes from each handler's
     * {@code @ResponseStatus}; the same status is echoed in the body.
     */
    private ProblemDetail problem(HttpStatus status, String title, String detail, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setProperty("message", detail);
        pd.setProperty("details", request.getDescription(false));
        pd.setProperty("timestamp", LocalDateTime.now());
        return pd;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ProblemDetail handleResourceNotFoundException(ResourceNotFoundException ex, WebRequest request) {
        logger.error("Resource not found exception: ", ex);
        return problem(HttpStatus.NOT_FOUND, "Resource Not Found", ex.getMessage(), request);
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ProblemDetail handleTenantAccessDeniedException(TenantAccessDeniedException ex, WebRequest request) {
        logger.warn("Tenant access denied: {}", ex.getMessage());
        return problem(HttpStatus.FORBIDDEN, "Access Denied",
                "Access to this resource is not permitted for your organization", request);
    }

    @ExceptionHandler(UnbalancedEntryException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ProblemDetail handleUnbalancedEntryException(UnbalancedEntryException ex, WebRequest request) {
        logger.error("Unbalanced entry exception: ", ex);
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Unbalanced Journal Entry", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidJournalException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ProblemDetail handleInvalidJournalException(InvalidJournalException ex, WebRequest request) {
        logger.error("Invalid journal exception: ", ex);
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid Journal", ex.getMessage(), request);
    }

    @ExceptionHandler({AccountNotFoundException.class, EntityNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ProblemDetail handleAccountNotFoundException(RuntimeException ex, WebRequest request) {
        logger.error("Account not found exception: ", ex);
        return problem(HttpStatus.NOT_FOUND, "Account Not Found", ex.getMessage(), request);
    }

    @ExceptionHandler(PeriodClosedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handlePeriodClosedException(PeriodClosedException ex, WebRequest request) {
        logger.error("Period closed exception: ", ex);
        return problem(HttpStatus.CONFLICT, "Period Closed", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateIdempotencyKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleDuplicateIdempotencyKeyException(DuplicateIdempotencyKeyException ex, WebRequest request) {
        logger.error("Duplicate idempotency key exception: ", ex);
        return problem(HttpStatus.CONFLICT, "Duplicate Idempotency Key", ex.getMessage(), request);
    }

    /**
     * A write that kept colliding with a concurrent change on the same rows and used up its
     * serialization retries. 409 rather than 503: the service is healthy and every other
     * request is being served, so signalling unavailability would be untrue and would push
     * clients and proxies into backing off from the whole API. It is the same shape as the
     * other "someone else got there first" answers here, and the retryable flag tells the
     * client it may simply send the request again.
     */
    @ExceptionHandler({TransactionRetriesExhaustedException.class, CannotAcquireLockException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleSerializationConflict(Exception ex, WebRequest request) {
        logger.warn("Serialization conflict, request not applied: {}", ex.getMessage());
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "Concurrent Update Conflict",
                "Someone else changed the same records at the same time, so the request was not "
                        + "applied. Please try again.", request);
        pd.setProperty("retryable", true);
        pd.setProperty("retryAfterSeconds", 1);
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException exception, WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        exception.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation Failed", "Validation Failed", request);
        pd.setProperty("errors", errors);
        return pd;
    }

    /**
     * The same 400 as above, for a bean validated outside Spring's own argument binding. A
     * payload that arrives as the JSON string part of a multipart request has to be
     * deserialized and validated by hand, and that raises this rather than
     * {@link MethodArgumentNotValidException}. Without this it would fall through to the
     * catch-all handler and be reported as a 500.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleConstraintViolationException(ConstraintViolationException exception,
                                                           WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            errors.put(violation.getPropertyPath().toString(), violation.getMessage());
        }

        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation Failed", "Validation Failed", request);
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleDataIntegrityViolationException(DataIntegrityViolationException ex, WebRequest request) {
        logger.error("Data integrity violation: ", ex);
        return problem(HttpStatus.CONFLICT, "Data Integrity Violation",
                "Database operation failed, Data Integrity violation", request);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleDuplicateResourceException(DuplicateResourceException ex, WebRequest request) {
        logger.error("Duplicate resource: ", ex);
        return problem(HttpStatus.CONFLICT, "Duplicate Resource",
                "Database operation failed, Data already exists", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ProblemDetail handleAccessDeniedException(AccessDeniedException ex, WebRequest request) {
        logger.error("Access denied: ", ex);
        return problem(HttpStatus.FORBIDDEN, "Access Denied", "Access denied", request);
    }

    @ExceptionHandler(com.fasterxml.jackson.core.JsonProcessingException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleJsonProcessingException(com.fasterxml.jackson.core.JsonProcessingException ex, WebRequest request) {
        logger.error("JSON processing error: ", ex);
        return problem(HttpStatus.BAD_REQUEST, "Malformed JSON",
                "Invalid JSON format: " + ex.getOriginalMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ProblemDetail handleAllUncaughtException(Exception ex, WebRequest request) {
        logger.error("Unknown error occurred: ", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred: " + ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleMissingRequestBody(HttpMessageNotReadableException ex, WebRequest request) {
        logger.error("Missing request body: ", ex);
        return problem(HttpStatus.BAD_REQUEST, "Malformed Request", "Required request body is missing", request);
    }

    @ExceptionHandler(InvalidInviteCodeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleInvalidInviteCodeException(InvalidInviteCodeException ex, WebRequest request) {
        logger.error("Invalid Invite Code: ", ex);
        return problem(HttpStatus.BAD_REQUEST, "Invalid Invite Code", ex.getMessage(), request);
    }

    /**
     * A refused {@code @PreAuthorize}, answered with the permission that would have satisfied it.
     *
     * <p>Spring's own message is the fixed string "Access Denied", which tells a caller that
     * something was refused but never what. The expression that refused it travels on the failure,
     * and it is the one authoritative statement of the requirement, so it is read back out and
     * named here. The role and permission names are also exposed as problem properties, so the
     * frontend can act on them without parsing the sentence.
     *
     * <p>An expression this cannot describe falls back to Spring's message rather than to a guess.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ProblemDetail handleAuthorizationDeniedException(AuthorizationDeniedException ex, WebRequest request) {
        String expression = deniedExpression(ex);
        AuthorizationRequirement requirement = AuthorizationRequirement.from(expression);
        String described = requirement.describe();

        logger.warn("Authorization denied on {} by expression [{}]", request.getDescription(false), expression);

        String detail = described == null
                ? ex.getMessage()
                : "You do not have permission for this action. " + described;

        ProblemDetail pd = problem(HttpStatus.FORBIDDEN, "Access Denied", detail, request);
        if (!requirement.getOrganizationRoles().isEmpty()) {
            pd.setProperty("requiredOrganizationRoles", requirement.getOrganizationRoles());
        }
        if (!requirement.getAuthorities().isEmpty()) {
            pd.setProperty("requiredAuthorities", requirement.getAuthorities());
        }
        return pd;
    }

    /**
     * Handles a refusal raised by our own code rather than by an annotation.
     *
     * <p>Services throw Spring Security's {@code AccessDeniedException} with a message that already
     * says what was wrong ("Only the sender can edit this message"). Without this handler that
     * exception fell through to the catch-all and came back as a 500, which is both the wrong
     * status and the wrong story. The message is written for the caller, so it is passed through.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ProblemDetail handleSecurityAccessDeniedException(
            org.springframework.security.access.AccessDeniedException ex, WebRequest request) {
        logger.warn("Access denied on {}: {}", request.getDescription(false), ex.getMessage());
        return problem(HttpStatus.FORBIDDEN, "Access Denied", ex.getMessage(), request);
    }

    /**
     * The {@code @PreAuthorize} expression behind a denial, when the failure carries one.
     *
     * <p>Method security attaches an {@link ExpressionAuthorizationDecision} holding the very
     * expression that was evaluated. Anything else (a denial from an {@code AuthorizationManager}
     * written in Java, for instance) carries no expression and yields null.
     */
    private String deniedExpression(AuthorizationDeniedException ex) {
        if (ex.getAuthorizationResult() instanceof ExpressionAuthorizationDecision decision) {
            return decision.getExpression().getExpressionString();
        }
        return null;
    }

    @ExceptionHandler(DatabaseOperationException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ProblemDetail handleDatabaseOperationException(DatabaseOperationException ex, WebRequest request) {
        logger.error("Database operation failed: ", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Database Operation Failed", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidAttendanceSequenceException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleInvalidAttendanceSequenceException(InvalidAttendanceSequenceException ex, WebRequest request) {
        logger.error("Invalid Attendance marking sequence: ", ex);
        return problem(HttpStatus.CONFLICT, "Invalid Attendance Sequence", ex.getMessage(), request);
    }

    @ExceptionHandler(InsufficientStockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleInsufficientStockException(InsufficientStockException ex, WebRequest request) {
        logger.error("Insufficient stock: ", ex);
        return problem(HttpStatus.CONFLICT, "Insufficient Stock", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleInvalidRequestException(InvalidRequestException ex, WebRequest request) {
        logger.error("Invalid request: ", ex);
        return problem(HttpStatus.BAD_REQUEST, "Invalid Request", ex.getMessage(), request);
    }

    @ExceptionHandler(NoActiveSubscriptionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleNoActiveSubscriptionException(NoActiveSubscriptionException ex, WebRequest request) {
        logger.error("No active subscription: ", ex);
        return problem(HttpStatus.CONFLICT, "No Active Subscription", ex.getMessage(), request);
    }

    @ExceptionHandler(PlanNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ProblemDetail handlePlanNotFoundException(PlanNotFoundException ex, WebRequest request) {
        logger.error("Plan not found: ", ex);
        return problem(HttpStatus.NOT_FOUND, "Plan Not Found", ex.getMessage(), request);
    }

    @ExceptionHandler(SubscriptionAccessDeniedException.class)
    @ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
    public SubscriptionErrorResponse handleSubscriptionAccessDenied(
            SubscriptionAccessDeniedException ex,
            HttpServletRequest request
    ) {
        logger.warn("Subscription access denied: {} | Path: {} | Feature: {}",
                ex.getMessage(),
                request.getRequestURI(),
                ex.getFeatureCode());

        SubscriptionErrorResponse.SubscriptionErrorResponseBuilder builder = SubscriptionErrorResponse.builder()
                .error("subscription_required")
                .message(ex.getMessage())
                .timestamp(Instant.now())
                .path(request.getRequestURI());
        if (ex.hasAccessResult()) {
            Map<String, Object> details = new HashMap<>();
            details.put("reason", ex.getReason());

            if (ex.getFeatureCode() != null) {
                details.put("feature", ex.getFeatureCode());
            }

            if (ex.getCurrentUsage() != null) {
                details.put("currentUsage", ex.getCurrentUsage());
            }
            if (ex.getQuotaLimit() != null) {
                details.put("quotaLimit", ex.getQuotaLimit());
            }

            builder.details(details);
        }

        return builder.build();

    }

    @ExceptionHandler(TenantIdMissingException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleTenantIdMissingException(TenantIdMissingException ex, WebRequest request) {
        logger.error("Tenant ID is missing: ", ex);
        return problem(HttpStatus.BAD_REQUEST, "Tenant Id Missing", ex.getMessage(), request);
    }

    @ExceptionHandler(FileUploadException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ProblemDetail handleFileUploadException(FileUploadException ex, WebRequest request) {
        logger.error("File upload failed: ", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "File Upload Failed", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleIllegalArgumentException(IllegalArgumentException ex, WebRequest request) {
        logger.error("Illegal argument: ", ex);
        String errorMessage = "Invalid input provided: " + ex.getMessage();
        String invalidValue;
        String enumClassName;
        String availableEnums = null;

        Pattern enumErrorPattern = Pattern.compile("No enum constant (\\S+)\\.(\\S+)");
        Matcher matcher = enumErrorPattern.matcher(ex.getMessage());

        if (matcher.find()) {
            enumClassName = matcher.group(1);
            invalidValue = matcher.group(2);

            try {
                Class<?> enumClass = Class.forName(enumClassName);
                if (enumClass.isEnum()) {
                    availableEnums = Arrays.stream(enumClass.getEnumConstants())
                            .map(Object::toString)
                            .collect(Collectors.joining(", "));
                }
            } catch (ClassNotFoundException e) {
                logger.warn("Could not load enum class for listing values: " + enumClassName, e);
            }

            errorMessage = String.format("Invalid value '%s' provided for enum '%s'. Available values: %s",
                    invalidValue,
                    enumClassName.substring(enumClassName.lastIndexOf('.') + 1),
                    (availableEnums != null ? availableEnums : "N/A"));
        }

        return problem(HttpStatus.BAD_REQUEST, "Invalid Argument", errorMessage, request);
    }
}
