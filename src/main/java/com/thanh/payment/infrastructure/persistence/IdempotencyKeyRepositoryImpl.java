package com.thanh.payment.infrastructure.persistence;

import com.thanh.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.thanh.payment.domain.model.IdempotencyKey;
import com.thanh.payment.domain.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class IdempotencyKeyRepositoryImpl implements IdempotencyKeyRepository {

    private final IdempotencyKeyJpaRepository repository;

    @Override
    public IdempotencyKey save(IdempotencyKey key) {
        return repository.save(key);
    }

    @Override
    public IdempotencyKey saveAndFlush(IdempotencyKey key) {
        try {
            return repository.saveAndFlush(key);
        } catch (DataIntegrityViolationException exception) {
            // Dịch lỗi của DB thành lỗi domain để tầng application không phải biết tên ràng buộc.
            if (ConstraintViolations.violates(exception, "uq_idempotency_scope_key")) {
                throw new DuplicateIdempotencyKeyException(exception);
            }
            throw exception;
        }
    }

    // Dựa vào unique (scope, key_hash) của bảng nên tối đa trả về một dòng.
    @Override
    public Optional<IdempotencyKey> find(String scope, String keyHash) {
        return repository.findByScopeAndKeyHash(scope, keyHash);
    }
}
