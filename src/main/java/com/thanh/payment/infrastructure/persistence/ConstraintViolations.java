package com.thanh.payment.infrastructure.persistence;

import org.hibernate.exception.ConstraintViolationException;

import java.util.Locale;

/**
 * Đọc tên ràng buộc DB bị vi phạm từ chuỗi nguyên nhân của một exception.
 * Ưu tiên thông tin có cấu trúc của Hibernate (getConstraintName); nếu Hibernate không điền tên thì dự phòng bằng
 * thông báo của PostgreSQL, vốn luôn ghi tên ràng buộc trong dấu nháy kép.
 */
final class ConstraintViolations {

    private ConstraintViolations() {
    }

    // true nếu trong chuỗi nguyên nhân có vi phạm ràng buộc mang đúng tên (không phân biệt hoa thường).
    static boolean violates(Throwable exception, String constraintName) {
        String quoted = "\"" + constraintName.toLowerCase(Locale.ROOT) + "\"";
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().toLowerCase(Locale.ROOT).contains(quoted)) {
                return true;
            }
        }
        return false;
    }
}
