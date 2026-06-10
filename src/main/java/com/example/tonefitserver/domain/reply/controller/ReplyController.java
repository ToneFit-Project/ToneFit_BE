package com.example.tonefitserver.domain.reply.controller;

import com.example.tonefitserver.domain.reply.dto.ReplyAnalysisRequest;
import com.example.tonefitserver.domain.reply.dto.ReplyAnalysisResponse;
import com.example.tonefitserver.domain.reply.dto.ReplyDraftRequest;
import com.example.tonefitserver.domain.reply.dto.ReplyDraftResponse;
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
 * 회신 API (REQ-Reply). 서버 호출은 두 번 — 파악·작성 (FUNC-Rep-02).
 * 인증 필수(Extension 정식만) — permitAll 아님.
 *
 * <ul>
 *   <li>POST /api/v1/replies/analysis — 파악 호출 (①정리~③파악, 입력 화면 띄우기 전). 200</li>
 *   <li>POST /api/v1/replies — 작성 호출 (⑤작성~⑥점검, 사용자 입력 후). 201</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/replies")
@RequiredArgsConstructor
public class ReplyController {

    private final ReplyService replyService;

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
