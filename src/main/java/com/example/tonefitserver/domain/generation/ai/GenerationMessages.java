package com.example.tonefitserver.domain.generation.ai;

import com.example.tonefitserver.core.enums.Purpose;
import com.example.tonefitserver.core.enums.Receiver;

/**
 * 생성 user 메시지 조립(공용). Gemini(sync/async)·OpenAI 클라이언트가 동일 입력 형식을 쓰도록 단일화 —
 * provider 간 프롬프트 드리프트 방지. PM 생성 prompt 의 입력 형식([Receiver]/[Purpose]/[BriefContent]).
 */
public final class GenerationMessages {

    private GenerationMessages() {
    }

    public static String buildUserMessage(Receiver receiver, Purpose purpose, String briefContent) {
        return "수신자 유형: " + receiver + '\n'
                + "목적: " + purpose + '\n'
                + "상황: " + briefContent;
    }
}
