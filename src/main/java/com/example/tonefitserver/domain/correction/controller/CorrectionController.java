package com.example.tonefitserver.domain.correction.controller;

import com.example.tonefitserver.domain.correction.dto.CorrectionRequest;
import com.example.tonefitserver.domain.correction.dto.CorrectionResponse;
import com.example.tonefitserver.domain.correction.dto.RejectionsRequest;
import com.example.tonefitserver.domain.correction.dto.RejectionsResponse;
import com.example.tonefitserver.domain.correction.service.CorrectionService;
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
 * 교정 API (v0.6). 무상태 2개 엔드포인트만 — 세션·히스토리·확정·개별 거부(상태 변경)는 제거됨.
 *
 * <ul>
 *   <li>POST /api/v1/corrections — 교정 요청(결과만 반환, 저장 안 함)</li>
 *   <li>POST /api/v1/corrections/rejections — 거절 항목 보존(AI_LEARNING 동의자 한정)</li>
 * </ul>
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

    @PostMapping("/rejections")
    public RejectionsResponse rejections(@AuthenticationPrincipal Long userId,
                                         @Valid @RequestBody RejectionsRequest request) {
        return correctionService.persistRejections(userId, request);
    }
}
