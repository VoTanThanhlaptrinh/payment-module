package com.thanh.payment.domain.repository;

import com.thanh.payment.domain.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    // Dựa vào unique (scope, key_hash) của bảng nên tối đa trả về một dòng. Không có kết quả nghĩa là key này chưa từng dùng.
    Optional<IdempotencyKey> findByScopeAndKeyHash(String scope, String keyHash);
}
