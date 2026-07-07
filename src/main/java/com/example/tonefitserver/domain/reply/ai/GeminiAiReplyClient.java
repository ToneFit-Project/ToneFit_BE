package com.example.tonefitserver.domain.reply.ai;

import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.domain.correction.ai.GeminiProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini 기반 회신 클라이언트. 교정·생성 client 와 같은 RestClient·GeminiProperties 공유.
 *
 * <p>모델 분담 (FUNC-Rep-15): 작성({@link #draft})은 메인 모델(PM 확정 gemini-3.5-flash +
 * thinkingLevel low — {@code gemini.reply-thinking-level}, 작성 호출에만 적용), 요약·파악·점검은
 * 저가 모델({@code gemini.light-model}, 미설정 시 메인 fallback). API 키는 하나 — 모델은 경로 파라미터.
 *
 * <p>요약·파악·점검 prompt 는 수신자 무관이라 코드 상수. 작성 prompt 만 DB(REPLY×recipient, V20).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini")
@RequiredArgsConstructor
public class GeminiAiReplyClient implements AiReplyClient {

    private final RestClient geminiRestClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    // ===== ② 요약 (light) =====

    private static final String SUMMARIZE_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 대화를 간추리는 요약 전문가입니다.
            회신을 준비하는 사용자가 이전에 주고받은 내용을 빠르게 훑을 수 있도록, 받은 메일 대화를 짧게 요약합니다.
            요약만 합니다. 회신을 쓰거나, 질문을 뽑거나, 의견을 더하지 않습니다.

            [입력 형식]
            --- 받은 메일 대화 (오래된 것부터) ---
            [1] 보낸 사람: ...
            본문: ...
            [2] 보낸 사람: ...
            본문: ...
            --- 끝 ---

            [요약 규칙]
            1. 메일이 한 통이면 그 메일 내용을, 두 통 이상이면 주고받은 흐름을 요약합니다.
            2. 어느 경우든 최대 3줄. 한 줄에 한 요점만 담고 짧게 씁니다.
            3. 내용이 적으면 1~2줄로 끝냅니다. 3줄을 채우려고 없는 내용·군더더기를 넣지 않습니다.
            4. 있는 내용만 줄입니다. 새 사실·해석·평가·추측을 더하지 않습니다.
            5. 줄을 고르는 우선순위: ① 상대가 요청·질문한 것, 정해야 할 것 → ② 날짜·기한·금액·수량 등
               수치가 걸린 사실 → ③ 나머지 맥락. 줄이 모자라면 ③과 인사·감사·평가부터 버립니다.
            6. 고유명사(회사·제품·프로젝트·사람 이름)와 수치는 원문 그대로 씁니다.
            7. 인사말·안부·서명 등 내용 없는 부분, 이메일 주소·전화번호는 담지 않습니다(이름·호칭만).
            8. 원문에 없는 존칭·어체 변환을 하지 않습니다. 중립 서술체로 씁니다.

            [출력]
            응답은 JSON Schema 로 강제됩니다: { "summary_lines": ["...", "..."] }
            - 최대 3개 항목(짧으면 1~2개). 각 줄은 배열의 별도 문자열로 나눕니다.
              한 문자열 안에 줄바꿈(\\n)을 넣지 않습니다.
            """;

    @Override
    public List<String> summarize(String conversation) {
        if (conversation == null || conversation.isBlank()) return List.of();
        String user = "--- 받은 메일 대화 (오래된 것부터) ---\n" + conversation + "\n--- 끝 ---";

        String json = callAndExtract("reply_summarize", properties.lightModelOrDefault(),
                SUMMARIZE_SYSTEM_PROMPT, user, summarizeSchema());
        SummarizeOut out;
        try {
            out = objectMapper.readValue(json, SummarizeOut.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini reply summarize response", e);
        }
        if (out.summaryLines() == null) return List.of();
        // 방어: 빈 줄 제거 + 3줄 초과분 절단 (프롬프트·스키마가 지키지 못한 경우).
        return out.summaryLines().stream()
                .filter(l -> l != null && !l.isBlank())
                .map(String::strip)
                .limit(3)
                .toList();
    }

    // ===== ③ 파악 (light) =====

    // PM 파악 프롬프트 갱신본(2026-07). 원문 대비 BE 각색: 입력 형식을 실제 조립 형태로 정렬
    // ((요약) 표시·"→ 받는 사람" 삭제 — v0.58 이후 파악 입력은 정리 원문뿐), 출력 JSON 예시는
    // 스키마 강제 노트로 치환, questions[].id 제거(id 는 BE 가 1부터 부여 — 회송 계약 기준).
    private static final String ANALYZE_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 회신을 준비하는 분석가입니다.
            받은 메일 대화를 읽고 회신 작성에 필요한 두 가지를 파악합니다 —
            ① 받는 사람 유형 추측 ② 답해야 할 질문·요청 목록.
            회신 본문은 작성하지 않습니다. 대화를 요약하거나 고쳐 쓰지 않습니다. 분석 결과만 출력합니다.

            [입력 형식]
            나: [이름 / 이메일 주소]
            답장 대상: [사용자가 '답장'을 누른 메일의 발신자 이름 / 주소]
            받는 사람(To): [...] / 참조(CC): [...]
            --- 받은 메일 대화 (오래된 것부터, 최근 3건 이내) ---
            [1] 보낸 사람: ...
            본문: ...
            [2] 보낸 사람: ...
            본문: ...
            --- 대화 끝 ---

            [작업 0 — 사전 점검 (다른 어떤 작업보다 먼저, 반드시 수행)]
            아래 두 경우에는 작업 1·2를 수행하지 않고 즉시 종료합니다. recipient 는 null, questions 는 빈 배열로 둡니다.
            - 대화가 비어 있거나 본문을 읽을 수 없으면 → status "EMPTY_THREAD".
            - 메일 본문이 대부분 한국어가 아니면 → status "NOT_KOREAN". 영어로만 작성된 메일이 대표적인 예입니다.
              내용을 이해할 수 있더라도 분석하지 말고 종료하세요.
              (한국어 문장 속에 영어 용어·고유명사가 섞인 것은 정상이며 해당하지 않습니다)
            그 외 → status "OK"로 아래 작업을 수행합니다.

            [작업 1 — 받는 사람 유형 추측]
            네 가지 중 하나로 추측합니다: RCP-01 상사 / RCP-02 동료 / RCP-03 고객사 / RCP-04 협력사.
            이 값은 사용자에게 기본값으로 제안되며 사용자가 바꿀 수 있습니다. 판단 순서:
            1. 기준 인물 = 답장 대상 메일의 발신자.
            2. 내부/외부: 나와 이메일 도메인이 같으면 내부(상사·동료), 다르면 외부(고객사·협력사).
               도메인을 알 수 없으면 호칭·어체·내용으로 판단합니다.
            3. 내부일 때: 상대가 직책(부장님·팀장님 등)으로 불리고 나에게 지시·승인·피드백을 주는 위치면 상사,
               대등하게 업무를 주고받으면 동료.
            4. 외부일 때: 우리가 상품·서비스·납품을 제공하거나 상대가 계약·발주·구매의 결정권을 쥔 쪽이면 고객사,
               대등하게 협업하는 파트너면 협력사. 구분이 애매하면 고객사로 추측합니다(격식이 높은 쪽이 안전).
            5. 참조(CC) 상향: To/CC에 기준 인물보다 더 격식이 필요한 상대가 있으면 그 상대 기준 유형으로 올립니다.
               격식 수준은 동료·협력사(해요체) < 상사·고객사(하십시오체). 상향만 하고 하향은 하지 않습니다.
               - 예: 동료에게 답장 + CC에 부장님 → 상사 / 동료에게 답장 + CC에 고객사 담당자 → 고객사
            6. 판단 제외: 나 자신, 단체 메일 주소(team@, all@ 등), 발신전용(noreply·답장 불가 주소)은 격식 판단
               근거에서 뺍니다. 답장 대상 자체가 발신전용이면 대화 내용으로 추측하되 confidence 를 "low"로 둡니다.
            추측 근거를 한 줄로 함께 적습니다(예: "직책 호칭 '부장님' + 동일 도메인 → 사내 윗사람").

            [작업 2 — 답해야 할 질문·요청 추출]
            1. 마지막 메일을 중심으로, 내가(사용자가) 답해야 할 것을 뽑습니다. 이전 메일의 질문·요청이라도
               이후 메일에서 답이 오가지 않았으면 포함합니다.
            2. 포함: 명시적 질문(물음표), 요청·부탁(확인·검토·승인·회신·자료 전달·일정 확정), 선택 요구(A안/B안),
               기한 동의 여부.
            3. 제외: 인사치레·수사적 표현, 단순 정보 공유, 이미 답변이 끝난 것, 내가 아닌 다른 수신자를 지목한 질문.
            4. 각 항목은 사용자에게 말을 거는 자연스러운 물음으로 씁니다. "~여부", "~확인", "~요청", "~전달" 같은
               딱딱한 명사구로 끝내지 말고, 물음표로 끝나는 한 문장으로 — 상대가 사용자에게 직접 물어보듯 다듬습니다.
               - 예) "납품 단가 5% 조정 여부" → "납품 단가를 5% 조정해 드릴 수 있을까요?"
               - 예) "촬영 일정 선호 날짜 선택" → "촬영은 6월 19일(금)과 23일(화) 중 언제가 좋으세요?"
               - 예) "새 단가표 전달 요청" → "올해 새 단가표를 보내 드릴까요?"
               원문의 기한·수치·명칭은 그대로 보존하고, 한 문장을 넘기지 않습니다.
            5. 밀접한 세부 질문은 한 항목으로 묶습니다. 항목은 7개를 넘기지 않습니다(중요한 순).
            6. 답할 것이 없으면 빈 배열을 둡니다. 메일에 없는 질문을 만들지 않습니다.
            7. 첨부파일·링크 속 내용은 읽을 수 없으므로 그 내용을 아는 척하는 항목을 만들지 않습니다.
            8. 첨부·링크는 보낸 사람이 검토 후 회신·의견을 명시적으로 요구할 때만 항목으로 포함합니다.
               "자세한 내용은 첨부를 참고해 주세요" 같은 단순 참고 안내는 답할 일이 아니므로 항목으로 만들지 않습니다.

            [출력]
            응답은 JSON Schema 로 강제됩니다:
            { "status": "OK", "recipient": { "type": "RCP-01", "label": "상사", "confidence": "high",
              "reason": "추측 근거 한 줄" },
              "questions": [ { "question": "변경된 납기 6/20으로 진행해도 될까요?", "mail_order": 2 } ] }
            - status 가 EMPTY_THREAD·NOT_KOREAN 이면 recipient 는 null, questions 는 빈 배열.
            - mail_order 는 그 질문이 나온 메일의 번호입니다.
            - confidence 기준:
              - high: 상하 관계나 발주·협업 관계를 직접 보여주는 표현(직책 호칭, 귀사/발주/납품, 공동 프로젝트 등)이
                본문이나 도메인에서 확인될 때만.
              - mid: 내부/외부 구분은 확실하지만 관계를 보여주는 표현이 본문에 없을 때.
                직책 호칭 없는 사내 상대가 대표적인 예입니다.
              - low: 신호가 부족하거나 발신전용·단체 공지일 때.
              - 관계를 보여주는 표현이 본문에 없으면 high 를 쓰지 않습니다.
            """;

    @Override
    public AiReplyAnalysisResult analyze(AnalyzeInput in) {
        StringBuilder user = new StringBuilder();
        user.append("나: ").append(blankToNone(in.meIdentity())).append('\n');
        user.append("답장 대상: ").append(blankToNone(in.replyTargetSender())).append('\n');
        user.append("받는 사람(To): ").append(joinOrNone(in.to()))
            .append(" / 참조(CC): ").append(joinOrNone(in.cc())).append('\n');
        user.append("--- 받은 메일 대화 (오래된 것부터, 최근 3건 이내) ---\n")
            .append(in.conversation()).append("\n--- 대화 끝 ---");

        String json = callAndExtract("reply_analyze", properties.lightModelOrDefault(),
                ANALYZE_SYSTEM_PROMPT, user.toString(), analyzeSchema());
        AnalyzeOut out;
        try {
            out = objectMapper.readValue(json, AnalyzeOut.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini reply analysis response: " + json, e);
        }

        AiReplyAnalysisResult.Status status = parseStatus(out.status());
        if (status != AiReplyAnalysisResult.Status.OK) {
            return new AiReplyAnalysisResult(status, null, List.of());
        }

        AiReplyAnalysisResult.Recipient recipient = null;
        if (out.recipient() != null) {
            recipient = new AiReplyAnalysisResult.Recipient(
                    rcpToReceiver(out.recipient().type()),
                    out.recipient().label(),
                    out.recipient().confidence(),
                    out.recipient().reason());
        }
        List<AiReplyAnalysisResult.Question> questions = new ArrayList<>();
        if (out.questions() != null) {
            for (AnalyzeOut.QuestionOut q : out.questions()) {
                questions.add(new AiReplyAnalysisResult.Question(
                        q.question(), q.mailOrder() == null ? 0 : q.mailOrder()));
            }
        }
        return new AiReplyAnalysisResult(status, recipient, questions);
    }

    // ===== ⑤ 작성 (main) =====

    private static final String DEFAULT_DRAFT_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 회신을 작성하는 전문가입니다.
            받은 메일 대화와 사용자의 질문별 답변을 바탕으로 회신 제목·본문을 작성합니다.
            입장(수락/거절 등)은 사용자 답변에서 읽고, 답변이 비거나 모호하면 중립으로 씁니다.
            대화·답변에 없는 사실·일정·금액·약속을 지어내지 않습니다.
            응답은 JSON Schema 로 강제됩니다: { "generated_subject": "...", "generated_email": "..." }
            """;

    @Override
    public AiReplyDraftResult draft(DraftInput in) {
        String system = (in.promptContent() == null || in.promptContent().isBlank())
                ? DEFAULT_DRAFT_SYSTEM_PROMPT : in.promptContent();
        String json = callAndExtract("reply_draft", properties.model(),
                system, buildDraftUserMessage(in), draftSchema(), properties.replyThinkingLevel());
        try {
            return objectMapper.readValue(json, AiReplyDraftResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini reply draft response: " + json, e);
        }
    }

    private String buildDraftUserMessage(DraftInput in) {
        StringBuilder sb = new StringBuilder();
        sb.append("받는 사람 유형: ").append(receiverToRcp(in.receiver())).append('\n');
        sb.append("보내는 사람(나): ").append(blankToNone(in.senderName())).append('\n');
        sb.append("원 메일 제목: ").append(blankToNone(in.originalSubject())).append('\n');
        sb.append("--- 받은 메일 대화 (오래된 것부터) ---\n").append(in.conversation()).append("\n--- 대화 끝 ---\n");
        sb.append("--- 답해야 할 질문과 사용자의 답변 ---\n");
        if (in.questionAnswers() == null || in.questionAnswers().isEmpty()) {
            sb.append("사용자가 전하려는 내용: ").append(blankToNone(in.freeInput())).append('\n');
        } else {
            for (AiReplyClient.QuestionAnswer qa : in.questionAnswers()) {
                sb.append(qa.id()).append(". ").append(qa.question())
                  .append(" → 사용자 답변: ")
                  .append(qa.answer() == null || qa.answer().isBlank() ? "(답변 없음 — 중립으로)" : qa.answer())
                  .append('\n');
            }
        }
        sb.append("--- 끝 ---\n");
        sb.append("그 밖에 전하고 싶은 말: ").append(blankToNone(in.extraMessage())).append('\n');
        sb.append("재작성 사유: ").append(
                in.revisionNotes() == null || in.revisionNotes().isEmpty()
                        ? "없음" : String.join(" / ", in.revisionNotes()));
        return sb.toString();
    }

    // ===== ⑥ 점검 (light) =====

    private static final String INSPECT_SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 이메일 회신 초안 검수자입니다. 초안을 고치지 말고 평가만 하세요.
            다섯 가지만 봅니다.
            1. 완전성 — 모든 질문에 답했는가. '그 밖에 전하고 싶은 말'이 반영됐는가.
               답 안 한 질문이 있으면 type=UNANSWERED_QUESTION, question_id 에 해당 질문 id.
            2. 사실 충실 — 대화·답변에 없는 사실·일정·금액·약속·거절 사유를 지어내지 않았는가,
               수치·기한이 바뀌지 않았는가. 위반 시 type=FABRICATION.
            3. 입장 일치 — 답변의 입장(수락/거절/조건부/중립)과 초안이 같은가. 모호한 답을 굳히지 않았는가.
               위반 시 type=STANCE_MISMATCH.
            4. 격식 — 수신자 유형에 맞는 어체인가. RCP-01·RCP-03 하십시오체, RCP-02·RCP-04 해요체 기본.
               불일치 시 type=FORMALITY_MISMATCH. 반말·인터넷 구어 혼입도 결함.
            5. 형식 — 제목·본문이 있고 이메일 형태인가. 위반 시 type=FORMAT.
            분명한 결함만 passed=false 로. 표현 취향·사소한 어색함은 통과. 의심스러우면 통과.
            question_id 는 UNANSWERED_QUESTION 일 때만 해당 id, 그 외 0. detail 은 무엇이 왜 문제인지 한 줄.
            """;

    @Override
    public AiReplyInspection inspect(InspectInput in) {
        StringBuilder sb = new StringBuilder();
        sb.append("받는 사람 유형: ").append(receiverToRcp(in.receiver())).append('\n');
        sb.append("--- 받은 메일 대화 ---\n").append(in.conversation()).append("\n--- 대화 끝 ---\n");
        sb.append("--- 질문과 사용자의 답변 ---\n");
        if (in.questionAnswers() == null || in.questionAnswers().isEmpty()) {
            sb.append("사용자가 전하려는 내용: ").append(blankToNone(in.freeInput())).append('\n');
        } else {
            for (AiReplyClient.QuestionAnswer qa : in.questionAnswers()) {
                sb.append(qa.id()).append(". ").append(qa.question())
                  .append(" → 사용자 답변: ").append(qa.answer() == null ? "" : qa.answer()).append('\n');
            }
        }
        sb.append("--- 끝 ---\n");
        sb.append("그 밖에 전하고 싶은 말: ").append(blankToNone(in.extraMessage())).append('\n');
        sb.append("--- 점검할 초안 ---\n제목: ").append(in.draft().generatedSubject())
          .append("\n본문:\n").append(in.draft().generatedEmail()).append("\n--- 초안 끝 ---");

        String json = callAndExtract("reply_inspect", properties.lightModelOrDefault(),
                INSPECT_SYSTEM_PROMPT, sb.toString(), inspectSchema());
        try {
            return objectMapper.readValue(json, AiReplyInspection.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini reply inspection response: " + json, e);
        }
    }

    // ===== 매핑·헬퍼 =====

    private AiReplyAnalysisResult.Status parseStatus(String s) {
        if (s == null) return AiReplyAnalysisResult.Status.OK;
        try {
            return AiReplyAnalysisResult.Status.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            return AiReplyAnalysisResult.Status.OK;
        }
    }

    private Receiver rcpToReceiver(String rcp) {
        if (rcp == null) return null;
        return switch (rcp.trim()) {
            case "RCP-01" -> Receiver.DIRECT_SUPERVISOR;
            case "RCP-02" -> Receiver.OTHER_DEPT_COLLEAGUE;
            case "RCP-03" -> Receiver.CLIENT;
            case "RCP-04" -> Receiver.EXTERNAL_PARTNER;
            default -> null;
        };
    }

    private String receiverToRcp(Receiver r) {
        if (r == null) return "RCP-02";
        return switch (r) {
            case DIRECT_SUPERVISOR -> "RCP-01";
            case OTHER_DEPT_COLLEAGUE -> "RCP-02";
            case CLIENT -> "RCP-03";
            case EXTERNAL_PARTNER -> "RCP-04";
        };
    }

    private String blankToNone(String s) {
        return (s == null || s.isBlank()) ? "없음" : s;
    }

    private String joinOrNone(List<String> list) {
        return (list == null || list.isEmpty()) ? "없음" : String.join(", ", list);
    }

    // ===== Gemini 호출 공통 =====

    private String callAndExtract(String op, String model, String systemInstruction,
                                  String userText, Map<String, Object> schema) {
        return callAndExtract(op, model, systemInstruction, userText, schema, null);
    }

    /** {@code thinkingLevel} 은 회신 작성 전용 옵션 — null/blank 면 thinkingConfig 미지정(모델 기본). */
    private String callAndExtract(String op, String model, String systemInstruction,
                                  String userText, Map<String, Object> schema, String thinkingLevel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userText)))));
        Map<String, Object> genConfig = new LinkedHashMap<>();
        genConfig.put("responseMimeType", "application/json");
        genConfig.put("responseJsonSchema", schema);
        // gemini-3 계열 → thinkingLevel 만 지정. budget·level 동시 지정은 Gemini 가 거부 (교정과 동일 정책).
        if (thinkingLevel != null && !thinkingLevel.isBlank()) {
            genConfig.put("thinkingConfig", Map.of("thinkingLevel", thinkingLevel));
        }
        body.put("generationConfig", genConfig);

        String path = "/models/" + model + ":generateContent";
        GeminiResponse response = geminiRestClient.post()
                .uri(uri -> uri.path(path).build())
                .header("x-goog-api-key", properties.apiKey())
                .body(body)
                .retrieve()
                .body(GeminiResponse.class);

        if (response == null || response.candidates() == null || response.candidates().isEmpty()
                || response.candidates().get(0).content() == null
                || response.candidates().get(0).content().parts() == null
                || response.candidates().get(0).content().parts().isEmpty()) {
            throw new IllegalStateException("Empty or malformed Gemini response (op=" + op + ")");
        }
        String text = response.candidates().get(0).content().parts().get(0).text();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Gemini response text is empty (op=" + op + ")");
        }
        logUsage(op, response.usageMetadata());
        return text;
    }

    private void logUsage(String op, GeminiResponse.UsageMetadata usage) {
        if (usage == null) return;
        log.info("gemini_usage op={} promptTokens={} candidatesTokens={} totalTokens={}",
                op, usage.promptTokenCount(), usage.candidatesTokenCount(), usage.totalTokenCount());
    }

    // ===== 스키마 =====

    private Map<String, Object> summarizeSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("summary_lines", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "maxItems", 3));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("summary_lines"));
        return root;
    }

    private Map<String, Object> analyzeSchema() {
        Map<String, Object> recipientProps = new LinkedHashMap<>();
        recipientProps.put("type", Map.of("type", "string",
                "enum", List.of("RCP-01", "RCP-02", "RCP-03", "RCP-04")));
        recipientProps.put("label", Map.of("type", "string"));
        recipientProps.put("confidence", Map.of("type", "string", "enum", List.of("high", "mid", "low")));
        recipientProps.put("reason", Map.of("type", "string"));
        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("type", "object");
        recipient.put("properties", recipientProps);
        recipient.put("required", List.of("type", "label", "confidence", "reason"));

        Map<String, Object> qProps = new LinkedHashMap<>();
        qProps.put("question", Map.of("type", "string"));
        qProps.put("mail_order", Map.of("type", "integer"));
        Map<String, Object> qItem = new LinkedHashMap<>();
        qItem.put("type", "object");
        qItem.put("properties", qProps);
        qItem.put("required", List.of("question", "mail_order"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("status", Map.of("type", "string",
                "enum", List.of("OK", "EMPTY_THREAD", "NOT_KOREAN")));
        props.put("recipient", recipient);
        props.put("questions", Map.of("type", "array", "items", qItem));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("status", "recipient", "questions"));
        return root;
    }

    private Map<String, Object> draftSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("generated_subject", Map.of("type", "string"));
        props.put("generated_email", Map.of("type", "string"));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("generated_subject", "generated_email"));
        return root;
    }

    private Map<String, Object> inspectSchema() {
        Map<String, Object> issueProps = new LinkedHashMap<>();
        issueProps.put("type", Map.of("type", "string", "enum",
                List.of("UNANSWERED_QUESTION", "FABRICATION", "FORMALITY_MISMATCH", "STANCE_MISMATCH", "FORMAT")));
        issueProps.put("question_id", Map.of("type", "integer"));
        issueProps.put("detail", Map.of("type", "string"));
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("type", "object");
        issue.put("properties", issueProps);
        issue.put("required", List.of("type", "question_id", "detail"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("passed", Map.of("type", "boolean"));
        props.put("issues", Map.of("type", "array", "items", issue));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("passed", "issues"));
        return root;
    }

    // ===== 파싱용 내부 DTO =====

    private record SummarizeOut(List<String> summaryLines) {
    }

    private record AnalyzeOut(String status, RecipientOut recipient, List<QuestionOut> questions) {
        record RecipientOut(String type, String label, String confidence, String reason) {
        }

        record QuestionOut(String question, Integer mailOrder) {
        }
    }

    private record GeminiResponse(
            List<Candidate> candidates,
            @JsonProperty("usageMetadata") UsageMetadata usageMetadata) {
        private record Candidate(Content content) {
        }

        private record Content(List<Part> parts) {
        }

        private record Part(String text) {
        }

        private record UsageMetadata(
                @JsonProperty("promptTokenCount") Integer promptTokenCount,
                @JsonProperty("candidatesTokenCount") Integer candidatesTokenCount,
                @JsonProperty("totalTokenCount") Integer totalTokenCount) {
        }
    }
}
