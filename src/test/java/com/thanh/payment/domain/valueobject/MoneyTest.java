package com.thanh.payment.domain.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    @Test
    @DisplayName("Khởi tạo Money với scale chuẩn = 4 và HALF_UP")
    void shouldCreateMoneyWithDefaultScale4() {
        Money money = Money.of(new BigDecimal("100.5"), Currency.VND);
        assertEquals(new BigDecimal("100.5000"), money.getAmount());
        assertEquals(Currency.VND, money.getCurrency());
    }

    @Test
    @DisplayName("Ném ngoại lệ khi amount hoặc currency null")
    void shouldThrowExceptionWhenNullValues() {
        assertThrows(IllegalArgumentException.class, () -> Money.of(null, Currency.VND));
        assertThrows(IllegalArgumentException.class, () -> Money.of(BigDecimal.TEN, null));
    }

    @Test
    @DisplayName("Phép cộng hai Money cùng loại tiền tệ")
    void shouldAddMoneyWithSameCurrency() {
        Money m1 = Money.vnd(new BigDecimal("100.25"));
        Money m2 = Money.vnd(new BigDecimal("50.75"));

        Money result = m1.plus(m2);

        assertEquals(new BigDecimal("151.0000"), result.getAmount());
        assertEquals(Currency.VND, result.getCurrency());
    }

    @Test
    @DisplayName("Ném ngoại lệ khi cộng khác loại tiền tệ")
    void shouldThrowExceptionWhenAddingDifferentCurrencies() {
        Money vnd = Money.vnd(100);
        Money usd = Money.usd(10);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> vnd.plus(usd));
        assertTrue(ex.getMessage().contains("Currencies must match"));
    }

    @Test
    @DisplayName("Phép trừ hai Money cùng loại tiền tệ")
    void shouldSubtractMoneyWithSameCurrency() {
        Money m1 = Money.usd(new BigDecimal("100.00"));
        Money m2 = Money.usd(new BigDecimal("40.50"));

        Money result = m1.minus(m2);

        assertEquals(new BigDecimal("59.5000"), result.getAmount());
        assertEquals(Currency.USD, result.getCurrency());
    }

    @Test
    @DisplayName("Phép nhân với hệ số BigDecimal hoặc long")
    void shouldMultiplyMoney() {
        Money m = Money.usd(new BigDecimal("25.50"));

        Money res1 = m.multiply(2);
        Money res2 = m.multiply(new BigDecimal("1.1"));

        assertEquals(new BigDecimal("51.0000"), res1.getAmount());
        assertEquals(new BigDecimal("28.0500"), res2.getAmount());
    }

    @Test
    @DisplayName("Phép chia với RoundingMode")
    void shouldDivideMoney() {
        Money m = Money.vnd(new BigDecimal("100"));
        Money res = m.divide(new BigDecimal("3"), RoundingMode.HALF_UP);

        assertEquals(new BigDecimal("33.3333"), res.getAmount());
    }

    @Test
    @DisplayName("So sánh Money (equals và compareTo)")
    void shouldCompareMoneyCorrectly() {
        Money m1 = Money.of(new BigDecimal("100.00"), Currency.VND);
        Money m2 = Money.of(new BigDecimal("100.0000"), Currency.VND);
        Money m3 = Money.of(new BigDecimal("200.00"), Currency.VND);

        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());
        assertTrue(m3.isGreaterThan(m1));
        assertTrue(m1.isLessThan(m3));
        assertTrue(m1.isGreaterThanOrEqual(m2));
    }

    @Test
    @DisplayName("Kiểm tra predicates: isPositive, isZero, isNegative")
    void shouldCheckPredicates() {
        assertTrue(Money.vnd(100).isPositive());
        assertTrue(Money.zero(Currency.VND).isZero());
        assertTrue(Money.vnd(-50).isNegative());
    }
}
