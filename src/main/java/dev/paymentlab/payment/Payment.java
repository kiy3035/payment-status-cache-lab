package dev.paymentlab.payment;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    Payment(Long id, PaymentStatus status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void transitionTo(PaymentStatus targetStatus, Instant changedAt) {
        if (!status.canTransitionTo(targetStatus)) {
            throw new InvalidPaymentStatusTransitionException(status);
        }
        status = targetStatus;
        updatedAt = changedAt;
    }

    public Long getId() {
        return id;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
