package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.domain.correction.model.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 교정 클라이언트(Gemini·OpenAI) 공용 후처리·요청 조립. provider 무관 로직을 한곳에 둔다.
 *
 * <ul>
 *   <li>보호 구간 마커(⟦…⟧) 삽입 — 송신</li>
 *   <li>changes 정제 — original 위치 탐색(cursor 순서 + 공백 무시 fallback), 보호 구간 침범 drop, 마커 strip</li>
 *   <li>입력 메시지 조립, changes 스키마 item, 파싱 DTO</li>
 * </ul>
 */
public final class CorrectionSupport {

    private static final Logger log = LoggerFactory.getLogger(CorrectionSupport.class);

    public static final String START_MARKER = "⟦";
    public static final String END_MARKER = "⟧";

    private CorrectionSupport() {
    }

    // === 송신 변환 ===

    public static String insertMarkers(String text, List<Range> ranges) {
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

    public static String buildUserMessage(Receiver receiver, String preparedOriginal) {
        return "[Receiver] " + receiver + '\n'
                + "[OriginalEmail]\n" + preparedOriginal;
    }

    // === 수신 변환 ===

    public static List<AiCorrectionResult.Change> sanitizeChanges(String original,
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
                log.warn("Dropping change (original text not found in source)");
                continue;
            }
            int start = range[0];
            int end = range[1];

            if (overlapsProtected(start, end, protectedRanges)) {
                log.warn("Dropping change (overlaps protected range): [{},{})", start, end);
                continue;
            }

            cursor = end;
            // 원문에서 실제로 발췌한 substring 을 사용 — 모델이 공백 normalize 등을 했어도
            // UI/머지에는 사용자가 실제로 입력한 형태가 들어가야 함.
            String actualOriginal = original.substring(start, end);
            result.add(new AiCorrectionResult.Change(
                    result.size(), start, end,
                    actualOriginal, cleanCorrected, cleanReason,
                    ch.label(), ch.confidence(), ch.appliedRules()));
        }
        return result;
    }

    private static String restoreFromAi(String aiText) {
        if (aiText == null) return null;
        return aiText
                .replace(START_MARKER, "")
                .replace(END_MARKER, "");
    }

    /**
     * 원문에서 target 의 위치를 찾는다. 단계별 fallback:
     *   1) 정확 매칭 — cursor 이후
     *   2) 정확 매칭 — 처음부터 (모델이 순서를 어겼거나 앞쪽 매칭일 때)
     *   3) 공백-tolerant 매칭 — 모델이 'executivesummary' 를 'executive summary' 로 normalize(또는 역) 한 케이스 복구
     * 못 찾으면 null.
     */
    private static int[] findOriginalRange(String source, String target, int cursor) {
        int found = source.indexOf(target, cursor);
        if (found >= 0) return new int[]{found, found + target.length()};

        found = source.indexOf(target);
        if (found >= 0) {
            return new int[]{found, found + target.length()};
        }

        // 공백-tolerant 매칭: 양쪽 모두에서 모든 whitespace 제거 후 비교.
        String targetNoWs = target.replaceAll("\\s+", "");
        if (targetNoWs.isEmpty() || targetNoWs.length() == target.length()) return null;

        StripResult ws = stripWhitespace(source);
        int normPos = ws.stripped().indexOf(targetNoWs);
        if (normPos < 0) return null;

        int actualStart = ws.map()[normPos];
        int actualEnd = ws.map()[normPos + targetNoWs.length() - 1] + 1;
        return new int[]{actualStart, actualEnd};
    }

    /** 공백을 제거한 문자열 + 각 char 가 원문에서 어느 인덱스였는지 매핑. */
    private static StripResult stripWhitespace(String s) {
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

    private static boolean overlapsProtected(int start, int end, List<Range> ranges) {
        if (ranges == null) return false;
        for (Range r : ranges) {
            if (start < r.getEnd() && r.getStart() < end) return true;
        }
        return false;
    }

    // === 스키마 / 파싱 DTO ===

    /**
     * {reasoning, changes} 스키마의 change item 객체 (type/properties/required). 매 호출 새 Map — 호출자가
     * root 로 감싸고 필요 시 provider 별 속성(예: OpenAI strict 의 additionalProperties)을 추가한다.
     */
    public static Map<String, Object> changeItemSchema() {
        Map<String, Object> changeProps = new LinkedHashMap<>();
        changeProps.put("index", Map.of("type", "integer"));
        changeProps.put("original", Map.of("type", "string"));
        changeProps.put("corrected", Map.of("type", "string"));
        changeProps.put("reason", Map.of("type", "string"));
        changeProps.put("label", Map.of("type", "string", "enum", List.of("AUTO", "SUGGEST", "STYLE")));
        changeProps.put("confidence", Map.of("type", "number"));
        changeProps.put("applied_rules", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> changeItem = new LinkedHashMap<>();
        changeItem.put("type", "object");
        changeItem.put("properties", changeProps);
        changeItem.put("required", List.of("index", "original", "corrected",
                "reason", "label", "confidence", "applied_rules"));
        return changeItem;
    }

    /** 교정 응답 파싱용 — reasoning(선행 CoT, 폐기) + changes. */
    public record CorrectionRaw(String reasoning, List<AiCorrectionResult.Change> changes) {
    }
}
