package dev.paymentlab.payment.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.paymentlab.payment.PaymentStatus;
import dev.paymentlab.payment.PaymentStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

@WebMvcTest(PaymentStatusController.class)
class PaymentAvailabilityApiTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PaymentStatusService service;

    @Test
    void 조회Transaction시작실패는503으로변환하고내부정보를숨긴다() throws Exception {
        given(service.getStatus(1L)).willThrow(new CannotCreateTransactionException("내부 연결 상세"));
        mockMvc.perform(get("/api/v1/payments/1/status"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_STATUS_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("결제 상태 저장소에 연결할 수 없습니다."));
    }

    @Test
    void 변경Transaction시작실패도503으로변환한다() throws Exception {
        given(service.changeStatus(1L, PaymentStatus.AUTH))
                .willThrow(new CannotCreateTransactionException("내부 연결 상세"));
        mockMvc.perform(patch("/api/v1/payments/1/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetStatus\":\"AUTH\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_STATUS_UNAVAILABLE"));
    }
}
