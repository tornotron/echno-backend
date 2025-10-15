package org.tornotron.echno_backend.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.tornotron.echno_backend.common.response.ErrorResponse;
import org.tornotron.echno_backend.common.response.ErrorResponseValidation;

import java.nio.file.AccessDeniedException;
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
    public ErrorResponse handleResourceNotFoundException(ResourceNotFoundException ex, WebRequest request) {
        logger.error("Resource not found exception: ", ex);
        return new ErrorResponse(
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
    public ErrorResponse handleDataIntegrityViolationException(DataIntegrityViolationException ex, WebRequest request) {
        logger.error("Data integrity violation: ", ex);
        return new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Database operation failed, Data Integrity violation",
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(DuplicateResourceException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateResourceException(DuplicateResourceException ex, WebRequest request) {
        logger.error("Duplicate resource: ", ex);
        return new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Database operation failed, Data already exists",
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleAccessDeniedException(AccessDeniedException ex, WebRequest request) {
        logger.error("Access denied: ", ex);
        return new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Access denied",
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleAllUncaughtException(Exception ex, WebRequest request) {
        logger.error("Unknown error occurred: ", ex);
        return new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred: " + ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleMissingRequestBody(HttpMessageNotReadableException ex, WebRequest request) {
        logger.error("Missing request body: ", ex);
        return new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Required request body is missing",
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(InvalidInviteCodeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidInviteCodeException(InvalidInviteCodeException ex, WebRequest request) {
        logger.error("Invalid Invite Code: ", ex);
        return new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleAuthorizationDeniedException(AuthorizationDeniedException ex, WebRequest request) {
        logger.error("Access denied: ", ex);
        return new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(DatabaseOperationException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleDatabaseOperationException(DatabaseOperationException ex, WebRequest request) {
        logger.error("Database operation failed: ", ex);
        return new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );

    }

    @ExceptionHandler(InvalidAttendanceSequenceException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleInvalidAttendanceSequenceException(InvalidAttendanceSequenceException ex, WebRequest request) {
        logger.error("Invalid Attendance marking sequence: ", ex);
        return new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(InvalidRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidRequestException(InvalidRequestException ex, WebRequest request) {
        logger.error("Invalid request: ", ex);
        return new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(TenantIdMissingException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleTenantIdMissingException(TenantIdMissingException ex, WebRequest request) {
        logger.error("Tenant ID is missing: ", ex);
        return new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIllegalArgumentException(IllegalArgumentException ex, WebRequest request) {
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

        return new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                errorMessage,
                request.getDescription(false),
                LocalDateTime.now()
        );
    }
}

