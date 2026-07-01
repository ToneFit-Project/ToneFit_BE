package com.example.tonefitserver.core.ai;

import java.util.List;

/**
 * OpenAI Chat Completions 응답 봉투 (공용). 구조화 출력은 {@code choices[0].message.content} 에
 * 내부 JSON 문자열로 담긴다. 전역 SNAKE_CASE 매퍼로 매핑(전 필드 단일 단어).
 */
public record OpenAiChatResponse(List<Choice> choices) {

    public record Choice(Message message) {
    }

    public record Message(String content) {
    }

    /** choices[0].message.content — 없으면 null. */
    public String firstContent() {
        if (choices == null || choices.isEmpty() || choices.get(0).message() == null) {
            return null;
        }
        return choices.get(0).message().content();
    }
}
