package com.thanh.payment.infrastructure.persistence;

import com.thanh.payment.application.provider.ProviderOrderCodeGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Cấp mã bằng sequence PostgreSQL provider_order_code_seq (V5). nextval không bị rollback nên mã không bao giờ lặp, kể cả khi transaction tạo payment thất bại.
@Component
@RequiredArgsConstructor
public class SequenceProviderOrderCodeGenerator implements ProviderOrderCodeGenerator {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public long next() {
        Long value = jdbcTemplate.queryForObject("SELECT nextval('provider_order_code_seq')", Long.class);
        if (value == null) {
            throw new IllegalStateException("provider_order_code_seq returned no value");
        }
        return value;
    }
}
