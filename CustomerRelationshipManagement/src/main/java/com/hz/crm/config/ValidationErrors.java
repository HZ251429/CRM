package com.hz.crm.config;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * When a form's fields are not valid (e.g. "Enter a valid mobile number"), send the readable reason to the browser
 * instead of a bare "400", so the person sees what to fix.
 */
@RestControllerAdvice
public class ValidationErrors {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getAllErrors().stream()
				.map(err -> err.getDefaultMessage() == null ? "Invalid value"
						: err instanceof FieldError f && err.getDefaultMessage().startsWith("must")
								? f.getField() + " " + err.getDefaultMessage() // e.g. "units must be greater than 0"
								: err.getDefaultMessage())
				.distinct().collect(Collectors.joining("; "));
		return ResponseEntity.badRequest().body(Map.of("status", HttpStatus.BAD_REQUEST.value(), "message", message));
	}
}
