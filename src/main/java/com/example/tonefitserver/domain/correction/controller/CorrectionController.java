package com.example.tonefitserver.domain.correction.controller;

import com.example.tonefitserver.domain.correction.dto.ConfirmRequest;
import com.example.tonefitserver.domain.correction.dto.ConfirmResponse;
import com.example.tonefitserver.domain.correction.dto.CorrectionDetailResponse;
import com.example.tonefitserver.domain.correction.dto.CorrectionRequest;
import com.example.tonefitserver.domain.correction.dto.CorrectionResponse;
import com.example.tonefitserver.domain.correction.dto.EditRequest;
import com.example.tonefitserver.domain.correction.dto.EditResponse;
import com.example.tonefitserver.domain.correction.dto.FinalizeResponse;
import com.example.tonefitserver.domain.correction.dto.HistoryResponse;
import com.example.tonefitserver.domain.correction.dto.InProgressResponse;
import com.example.tonefitserver.domain.correction.dto.RecorrectRequest;
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

    @PostMapping("/{sessionId}/recorrect")
    public CorrectionResponse recorrect(@AuthenticationPrincipal Long userId,
                                        @PathVariable Long sessionId,
                                        @Valid @RequestBody RecorrectRequest request) {
        return correctionService.recorrect(userId, sessionId, request);
    }

    @PostMapping("/{sessionId}/reject")
    public RejectResponse rejectFeedback(@AuthenticationPrincipal Long userId,
                                         @PathVariable Long sessionId,
                                         @Valid @RequestBody RejectRequest request) {
        return correctionService.rejectFeedback(userId, sessionId, request);
    }

    @PostMapping("/{sessionId}/finalize")
    public FinalizeResponse finalizeSession(@AuthenticationPrincipal Long userId,
                                            @PathVariable Long sessionId) {
        return correctionService.finalize(userId, sessionId);
    }

    @PatchMapping("/{sessionId}/edit")
    public EditResponse editFinal(@AuthenticationPrincipal Long userId,
                                  @PathVariable Long sessionId,
                                  @RequestBody EditRequest request) {
        return correctionService.editFinal(userId, sessionId, request);
    }

    @PostMapping("/{sessionId}/confirm")
    public ConfirmResponse confirmFinal(@AuthenticationPrincipal Long userId,
                                        @PathVariable Long sessionId,
                                        @RequestBody(required = false) ConfirmRequest request) {
        return correctionService.confirmFinal(userId, sessionId, request);
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
