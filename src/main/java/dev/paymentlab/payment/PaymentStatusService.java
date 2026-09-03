package dev.paymentlab.payment;

import java.time.Instant;

import dev.paymentlab.payment.api.PaymentStatusResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PaymentStatusService {

    private final PaymentRepository paymentRepository;

    public PaymentStatusService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    public PaymentStatusResponse getStatus(long paymentId) {
        return PaymentStatusResponse.from(findPayment(paymentId));
    }

    @Transactional
    public PaymentStatusResponse changeStatus(long paymentId, PaymentStatus targetStatus) {
        Payment payment = findPayment(paymentId);
        payment.transitionTo(targetStatus, Instant.now());
        paymentRepository.flush();
        return PaymentStatusResponse.from(payment);
    }

    private Payment findPayment(long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
