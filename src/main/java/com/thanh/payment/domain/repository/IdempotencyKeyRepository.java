package com.thanh.payment.domain.repository;

import com.thanh.payment.domain.model.IdempotencyKey;

import java.util.Optional;

public interface IdempotencyKeyRepository {
    IdempotencyKey save(IdempotencyKey key);

    // Lưu rồi flush ngay: vi phạm unique (scope, key_hash) nổ tại đây chứ không đợi COMMIT.
    IdempotencyKey saveAndFlush(IdempotencyKey key);

    // Tìm theo (scope, key_hash). Không có kết quả nghĩa là key này chưa từng dùng.
    Optional<IdempotencyKey> find(String scope, String keyHash);
}
