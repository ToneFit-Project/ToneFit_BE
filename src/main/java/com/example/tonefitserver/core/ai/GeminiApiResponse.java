package com.example.tonefitserver.core.ai;

import java.util.List;

/**
 * Gemini generateContent 응답 봉투 (async transport 파싱용). 구조화 출력은
 * {@code candidates[0].content.parts[0].text} 에 내부 JSON 문자열로 담긴다.
 * (동기 클라이언트는 RestClient 자동 역직렬화 + 자체 record 사용 — 이건 async 경로 전용.)
 */
public record GeminiApiResponse(List<Candidate> candidates) {

    public record Candidate(Content content) {
    }

    public record Content(List<Part> parts) {
    }

    public record Part(String text) {
    }

    /** candidates[0].content.parts[0].text — 없으면 null. */
    public String firstText() {
        if (candidates == null || candidates.isEmpty()
                || candidates.get(0).content() == null
                || candidates.get(0).content().parts() == null
                || candidates.get(0).content().parts().isEmpty()) {
            return null;
        }
        return candidates.get(0).content().parts().get(0).text();
    }
}
