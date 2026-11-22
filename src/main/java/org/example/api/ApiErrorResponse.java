package org.example.api;

public record ApiErrorResponse(ErrorBody error) {
    public record ErrorBody(String code, String message) {}
}
