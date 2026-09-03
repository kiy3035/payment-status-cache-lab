package dev.paymentlab.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant CHANGED_AT = Instant.parse("2026-01-02T00:00:00Z");

    @Test
    void ready에서Auth로변경할수있다() {
        Payment payment = payment(PaymentStatus.READY);

        payment.transitionTo(PaymentStatus.AUTH, CHANGED_AT);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTH);
        assertThat(payment.getUpdatedAt()).isEqualTo(CHANGED_AT);
    }

    @Test
    void auth에서Approved로변경할수있다() {
        Payment payment = payment(PaymentStatus.AUTH);

        payment.transitionTo(PaymentStatus.APPROVED, CHANGED_AT);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getUpdatedAt()).isEqualTo(CHANGED_AT);
    }

    @Test
    void ready에서Approved로건너뛸수없다() {
        Payment payment = payment(PaymentStatus.READY);

        assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.APPROVED, CHANGED_AT))
                .isInstanceOf(InvalidPaymentStatusTransitionException.class)
                .hasMessage("READY 상태에서는 AUTH 상태로만 변경할 수 있습니다.");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(payment.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void auth에서Ready로되돌릴수없다() {
        Payment payment = payment(PaymentStatus.AUTH);

        assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.READY, CHANGED_AT))
                .isInstanceOf(InvalidPaymentStatusTransitionException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTH);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"READY", "AUTH"})
    void approved이후에는상태를변경할수없다(PaymentStatus targetStatus) {
        Payment payment = payment(PaymentStatus.APPROVED);

        assertThatThrownBy(() -> payment.transitionTo(targetStatus, CHANGED_AT))
                .isInstanceOf(InvalidPaymentStatusTransitionException.class)
                .hasMessage("APPROVED 상태에서는 더 이상 상태를 변경할 수 없습니다.");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void 같은상태로변경할수없다(PaymentStatus status) {
        Payment payment = payment(status);

        assertThatThrownBy(() -> payment.transitionTo(status, CHANGED_AT))
                .isInstanceOf(InvalidPaymentStatusTransitionException.class);
        assertThat(payment.getStatus()).isEqualTo(status);
    }

    private Payment payment(PaymentStatus status) {
        return new Payment(1L, status, CREATED_AT, CREATED_AT);
    }
}
