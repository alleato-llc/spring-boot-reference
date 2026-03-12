package com.alleato.ecommerce.ordering.controller.response;

import java.util.Map;

public record ErrorResponse(String message, int status, Map<String, Object> context) {}
