package com.alleato.ecommerce.ordering.controller.exception;

import com.alleato.ecommerce.ordering.controller.response.ErrorResponse;
import com.alleato.ecommerce.ordering.controller.response.ValidationErrorResponse;
import com.alleato.ecommerce.ordering.exception.OrderingIllegalArgumentException;
import com.alleato.ecommerce.ordering.exception.OrderingInternalServerException;
import com.alleato.ecommerce.ordering.exception.OrderingNotFoundException;
import com.alleato.ecommerce.ordering.exception.OrderingRuntimeException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(OrderingIllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(OrderingIllegalArgumentException ex) {
    var body = new ErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST.value(), ex.getContext());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  @ExceptionHandler(OrderingNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFound(OrderingNotFoundException ex) {
    var body = new ErrorResponse(ex.getMessage(), HttpStatus.NOT_FOUND.value(), Map.of());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
  }

  @ExceptionHandler(OrderingInternalServerException.class)
  public ResponseEntity<ErrorResponse> handleInternalServer(OrderingInternalServerException ex) {
    log.error("Internal error: {}", ex.getMessage(), ex);
    var body =
        new ErrorResponse(
            "An internal error occurred", HttpStatus.INTERNAL_SERVER_ERROR.value(), Map.of());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }

  @ExceptionHandler(OrderingRuntimeException.class)
  public ResponseEntity<ErrorResponse> handleRuntime(OrderingRuntimeException ex) {
    log.error("Internal error: {}", ex.getMessage(), ex);
    var body =
        new ErrorResponse(
            "An internal error occurred", HttpStatus.INTERNAL_SERVER_ERROR.value(), Map.of());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ValidationErrorResponse> handleValidationException(
      MethodArgumentNotValidException ex) {
    var fieldErrors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                fe -> new ValidationErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
            .toList();

    var body =
        new ValidationErrorResponse(
            "Validation failed", HttpStatus.BAD_REQUEST.value(), fieldErrors);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception ex) {
    log.error("Unexpected error: {}", ex.getMessage(), ex);
    var body =
        new ErrorResponse(
            "An internal error occurred", HttpStatus.INTERNAL_SERVER_ERROR.value(), Map.of());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }
}
