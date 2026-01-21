package org.tornotron.echno_backend.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.tornotron.echno_backend.common.response.CustomErrorResponse;
import org.tornotron.echno_backend.common.response.ErrorResponseValidation;
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


@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public CustomErrorResponse handleResourceNotFoundException(ResourceNotFoundException ex, WebRequest request) {
        logger.error("Resource not found exception: ", ex);
        return new CustomErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidationException(MethodArgumentNotValidException exception, WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        exception.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponseValidation(HttpStatus.BAD_REQUEST.value(),
                        "Validation Failed",
                        errors,
                        request.getDescription(false),
                        LocalDateTime.now()
                ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public CustomErrorResponse handleDataIntegrityViolationException(DataIntegrityViolationException ex, WebRequest request) {
        logger.error("Data integrity violation: ", ex);
        return new CustomErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Database operation failed, Data Integrity violation",
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(DuplicateResourceException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public CustomErrorResponse handleDuplicateResourceException(DuplicateResourceException ex, WebRequest request) {
        logger.error("Duplicate resource: ", ex);
        return new CustomErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Database operation failed, Data already exists",
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public CustomErrorResponse handleAccessDeniedException(AccessDeniedException ex, WebRequest request) {
        logger.error("Access denied: ", ex);
        return new CustomErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Access denied",
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public CustomErrorResponse handleAllUncaughtException(Exception ex, WebRequest request) {
        logger.error("Unknown error occurred: ", ex);
        return new CustomErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred: " + ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CustomErrorResponse handleMissingRequestBody(HttpMessageNotReadableException ex, WebRequest request) {
        logger.error("Missing request body: ", ex);
        return new CustomErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Required request body is missing",
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(InvalidInviteCodeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CustomErrorResponse handleInvalidInviteCodeException(InvalidInviteCodeException ex, WebRequest request) {
        logger.error("Invalid Invite Code: ", ex);
        return new CustomErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public CustomErrorResponse handleAuthorizationDeniedException(AuthorizationDeniedException ex, WebRequest request) {
        logger.error("Access denied: ", ex);
        return new CustomErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(DatabaseOperationException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public CustomErrorResponse handleDatabaseOperationException(DatabaseOperationException ex, WebRequest request) {
        logger.error("Database operation failed: ", ex);
        return new CustomErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );

    }

    @ExceptionHandler(InvalidAttendanceSequenceException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public CustomErrorResponse handleInvalidAttendanceSequenceException(InvalidAttendanceSequenceException ex, WebRequest request) {
        logger.error("Invalid Attendance marking sequence: ", ex);
        return new CustomErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(InsufficientStockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public CustomErrorResponse handleInsufficientStockException(InsufficientStockException ex, WebRequest request) {
        logger.error("Insufficient stock: ", ex);
        return new CustomErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(InvalidRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CustomErrorResponse handleInvalidRequestException(InvalidRequestException ex, WebRequest request) {
        logger.error("Invalid request: ", ex);
        return new CustomErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(NoActiveSubscriptionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public CustomErrorResponse handleNoActiveSubscriptionException(NoActiveSubscriptionException ex, WebRequest request) {
        logger.error("No active subscription: ", ex);
        return new CustomErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(PlanNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public CustomErrorResponse handlePlanNotFoundException(PlanNotFoundException ex, WebRequest request) {
        logger.error("Plan not found: ", ex);
        return new CustomErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
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
    public CustomErrorResponse handleTenantIdMissingException(TenantIdMissingException ex, WebRequest request) {
        logger.error("Tenant ID is missing: ", ex);
        return new CustomErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(FileUploadException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleFileUploadException(FileUploadException ex, WebRequest request) {
        logger.error("File upload failed: ", ex);
        return new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CustomErrorResponse handleIllegalArgumentException(IllegalArgumentException ex, WebRequest request) {
        logger.error("Illegal argument: ", ex);
        String errorMessage = "Invalid input provided: " + ex.getMessage();
        String invalidValue = null;
        String enumClassName = null;
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

        return new CustomErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                errorMessage,
                request.getDescription(false),
                LocalDateTime.now()
        );
    }
}

