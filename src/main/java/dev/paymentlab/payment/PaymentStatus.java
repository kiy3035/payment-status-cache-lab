package dev.paymentlab.payment;

public enum PaymentStatus {
    READY,
    AUTH,
    APPROVED;

    public boolean canTransitionTo(PaymentStatus targetStatus) {
        return switch (this) {
            case READY -> targetStatus == AUTH;
            case AUTH -> targetStatus == APPROVED;
            case APPROVED -> false;
        };
    }

    public String transitionRuleMessage() {
        return switch (this) {
            case READY -> "READY 상태에서는 AUTH 상태로만 변경할 수 있습니다.";
            case AUTH -> "AUTH 상태에서는 APPROVED 상태로만 변경할 수 있습니다.";
            case APPROVED -> "APPROVED 상태에서는 더 이상 상태를 변경할 수 없습니다.";
        };
    }
}
