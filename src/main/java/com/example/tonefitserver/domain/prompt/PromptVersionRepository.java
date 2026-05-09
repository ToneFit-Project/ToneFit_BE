package com.example.tonefitserver.domain.prompt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PromptVersionRepository extends JpaRepository<PromptVersion, Long> {

    Optional<PromptVersion> findFirstByPurposeAndIsActiveTrueOrderByCreatedAtDesc(PromptPurpose purpose);
}
