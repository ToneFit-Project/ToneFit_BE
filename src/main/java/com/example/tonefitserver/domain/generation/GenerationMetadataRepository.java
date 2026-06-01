package com.example.tonefitserver.domain.generation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface GenerationMetadataRepository extends JpaRepository<GenerationMetadata, Long> {

    /** 90일 경과분 파기 (매일 0시 @Scheduled). 삭제된 row 수 반환. */
    @Modifying
    @Query("delete from GenerationMetadata m where m.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    /** AI_LEARNING 동의 철회 시 해당 user 메타데이터 즉시 삭제. */
    @Modifying
    @Query("delete from GenerationMetadata m where m.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
