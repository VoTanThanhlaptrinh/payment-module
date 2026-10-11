package com.thanh.payment;

import com.thanh.payment.application.dto.webhook.VnpayIpnData;
import com.thanh.payment.application.dto.webhook.VnpayIpnResponse;
import com.thanh.payment.application.service.VnpayIpnProcessor;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.model.PaymentStatus;
import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.model.TransactionStatus;
import com.thanh.payment.domain.model.TransactionType;
import com.thanh.payment.domain.repository.PaymentRepository;
import com.thanh.payment.domain.repository.TransactionRepository;
import com.thanh.payment.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class VnpayIpnPersistenceIntegrationTest {

    @Autowired VnpayIpnProcessor processor;
    @Autowired PaymentRepository paymentRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM payments");
    }

    @Test
    void concurrentDuplicateIpnMustCreateOnlyOneTransition() {
        Payment payment = paymentRepository.saveAndFlush(
                Payment.create(1001L, Money.vnd(5_000), PaymentMethodType.VNPAY));
        Transaction transaction = transactionRepository.saveAndFlush(Transaction.createCharge(
                payment.getId(), Money.vnd(5_000), OffsetDateTime.now(), OffsetDateTime.now().plusMinutes(15)));
        VnpayIpnData data = successData(transaction.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<VnpayIpnResponse> first = CompletableFuture.supplyAsync(
                    () -> processor.processVerified(data), executor);
            CompletableFuture<VnpayIpnResponse> second = CompletableFuture.supplyAsync(
                    () -> processor.processVerified(data), executor);
            List<String> responseCodes = List.of(first.join().rspCode(), second.join().rspCode());

            assertTrue(responseCodes.contains("00"));
            assertTrue(responseCodes.contains("02"));
        } finally {
            executor.shutdownNow();
        }

        Payment reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        Transaction reloadedTransaction = transactionRepository.findById(transaction.getId()).orElseThrow();
        assertEquals(PaymentStatus.COMPLETED, reloadedPayment.getStatus());
        assertEquals(TransactionStatus.SUCCESS, reloadedTransaction.getStatus());
        assertEquals("98765", reloadedTransaction.getProviderTransactionId());
        assertEquals("00", reloadedTransaction.getProviderResponseCode());
    }

    private VnpayIpnData successData(Long transactionId) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("vnp_TxnRef", String.valueOf(transactionId));
        parameters.put("vnp_Amount", "500000");
        parameters.put("vnp_ResponseCode", "00");
        parameters.put("vnp_TransactionStatus", "00");
        parameters.put("vnp_TransactionNo", "98765");
        return new VnpayIpnData(true, false, parameters);
    }
}
