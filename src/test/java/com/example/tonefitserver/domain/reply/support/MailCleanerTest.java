package com.example.tonefitserver.domain.reply.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MailCleaner 단위 테스트 — Spring/Docker 불필요 (순수 함수).
 */
class MailCleanerTest {

    @Test
    @DisplayName("Gmail 인용 trail 이 별도 메일과 중복되면 제거되고 recovered 는 비어 있다")
    void dedupRemovesQuotedPrevious() {
        String mail1 = """
                안녕하세요 김선임님,
                지난주 요청드린 견적서 검토 부탁드립니다.
                기한은 이번 주 금요일까지입니다.
                감사합니다.""";
        String mail2 = """
                팀장님, 검토 완료했습니다.
                두 가지 의견이 있어 회신드립니다.

                2026년 6월 1일 (월) 오후 3:01, 박팀장 <park@x.com>님이 작성:
                > 안녕하세요 김선임님,
                > 지난주 요청드린 견적서 검토 부탁드립니다.
                > 기한은 이번 주 금요일까지입니다.
                > 감사합니다.""";

        MailCleaner.CleanResult result = MailCleaner.clean(List.of(mail1, mail2));

        assertEquals(2, result.mails().size());
        assertFalse(result.mails().get(1).contains("견적서 검토 부탁드립니다"));
        assertTrue(result.mails().get(1).contains("두 가지 의견이 있어"));
        assertEquals("", result.recoveredContext());
    }

    @Test
    @DisplayName("긁지 못한 이전 대화가 trail 에만 있으면 recovered context 로 복원된다")
    void uniqueTrailRecovered() {
        String onlyMail = """
                네 팀장님, 일정 조정 가능합니다.
                수요일 오후로 옮기겠습니다.

                2026년 6월 1일 (월) 오후 3:01, 박팀장 <park@x.com>님이 작성:
                > 김선임님, 내일 회의를 수요일로 옮기려고 하는데 가능한가요?
                > 고객사 미팅이 겹쳐서 부득이하게 조정이 필요합니다.
                > 가능 여부 회신 부탁드립니다.""";

        MailCleaner.CleanResult result = MailCleaner.clean(List.of(onlyMail));

        assertTrue(result.recoveredContext().contains("수요일로 옮기려고"));
        assertTrue(result.recoveredContext().contains("고객사 미팅이 겹쳐서"));
        assertFalse(result.mails().get(0).contains("수요일로 옮기려고"));
    }

    @Test
    @DisplayName("RFC 3676 서명 구분자와 모바일 꼬리말은 제거되고 일반 맺음말은 보존된다")
    void signatureStripped() {
        String mail = """
                보고서 초안 공유드립니다.
                검토 부탁드립니다. 감사합니다.
                --
                김선임 | 영업팀
                010-1234-5678
                iPhone에서 보냄""";

        MailCleaner.CleanResult result = MailCleaner.clean(List.of(mail));

        String cleaned = result.mails().get(0);
        assertFalse(cleaned.contains("010-1234-5678"));
        assertFalse(cleaned.contains("iPhone에서 보냄"));
        assertTrue(cleaned.contains("감사합니다"));
    }

    @Test
    @DisplayName("미중복 trail 이라도 너무 짧으면 노이즈로 버린다")
    void shortNoiseDiscarded() {
        String mail = """
                확인했습니다.

                2026년 6월 1일 (월) 오후 3:01, 박팀장 <park@x.com>님이 작성:
                > 넵""";

        MailCleaner.CleanResult result = MailCleaner.clean(List.of(mail));

        assertEquals("", result.recoveredContext());
    }

    @Test
    @DisplayName("마커가 없는 메일은 본문이 그대로 보존된다")
    void noMarkerUnchanged() {
        String mail = """
                안녕하세요.
                다음 분기 계획 초안을 공유드립니다.
                1 > 2 같은 부등호 한 줄은 인용이 아닙니다.
                의견 부탁드립니다.""";

        MailCleaner.CleanResult result = MailCleaner.clean(List.of(mail));

        assertTrue(result.mails().get(0).contains("다음 분기 계획"));
        assertTrue(result.mails().get(0).contains("의견 부탁드립니다"));
        assertEquals("", result.recoveredContext());
    }

    @Test
    @DisplayName("Outlook 헤더 블록(보낸 사람: + 날짜:)도 trail 경계로 인식한다")
    void outlookHeaderBlock() {
        String mail1 = "견적 단가 회신 부탁드립니다.\n납기는 6월 말입니다.";
        String mail2 = """
                단가표 첨부드립니다. 확인 부탁드립니다.

                보낸 사람: 박팀장 <park@x.com>
                보낸 날짜: 2026년 6월 1일 월요일 오후 3:01
                받는 사람: 김선임
                제목: RE: 견적 요청

                견적 단가 회신 부탁드립니다.
                납기는 6월 말입니다.""";

        MailCleaner.CleanResult result = MailCleaner.clean(List.of(mail1, mail2));

        assertFalse(result.mails().get(1).contains("견적 단가 회신"));
        assertTrue(result.mails().get(1).contains("단가표 첨부드립니다"));
        assertEquals("", result.recoveredContext());
    }
}
