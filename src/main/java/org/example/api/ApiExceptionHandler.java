package org.example.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        ApiErrorResponse response = new ApiErrorResponse(
                new ApiErrorResponse.ErrorBody(exception.getCode(), exception.getMessage()));
        return ResponseEntity.status(exception.getStatus()).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        ApiErrorResponse response = new ApiErrorResponse(
                new ApiErrorResponse.ErrorBody("INTERNAL_ERROR", "An unexpected error occurred"));
        return ResponseEntity.internalServerError().body(response);
    }
}
