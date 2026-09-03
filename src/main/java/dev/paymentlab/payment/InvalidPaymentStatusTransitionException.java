package dev.paymentlab.payment;

public class InvalidPaymentStatusTransitionException extends RuntimeException {

    public InvalidPaymentStatusTransitionException(PaymentStatus currentStatus) {
        super(currentStatus.transitionRuleMessage());
    }
}
