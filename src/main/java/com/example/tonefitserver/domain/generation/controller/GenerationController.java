package com.example.tonefitserver.domain.generation.controller;

import com.example.tonefitserver.domain.generation.dto.GenerationRequest;
import com.example.tonefitserver.domain.generation.dto.GenerationResponse;
import com.example.tonefitserver.domain.generation.service.GenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * v0.52 API 명세 §4 — 일회성 이메일 생성. 세션 미생성, history 비노출.
 * PM 결정: 체험 횟수는 FE/localStorage 관리. BE 는 카운트·한도 체크 안 함 (FUNC-De-04 #4).
 */
@RestController
@RequestMapping("/api/v1/generations")
@RequiredArgsConstructor
public class GenerationController {

    private final GenerationService generationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GenerationResponse generate(@AuthenticationPrincipal Long userId,
                                       @Valid @RequestBody GenerationRequest request) {
        return generationService.generate(userId, request);
    }
}
