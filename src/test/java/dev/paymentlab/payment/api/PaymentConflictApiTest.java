package dev.paymentlab.payment.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.paymentlab.payment.Payment;
import dev.paymentlab.payment.PaymentStatus;
import dev.paymentlab.payment.PaymentStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentStatusController.class)
class PaymentConflictApiTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PaymentStatusService paymentStatusService;

    @Test
    void 낙관적잠금충돌은409를반환한다() throws Exception {
        given(paymentStatusService.changeStatus(1L, PaymentStatus.AUTH))
                .willThrow(new ObjectOptimisticLockingFailureException(Payment.class, 1L));

        mockMvc.perform(patch("/api/v1/payments/{paymentId}/status", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"AUTH\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_STATUS_CONFLICT"));
    }
}
