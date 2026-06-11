package com.example.tonefitserver.domain.reply.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 받은 메일 대화의 기계적 정리 (FUNC-Rep-04 — "중복으로 따라붙은 인용·서명을 지우는
 * 기계적 정리는 서버 코드가 하고"). 의미 파악은 AI(파악 단계) 몫.
 *
 * <p>원칙: <b>"인용이라서"가 아니라 "중복이라서" 지운다.</b>
 * <ul>
 *   <li>각 메일을 본문 / 인용 trail 로 분리 (인용 도입부 마커 기준)</li>
 *   <li>본문(세그먼트 0)은 절대 삭제하지 않음 — 오삭제가 누락보다 훨씬 나쁘다</li>
 *   <li>trail 중 다른 메일 본문과 중복되는 줄만 버리고, <b>중복 아닌 trail 내용은
 *       recovered context 로 보존</b> — FE 가 3건을 못 긁었을 때 이전 대화의 fallback 소스
 *       (Gmail 은 최신 메일 하단에 대화 이력이 따라붙음)</li>
 *   <li>서명은 보수적 패턴만 제거 (RFC 3676 "--" 구분자, "Sent from my ..." 류)</li>
 * </ul>
 *
 * <p>정리는 토큰 비용 절감 + 파악 품질 방어가 목적이라 보수적 임계로 운영한다.
 * 남은 중복·노이즈는 요약·파악 AI 가 흡수한다.
 */
public final class MailCleaner {

    /** 정리 결과 — 본문만 남긴 메일들 + 인용에서 복원한 미중복 이전 대화 (없으면 ""). */
    public record CleanResult(List<String> mails, String recoveredContext) {
    }

    /** 한국 Gmail 인용 도입부: "2026년 6월 1일 (월) 오후 3:01, 홍길동 <a@b.c>님이 작성:" */
    private static final Pattern KO_WROTE = Pattern.compile("^.{0,160}님이\\s*작성:?\\s*$");
    /** 영문 Gmail: "On Mon, Jun 1, 2026 at 3:01 PM Hong <a@b.c> wrote:" */
    private static final Pattern EN_WROTE = Pattern.compile("^On .{0,160}wrote:\\s*$");
    /** Outlook 류 원본 구분선 */
    private static final Pattern ORIGINAL_MSG = Pattern.compile(
            "(?i)^-{2,}\\s*(Original Message|원본\\s*(이메일|메일|메시지))\\s*-{2,}\\s*$");
    /** 헤더 블록 시작 후보 — 다음 몇 줄에 날짜/수신 헤더가 따라올 때만 trail 경계로 인정 */
    private static final Pattern HEADER_FROM = Pattern.compile("^(From|보낸\\s*사람|보낸이)\\s*:.*$");
    private static final Pattern HEADER_FOLLOW = Pattern.compile(
            "^(Sent|Date|To|Cc|Subject|받는\\s*사람|보낸\\s*날짜|날짜|참조|제목)\\s*:.*$");
    /** 인용 헤더성 줄 — recovered context 에서 제외 */
    private static final Pattern HEADER_ANY = Pattern.compile(
            "^(From|Sent|Date|To|Cc|Subject|보낸\\s*사람|보낸이|받는\\s*사람|보낸\\s*날짜|날짜|참조|제목)\\s*:.*$");

    /** RFC 3676 서명 구분자 */
    private static final Pattern SIG_DELIMITER = Pattern.compile("^--\\s*$");
    /** 모바일 클라이언트 꼬리말 */
    private static final Pattern SENT_FROM = Pattern.compile(
            "^(Sent from my .{0,40}|.{0,40}에서 보냄|.{0,40}에서 보낸 메일)\\s*$");

    /** 서명 구분자를 본문 끝으로 인정하는 탐색 범위 (마지막 N줄) */
    private static final int SIG_SEARCH_TAIL_LINES = 15;
    /** recovered context 최소 길이 — 이보다 짧으면 노이즈로 보고 버림 (한국어는 글자당 정보량이 커 보수적으로) */
    private static final int RECOVERED_MIN_LENGTH = 40;

    private MailCleaner() {
    }

    /**
     * @param rawBodies 메일 본문들 — 시간순(오래된 → 최신), FE 가 가공 없이 보낸 raw 텍스트
     */
    public static CleanResult clean(List<String> rawBodies) {
        List<List<String>> ownParts = new ArrayList<>();
        List<List<String>> trailParts = new ArrayList<>();

        for (String body : rawBodies) {
            List<String> lines = List.of((body == null ? "" : body).split("\n", -1));
            int trailStart = findTrailStart(lines);
            List<String> own = new ArrayList<>(trailStart < 0 ? lines : lines.subList(0, trailStart));
            List<String> trail = trailStart < 0 ? List.of() : lines.subList(trailStart, lines.size());
            stripSignature(own);
            ownParts.add(own);
            trailParts.add(trail);
        }

        // 모든 메일 본문 줄의 정규화 집합 — trail 중복 판정 기준
        Set<String> ownLineSet = new LinkedHashSet<>();
        for (List<String> own : ownParts) {
            for (String line : own) {
                String n = normalize(line);
                if (!n.isEmpty()) ownLineSet.add(n);
            }
        }

        // 미중복 trail 줄 복원 (마커·헤더 줄 제외, 순서 유지·중복 제거)
        Set<String> recovered = new LinkedHashSet<>();
        for (List<String> trail : trailParts) {
            for (String line : trail) {
                if (isMarkerLine(line) || HEADER_ANY.matcher(line.trim()).matches()) continue;
                String n = normalize(line);
                if (n.isEmpty() || ownLineSet.contains(n)) continue;
                recovered.add(n);
            }
        }
        String recoveredText = String.join("\n", recovered);
        if (recoveredText.length() < RECOVERED_MIN_LENGTH) {
            recoveredText = "";
        }

        List<String> cleanedMails = ownParts.stream()
                .map(own -> String.join("\n", own).strip())
                .toList();
        return new CleanResult(cleanedMails, recoveredText);
    }

    /** 인용 trail 시작 줄 인덱스. 없으면 -1. */
    private static int findTrailStart(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (KO_WROTE.matcher(t).matches()
                    || EN_WROTE.matcher(t).matches()
                    || ORIGINAL_MSG.matcher(t).matches()) {
                return i;
            }
            // From:/보낸 사람: 단독으론 본문 오탐 여지 → 4줄 내 후속 헤더 동반 시에만 경계 인정
            if (HEADER_FROM.matcher(t).matches()) {
                for (int j = i + 1; j < Math.min(i + 5, lines.size()); j++) {
                    if (HEADER_FOLLOW.matcher(lines.get(j).trim()).matches()) return i;
                }
            }
            // '>' 인용 런 — 본문에 우연히 한 줄 있을 수 있어 2줄 연속일 때만 경계 인정
            if (t.startsWith(">") && i + 1 < lines.size()
                    && lines.get(i + 1).trim().startsWith(">")) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isMarkerLine(String line) {
        String t = line.trim();
        return KO_WROTE.matcher(t).matches()
                || EN_WROTE.matcher(t).matches()
                || ORIGINAL_MSG.matcher(t).matches();
    }

    /**
     * 본문 끝 서명 제거 — 보수적: 마지막 {@value SIG_SEARCH_TAIL_LINES}줄 안의 "--" 구분자
     * 이후 전부, 그리고 끝쪽 "Sent from my ..."/"...에서 보냄" 줄만.
     * ("감사합니다" 같은 일반 맺음말은 본문이므로 건드리지 않는다)
     */
    private static void stripSignature(List<String> own) {
        int from = Math.max(0, own.size() - SIG_SEARCH_TAIL_LINES);
        for (int i = own.size() - 1; i >= from; i--) {
            if (SIG_DELIMITER.matcher(own.get(i).trim()).matches()) {
                own.subList(i, own.size()).clear();
                break;
            }
        }
        while (!own.isEmpty()) {
            String last = own.get(own.size() - 1).trim();
            if (last.isEmpty() || SENT_FROM.matcher(last).matches()) {
                own.remove(own.size() - 1);
            } else {
                break;
            }
        }
    }

    /** 인용 프리픽스('>')·공백 정규화 — 중복 판정용. */
    private static String normalize(String line) {
        String s = line;
        while (!s.isEmpty() && (s.charAt(0) == '>' || s.charAt(0) == ' ' || s.charAt(0) == '\t')) {
            s = s.substring(1);
        }
        return s.strip().replaceAll("\\s+", " ");
    }
}
