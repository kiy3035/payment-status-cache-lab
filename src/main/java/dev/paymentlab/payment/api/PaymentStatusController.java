package dev.paymentlab.payment.api;

import dev.paymentlab.payment.PaymentStatusQueryResult;
import dev.paymentlab.payment.PaymentStatusService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentStatusController {

    private static final String CACHE_RESULT_HEADER = "X-Cache-Result";
    private final PaymentStatusService paymentStatusService;

    public PaymentStatusController(PaymentStatusService paymentStatusService) {
        this.paymentStatusService = paymentStatusService;
    }

    @GetMapping("/{paymentId}/status")
    public ResponseEntity<PaymentStatusResponse> getStatus(@PathVariable long paymentId) {
        PaymentStatusQueryResult result = paymentStatusService.getStatus(paymentId);
        return ResponseEntity.ok()
                .header(CACHE_RESULT_HEADER, result.cacheResult().headerValue())
                .body(result.response());
    }

    @PatchMapping("/{paymentId}/status")
    public PaymentStatusResponse changeStatus(
            @PathVariable long paymentId,
            @Valid @RequestBody ChangePaymentStatusRequest request) {
        return paymentStatusService.changeStatus(paymentId, request.targetStatus());
    }
}
