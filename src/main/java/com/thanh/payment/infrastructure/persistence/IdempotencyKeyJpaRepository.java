package com.thanh.payment.infrastructure.persistence;

import com.thanh.payment.domain.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyKeyJpaRepository extends JpaRepository<IdempotencyKey, Long> {
    Optional<IdempotencyKey> findByScopeAndKeyHash(String scope, String keyHash);
}
