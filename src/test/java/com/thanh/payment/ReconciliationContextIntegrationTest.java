package com.thanh.payment;

import com.thanh.payment.adapter.out.provider.VnpayQueryDrClient;
import com.thanh.payment.infrastructure.config.VnpayReconciliationScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Bật đối soát và kiểm tra context khởi động được: trước đây thiếu bean RestClient.Builder (Boot 4 tách autoconfig sang
 * spring-boot-starter-restclient) nên app bật đối soát là không chạy.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "payment.providers.vnpay.reconciliation-enabled=true",
        "payment.providers.vnpay.merchant-ip=127.0.0.1"
})
@ActiveProfiles("test")
class ReconciliationContextIntegrationTest {

    @Autowired ApplicationContext context;

    @Test
    void contextStartsWithReconciliationEnabled() {
        assertNotNull(context.getBean(VnpayQueryDrClient.class));
        assertNotNull(context.getBean(VnpayReconciliationScheduler.class));
    }
}
