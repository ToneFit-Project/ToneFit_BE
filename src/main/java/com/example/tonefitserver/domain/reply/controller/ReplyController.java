package com.example.tonefitserver.domain.reply.controller;

import com.example.tonefitserver.domain.reply.dto.ReplyAnalysisRequest;
import com.example.tonefitserver.domain.reply.dto.ReplyAnalysisResponse;
import com.example.tonefitserver.domain.reply.dto.ReplyDraftRequest;
import com.example.tonefitserver.domain.reply.dto.ReplyDraftResponse;
import com.example.tonefitserver.domain.reply.dto.ReplySummaryResponse;
import com.example.tonefitserver.domain.reply.service.ReplyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회신 API (REQ-Reply). 인증 필수(Extension 정식만) — permitAll 아님.
 *
 * <p>PM 재설계(v0.58): 요약을 생성 파이프라인에서 분리. "회신 준비" 버튼에서 FE 가 요약·파악을
 * 병렬 호출 — 요약이 먼저 도착하면 표시하고, 파악으로 입력 화면을 띄운다. 작성은 사용자 입력 후 별도 호출.
 *
 * <ul>
 *   <li>POST /api/v1/replies/summary — 요약 호출 (화면 표시 전용, 파악·작성에는 미사용). 200</li>
 *   <li>POST /api/v1/replies/analysis — 파악 호출 (①정리~③파악, 정리 원문 대화 기반). 200</li>
 *   <li>POST /api/v1/replies — 작성 호출 (⑤작성~⑥점검, 사용자 입력 후). 201</li>
 * </ul>
 *
 * <p>한도(PM 확정): 일일·분당 모두 호출별 차감으로 통일 — 요약·파악·작성 각 1회.
 */
@RestController
@RequestMapping("/api/v1/replies")
@RequiredArgsConstructor
public class ReplyController {

    private final ReplyService replyService;

    @PostMapping("/summary")
    public ReplySummaryResponse summary(@AuthenticationPrincipal Long userId,
                                        @Valid @RequestBody ReplyAnalysisRequest request) {
        return replyService.summary(userId, request);
    }

    @PostMapping("/analysis")
    public ReplyAnalysisResponse analyze(@AuthenticationPrincipal Long userId,
                                         @Valid @RequestBody ReplyAnalysisRequest request) {
        return replyService.analyze(userId, request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReplyDraftResponse draft(@AuthenticationPrincipal Long userId,
                                    @Valid @RequestBody ReplyDraftRequest request) {
        return replyService.draft(userId, request);
    }
}
