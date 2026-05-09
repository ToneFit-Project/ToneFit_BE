package com.example.tonefitserver.domain.correction.repository;

import com.example.tonefitserver.domain.correction.model.CorrectionFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.Optional;

public interface CorrectionFeedbackRepository extends JpaRepository<CorrectionFeedback, Long> {

    List<CorrectionFeedback> findBySessionIdOrderByIndexAsc(Long sessionId);

    Optional<CorrectionFeedback> findBySessionIdAndIndex(Long sessionId, int index);

    @Modifying
    void deleteBySessionId(Long sessionId);
}
