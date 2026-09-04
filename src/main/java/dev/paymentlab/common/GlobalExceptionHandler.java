package dev.paymentlab.common;

import java.time.Instant;

import dev.paymentlab.payment.InvalidPaymentStatusTransitionException;
import dev.paymentlab.payment.PaymentNotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(PaymentNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(InvalidPaymentStatusTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidTransition(
            InvalidPaymentStatusTransitionException exception) {
        return error(
                HttpStatus.CONFLICT,
                "INVALID_PAYMENT_STATUS_TRANSITION",
                exception.getMessage());
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiErrorResponse> handleOptimisticLockingConflict(RuntimeException exception) {
        return error(
                HttpStatus.CONFLICT,
                "PAYMENT_STATUS_CONFLICT",
                "동시에 결제 상태가 변경되었습니다. 다시 조회한 후 시도해 주세요.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값을 확인해 주세요.");
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ApiErrorResponse> handleDataAccessFailure(RuntimeException exception) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PAYMENT_STATUS_UNAVAILABLE",
                "결제 상태 저장소에 연결할 수 없습니다.");
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(code, message, Instant.now()));
    }
}
