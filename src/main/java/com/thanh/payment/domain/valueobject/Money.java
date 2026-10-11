package com.thanh.payment.domain.valueobject;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Value Object biểu diễn số tiền và loại tiền tệ theo chuẩn kế toán/tài chính.
 * <p>
 * Bất biến (Immutable), scale cố định = 4, precision = 18.
 */
@Embeddable
@Getter
public class Money implements Serializable, Comparable<Money> {

    public static final int DEFAULT_SCALE = 4;
    public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_UP;

    @Column(name = "amount", precision = 18, scale = DEFAULT_SCALE, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", length = 10, nullable = false)
    private Currency currency;

    /**
     * Protected constructor phục vụ cho JPA / Hibernate reflection.
     */
    protected Money() {
    }

    /**
     * Khởi tạo đối tượng Money với kiểm tra hợp lệ và chuẩn hóa scale kế toán.
     *
     * @param amount   Số tiền (không được null)
     * @param currency Đơn vị tiền tệ (không được null)
     */
    // Luôn chuẩn hoá về scale 4 (HALF_UP) để so sánh/lưu DB nhất quán. VNPay chỉ nhận số nguyên VND nên PaymentService còn kiểm tra thêm "không có phần thập phân".
    public Money(BigDecimal amount, Currency currency) {
        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null");
        }
        if (currency == null) {
            throw new IllegalArgumentException("Currency cannot be null");
        }
        this.amount = amount.setScale(DEFAULT_SCALE, DEFAULT_ROUNDING);
        this.currency = currency;
    }

    // --- Static Factory Methods ---

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(long amount, Currency currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }

    public static Money of(double amount, Currency currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public static Money vnd(BigDecimal amount) {
        return of(amount, Currency.VND);
    }

    public static Money vnd(long amount) {
        return of(amount, Currency.VND);
    }

    public static Money usd(BigDecimal amount) {
        return of(amount, Currency.USD);
    }

    public static Money usd(double amount) {
        return of(amount, Currency.USD);
    }

    // --- Accounting / Financial Operations ---

    /**
     * Cộng tiền (phải cùng loại tiền tệ).
     */
    public Money plus(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money add(Money other) {
        return plus(other);
    }

    /**
     * Trừ tiền (phải cùng loại tiền tệ).
     */
    public Money minus(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public Money subtract(Money other) {
        return minus(other);
    }

    /**
     * Nhân tiền với một hệ số (ví dụ: lãi suất, số lượng, tỷ lệ % thuế).
     */
    public Money multiply(BigDecimal multiplicand) {
        if (multiplicand == null) {
            throw new IllegalArgumentException("Multiplicand cannot be null");
        }
        return new Money(this.amount.multiply(multiplicand), this.currency);
    }

    public Money multiply(long factor) {
        return multiply(BigDecimal.valueOf(factor));
    }

    /**
     * Chia tiền theo chuẩn làm tròn kế toán.
     */
    public Money divide(BigDecimal divisor, RoundingMode roundingMode) {
        if (divisor == null || divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("Divisor cannot be null or zero");
        }
        return new Money(this.amount.divide(divisor, DEFAULT_SCALE, roundingMode != null ? roundingMode : DEFAULT_ROUNDING), this.currency);
    }

    // --- Comparison & Predicates ---

    public boolean sameCurrencyAs(Money other) {
        return other != null && this.currency == other.currency;
    }

    private void validateSameCurrency(Money other) {
        if (other == null) {
            throw new IllegalArgumentException("Cannot operate with null Money");
        }
        if (this.currency != other.currency) {
            throw new IllegalArgumentException(
                    String.format("Currencies must match: expected %s but got %s", this.currency, other.currency)
            );
        }
    }

    public boolean isGreaterThan(Money other) {
        validateSameCurrency(other);
        return this.amount.compareTo(other.amount) > 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        validateSameCurrency(other);
        return this.amount.compareTo(other.amount) >= 0;
    }

    public boolean isLessThan(Money other) {
        validateSameCurrency(other);
        return this.amount.compareTo(other.amount) < 0;
    }

    public boolean isLessThanOrEqual(Money other) {
        validateSameCurrency(other);
        return this.amount.compareTo(other.amount) <= 0;
    }

    public boolean isPositive() {
        return this.amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isZero() {
        return this.amount.compareTo(BigDecimal.ZERO) == 0;
    }

    public boolean isNegative() {
        return this.amount.compareTo(BigDecimal.ZERO) < 0;
    }

    @Override
    public int compareTo(Money other) {
        validateSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    // --- Value Object Equality ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Money money = (Money) o;
        return currency == money.currency &&
                (amount != null && money.amount != null ? amount.compareTo(money.amount) == 0 : Objects.equals(amount, money.amount));
    }

    @Override
    public int hashCode() {
        // Strip trailing zeros để hashCode nhất quán với compareTo trong BigDecimal
        return Objects.hash(amount != null ? amount.stripTrailingZeros() : null, currency);
    }

    @Override
    public String toString() {
        return amount + " " + currency;
    }
}
