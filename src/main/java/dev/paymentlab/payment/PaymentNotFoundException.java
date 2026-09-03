package dev.paymentlab.payment;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(long paymentId) {
        super("결제 정보를 찾을 수 없습니다. paymentId=" + paymentId);
    }
}
