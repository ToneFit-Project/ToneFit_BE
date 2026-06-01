package com.example.tonefitserver.domain.prompt;

import com.example.tonefitserver.domain.session.Receiver;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PromptVersionRepository extends JpaRepository<PromptVersion, Long> {

    /**
     * 활성 prompt 조회. (purpose, recipient_type) 조합당 partial UNIQUE 라
     * 정상 운영 상태에서는 결과 0 또는 1.
     */
    Optional<PromptVersion> findFirstByPurposeAndRecipientTypeAndIsActiveTrue(
            PromptPurpose purpose, Receiver recipientType);
}
