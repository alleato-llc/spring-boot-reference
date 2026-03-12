package com.alleato.ecommerce.ordering.controller.response;

import java.util.List;

public record ValidationErrorResponse(String message, int status, List<FieldError> errors) {

  public record FieldError(String field, String error) {}
}
