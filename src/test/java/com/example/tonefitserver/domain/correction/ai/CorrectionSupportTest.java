package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.domain.correction.model.Label;
import com.example.tonefitserver.domain.correction.model.Range;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CorrectionSupport 후처리 단위 테스트 — Gemini 클라이언트에서 추출한 로직 잠금.
 */
class CorrectionSupportTest {

    private AiCorrectionResult.Change change(String original, String corrected) {
        return new AiCorrectionResult.Change(0, null, null, original, corrected, "사유", Label.AUTO, 0.9, List.of());
    }

    @Test
    @DisplayName("원문에 존재하는 change 는 start/end 를 채워 반환")
    void mapsFoundChange() {
        String original = "안녕하세요. 회신 바랍니다.";
        List<AiCorrectionResult.Change> out = CorrectionSupport.sanitizeChanges(
                original, null, List.of(change("회신 바랍니다", "회신 부탁드립니다")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).corrected()).isEqualTo("회신 부탁드립니다");
        assertThat(original.substring(out.get(0).start(), out.get(0).end())).isEqualTo("회신 바랍니다");
    }

    @Test
    @DisplayName("원문에 없는 change 는 drop")
    void dropsNotFound() {
        List<AiCorrectionResult.Change> out = CorrectionSupport.sanitizeChanges(
                "안녕하세요.", null, List.of(change("없는문구", "x")));
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("AI 가 띄어 보고한 target 은 붙어 있는 원문에 whitespace-tolerant 로 복구")
    void recoversWhitespaceTolerant() {
        // 원문은 붙어 있고("executivesummary"), AI 가 "executive summary" 로 띄어 보고한 케이스.
        // (반대 방향 — target 에 공백이 없는 경우 — 은 오탐 방지로 복구하지 않는 것이 설계된 동작.)
        String original = "executivesummary 입니다";
        List<AiCorrectionResult.Change> out = CorrectionSupport.sanitizeChanges(
                original, null, List.of(change("executive summary", "요약")));
        assertThat(out).hasSize(1);
        assertThat(original.substring(out.get(0).start(), out.get(0).end())).isEqualTo("executivesummary");
    }

    @Test
    @DisplayName("보호 구간과 겹치는 change 는 drop")
    void dropsProtectedOverlap() {
        String original = "가나다 회신 바랍니다 라마바";
        int start = original.indexOf("회신");
        int end = original.indexOf("바랍니다") + "바랍니다".length();
        List<AiCorrectionResult.Change> out = CorrectionSupport.sanitizeChanges(
                original, List.of(new Range(start, end)), List.of(change("회신 바랍니다", "회신 부탁드립니다")));
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("insertMarkers 는 보호 구간을 ⟦⟧ 로 감싼다")
    void insertMarkersWraps() {
        String original = "가나다 라마바";
        int start = original.indexOf("라마바");
        int end = start + "라마바".length();
        String marked = CorrectionSupport.insertMarkers(original, List.of(new Range(start, end)));
        assertThat(marked).isEqualTo("가나다 ⟦라마바⟧");
    }
}
