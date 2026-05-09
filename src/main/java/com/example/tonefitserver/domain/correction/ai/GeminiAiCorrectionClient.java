package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.domain.session.Purpose;
import com.example.tonefitserver.domain.session.Range;
import com.example.tonefitserver.domain.session.Receiver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini")
@RequiredArgsConstructor
public class GeminiAiCorrectionClient implements AiCorrectionClient {

    private static final String START_MARKER = "⟦"; // ⟦
    private static final String END_MARKER = "⟧";   // ⟧

    private static final String DEFAULT_CORRECTION_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 교정 어시스턴트입니다.
            입력된 이메일 본문을 검토하고 다음을 반환하세요.
            1) corrected_email: 전체를 교정한 최종 본문
            2) changes: 교정 항목 배열 (index는 0부터, 본문 등장 순서대로 나열)
               - original: 원문에 실제로 존재하는 교정 대상 문자열 (수정/가공 금지)
               - corrected: 교정 후 문자열
               - reason: 교정 사유
               - label: 맞춤법/문법은 AUTO, 톤은 SUGGEST, 스타일은 STYLE
               - confidence: 0.0 ~ 1.0
               - applied_rules: 참고한 규칙 코드 배열 (없으면 빈 배열)

            [보호 구간 규칙]
            입력 본문에 "⟦" 와 "⟧" 로 감싸진 구간이 있을 수 있습니다. 해당 구간은 보호 텍스트입니다.
            - 보호 구간 내부의 텍스트는 어떤 이유로도 수정하지 마세요.
            - 응답 corrected_email 안에도 "⟦" "⟧" 마커와 그 안의 내용을 원문과 동일하게 그대로 포함하세요.
            - 보호 구간에 대한 change 항목은 생성하지 마세요.

            [줄바꿈 규칙]
            원문의 개행 구조(문단 구분)를 있는 그대로 보존하세요.
            추가로 개행을 삽입하거나 제거하지 마세요.
            """;

    private static final String DEFAULT_FINALIZE_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 다듬기 어시스턴트입니다.
            수신자 유형과 목적에 맞추어 본문을 매끄럽게 다듬고,
            ai_final (최종 본문)과 ai_subject (제목)를 반환하세요.

            [보호 구간 규칙]
            입력 본문에 "⟦" 와 "⟧" 로 감싸진 구간이 있을 수 있습니다.
            이는 사용자가 직전 단계에서 수락(또는 명시적으로 거절하여 원문 유지를 선택)한 텍스트, 혹은 처음부터 보호로 지정된 구간입니다.
            - 보호 구간 내부의 어휘/표현은 어떤 이유로도 수정하지 마세요. (재교정·동의어 치환·어조 변경 모두 금지)
            - 응답 ai_final 안에도 "⟦" "⟧" 마커와 그 안의 내용을 원문 그대로 포함하세요.
            - 보호 구간 바깥의 연결어, 인사말, 마무리 문구는 자유롭게 다듬어도 됩니다.

            [줄바꿈 규칙]
            인사말, 본문, 마무리 간의 문단 구분이 유지되도록 적절한 개행을 포함하세요.
            단, 불필요하게 개행을 중복해서 넣지 마세요.
            """;

    private final RestClient geminiRestClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public AiCorrectionResult correct(String promptContent, Receiver receiver, Purpose purpose,
                                      String original, List<Range> protectedRanges) {
        String system = (promptContent == null || promptContent.isBlank())
                ? DEFAULT_CORRECTION_SYSTEM_PROMPT : promptContent;
        String preparedOriginal = insertMarkers(original == null ? "" : original, protectedRanges);
        String user = buildCorrectionUserMessage(receiver, purpose, preparedOriginal);
        String json = callAndExtract(system, user, correctionSchema());

        AiCorrectionResult raw;
        try {
            raw = objectMapper.readValue(json, AiCorrectionResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini correction response: " + json, e);
        }

        String cleanedEmail = restoreFromAi(raw.correctedEmail());
        List<AiCorrectionResult.Change> cleanedChanges = sanitizeChanges(
                original == null ? "" : original, protectedRanges, raw.changes());
        return new AiCorrectionResult(cleanedEmail, cleanedChanges);
    }

    @Override
    public AiFinalizeResult finalizePolish(String promptContent, Receiver receiver, Purpose purpose,
                                           String mergedText, List<Range> protectedRanges) {
        String system = (promptContent == null || promptContent.isBlank())
                ? DEFAULT_FINALIZE_SYSTEM_PROMPT : promptContent;
        String prepared = insertMarkers(mergedText == null ? "" : mergedText, protectedRanges);
        String user = buildFinalizeUserMessage(receiver, purpose, prepared);
        String json = callAndExtract(system, user, finalizeSchema());

        AiFinalizeResult raw;
        try {
            raw = objectMapper.readValue(json, AiFinalizeResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini finalize response: " + json, e);
        }
        return new AiFinalizeResult(restoreFromAi(raw.aiFinal()), restoreFromAi(raw.aiSubject()));
    }

    // === 송신 변환 ===

    private String insertMarkers(String text, List<Range> ranges) {
        if (ranges == null || ranges.isEmpty()) return text;
        // start 내림차순으로 뒤에서부터 삽입 → 앞쪽 오프셋 유지
        List<Range> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(Range::getStart).reversed());

        StringBuilder sb = new StringBuilder(text);
        for (Range r : sorted) {
            int start = r.getStart();
            int end = r.getEnd();
            if (start < 0 || end > sb.length() || start >= end) {
                log.warn("Skipping invalid protected range: start={}, end={}, textLen={}",
                        start, end, sb.length());
                continue;
            }
            sb.insert(end, END_MARKER);
            sb.insert(start, START_MARKER);
        }
        return sb.toString();
    }

    // === 수신 변환 ===

    private String restoreFromAi(String aiText) {
        if (aiText == null) return null;
        return aiText
                .replace(START_MARKER, "")
                .replace(END_MARKER, "");
    }

    private List<AiCorrectionResult.Change> sanitizeChanges(String original,
                                                            List<Range> protectedRanges,
                                                            List<AiCorrectionResult.Change> changes) {
        if (changes == null) return List.of();
        List<AiCorrectionResult.Change> result = new ArrayList<>();
        int cursor = 0;

        for (AiCorrectionResult.Change ch : changes) {
            String cleanOriginal = restoreFromAi(ch.original());
            String cleanCorrected = restoreFromAi(ch.corrected());
            String cleanReason = restoreFromAi(ch.reason());

            if (cleanOriginal == null || cleanOriginal.isEmpty()) {
                log.warn("Dropping change (empty or null original): index={}", ch.index());
                continue;
            }

            int[] range = findOriginalRange(original, cleanOriginal, cursor);
            if (range == null) {
                log.warn("Dropping change (original text not found in source): '{}'", cleanOriginal);
                continue;
            }
            int start = range[0];
            int end = range[1];

            if (overlapsProtected(start, end, protectedRanges)) {
                log.warn("Dropping change (overlaps protected range): [{},{})", start, end);
                continue;
            }

            cursor = end;
            // 원문에서 실제로 발췌한 substring 을 사용 — Gemini 가 공백 normalize 등을 했어도
            // UI/머지에는 사용자가 실제로 입력한 형태가 들어가야 함.
            String actualOriginal = original.substring(start, end);
            result.add(new AiCorrectionResult.Change(
                    result.size(), start, end,
                    actualOriginal, cleanCorrected, cleanReason,
                    ch.label(), ch.confidence(), ch.appliedRules()));
        }
        return result;
    }

    /**
     * 원문에서 target 의 위치를 찾는다. 단계별 fallback:
     *   1) 정확 매칭 — cursor 이후
     *   2) 정확 매칭 — 처음부터 (Gemini 가 순서를 어겼거나 앞쪽 매칭일 때)
     *   3) 공백-tolerant 매칭 — Gemini 가 'executivesummary' 같은 입력을 'executive summary' 로
     *      normalize 해서 보고하는(또는 그 역방향) 케이스 복구
     * 못 찾으면 null.
     */
    private int[] findOriginalRange(String source, String target, int cursor) {
        int found = source.indexOf(target, cursor);
        if (found >= 0) return new int[]{found, found + target.length()};

        found = source.indexOf(target);
        if (found >= 0) {
            log.debug("Change out of document order, matched via full scan: '{}'", target);
            return new int[]{found, found + target.length()};
        }

        // 공백-tolerant 매칭: 양쪽 모두에서 모든 whitespace 를 제거 후 비교.
        // target 자체에 whitespace 가 없거나 전부 whitespace 면 의미 없으므로 skip.
        String targetNoWs = target.replaceAll("\\s+", "");
        if (targetNoWs.isEmpty() || targetNoWs.length() == target.length()) return null;

        StripResult ws = stripWhitespace(source);
        int normPos = ws.stripped().indexOf(targetNoWs);
        if (normPos < 0) return null;

        int actualStart = ws.map()[normPos];
        int actualEnd = ws.map()[normPos + targetNoWs.length() - 1] + 1;
        log.debug("Recovered via whitespace-tolerant match: '{}' -> [{},{})", target, actualStart, actualEnd);
        return new int[]{actualStart, actualEnd};
    }

    /** 공백을 제거한 문자열 + 각 char 가 원문에서 어느 인덱스였는지 매핑. */
    private StripResult stripWhitespace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int[] map = new int[s.length()];
        int j = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
                map[j++] = i;
            }
        }
        return new StripResult(sb.toString(), Arrays.copyOf(map, j));
    }

    private record StripResult(String stripped, int[] map) {
    }

    private boolean overlapsProtected(int start, int end, List<Range> ranges) {
        if (ranges == null) return false;
        for (Range r : ranges) {
            if (start < r.getEnd() && r.getStart() < end) return true;
        }
        return false;
    }

    // === 요청/응답 공통 ===

    private String callAndExtract(String systemInstruction, String userText, Map<String, Object> schema) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userText))
        )));
        body.put("generationConfig", Map.of(
                "responseMimeType", "application/json",
                "responseJsonSchema", schema
        ));

        String path = "/models/" + properties.model() + ":generateContent";
        GeminiResponse response = geminiRestClient.post()
                .uri(uri -> uri.path(path).build())
                .header("x-goog-api-key", properties.apiKey())
                .body(body)
                .retrieve()
                .body(GeminiResponse.class);

        if (response == null
                || response.candidates() == null
                || response.candidates().isEmpty()
                || response.candidates().get(0).content() == null
                || response.candidates().get(0).content().parts() == null
                || response.candidates().get(0).content().parts().isEmpty()) {
            throw new IllegalStateException("Empty or malformed Gemini response");
        }
        String text = response.candidates().get(0).content().parts().get(0).text();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Gemini response text is empty");
        }
        return text;
    }

    private String buildCorrectionUserMessage(Receiver receiver, Purpose purpose, String preparedOriginal) {
        return "[Receiver] " + receiver + '\n'
                + "[Purpose] " + purpose + '\n'
                + "[OriginalEmail]\n" + preparedOriginal;
    }

    private String buildFinalizeUserMessage(Receiver receiver, Purpose purpose, String mergedText) {
        return "[Receiver] " + receiver + '\n'
                + "[Purpose] " + purpose + '\n'
                + "[MergedText]\n" + mergedText;
    }

    private Map<String, Object> correctionSchema() {
        Map<String, Object> changeProps = new LinkedHashMap<>();
        changeProps.put("index", Map.of("type", "integer"));
        changeProps.put("original", Map.of("type", "string"));
        changeProps.put("corrected", Map.of("type", "string"));
        changeProps.put("reason", Map.of("type", "string"));
        changeProps.put("label", Map.of("type", "string", "enum", List.of("AUTO", "SUGGEST", "STYLE")));
        changeProps.put("confidence", Map.of("type", "number"));
        changeProps.put("applied_rules", Map.of(
                "type", "array",
                "items", Map.of("type", "string")
        ));

        Map<String, Object> changeItem = new LinkedHashMap<>();
        changeItem.put("type", "object");
        changeItem.put("properties", changeProps);
        changeItem.put("required", List.of("index", "original", "corrected",
                "reason", "label", "confidence", "applied_rules"));

        Map<String, Object> rootProps = new LinkedHashMap<>();
        rootProps.put("corrected_email", Map.of("type", "string"));
        rootProps.put("changes", Map.of("type", "array", "items", changeItem));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", rootProps);
        root.put("required", List.of("corrected_email", "changes"));
        return root;
    }

    private Map<String, Object> finalizeSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("ai_final", Map.of("type", "string"));
        props.put("ai_subject", Map.of("type", "string"));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("ai_final", "ai_subject"));
        return root;
    }

    private record GeminiResponse(List<Candidate> candidates) {
        private record Candidate(Content content) {
        }

        private record Content(List<Part> parts) {
        }

        private record Part(String text) {
        }
    }
}
