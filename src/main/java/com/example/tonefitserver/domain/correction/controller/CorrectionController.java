package com.example.tonefitserver.domain.correction.controller;

import com.example.tonefitserver.domain.correction.dto.ConfirmRequest;
import com.example.tonefitserver.domain.correction.dto.ConfirmResponse;
import com.example.tonefitserver.domain.correction.dto.CorrectionDetailResponse;
import com.example.tonefitserver.domain.correction.dto.CorrectionRequest;
import com.example.tonefitserver.domain.correction.dto.CorrectionResponse;
import com.example.tonefitserver.domain.correction.dto.HistoryResponse;
import com.example.tonefitserver.domain.correction.dto.InProgressResponse;
import com.example.tonefitserver.domain.correction.dto.RejectRequest;
import com.example.tonefitserver.domain.correction.dto.RejectResponse;
import com.example.tonefitserver.domain.correction.service.CorrectionService;
import com.example.tonefitserver.domain.session.Purpose;
import com.example.tonefitserver.domain.session.Receiver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * v0.52 API 명세 §3. 교정 → (개별 거부) → 확정 3단계로 단순화. 후교정/재교정/구조교정/편집 모두 제거.
 */
@RestController
@RequestMapping("/api/v1/corrections")
@RequiredArgsConstructor
public class CorrectionController {

    private final CorrectionService correctionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CorrectionResponse correct(@AuthenticationPrincipal Long userId,
                                      @Valid @RequestBody CorrectionRequest request) {
        return correctionService.correct(userId, request);
    }

    @PostMapping("/{sessionId}/reject")
    public RejectResponse rejectFeedback(@AuthenticationPrincipal Long userId,
                                         @PathVariable Long sessionId,
                                         @Valid @RequestBody RejectRequest request) {
        return correctionService.rejectFeedback(userId, sessionId, request);
    }

    @PostMapping("/{sessionId}/confirm")
    public ConfirmResponse confirm(@AuthenticationPrincipal Long userId,
                                   @PathVariable Long sessionId,
                                   @Valid @RequestBody ConfirmRequest request) {
        return correctionService.confirm(userId, sessionId, request);
    }

    @GetMapping("/in-progress")
    public InProgressResponse listInProgress(@AuthenticationPrincipal Long userId) {
        return correctionService.listInProgress(userId);
    }

    @GetMapping("/history")
    public HistoryResponse listHistory(@AuthenticationPrincipal Long userId,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size,
                                       @RequestParam(value = "receiver_type", required = false) Receiver receiverType,
                                       @RequestParam(required = false) Purpose purpose) {
        return correctionService.listHistory(userId, page, size, receiverType, purpose);
    }

    @GetMapping("/{sessionId}")
    public CorrectionDetailResponse getDetail(@AuthenticationPrincipal Long userId,
                                              @PathVariable Long sessionId) {
        return correctionService.getDetail(userId, sessionId);
    }
}
