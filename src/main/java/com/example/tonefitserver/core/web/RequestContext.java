package com.example.tonefitserver.core.web;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * 요청 단위 컨텍스트. visit_session_id 보관.
 * 이벤트 발화 시 {@code event_log.visit_session_id} 채우는 데 사용.
 */
@Component
@RequestScope
public class RequestContext {

    public static final String UNKNOWN = "unknown";

    private String visitSessionId = UNKNOWN;

    public String getVisitSessionId() {
        return visitSessionId;
    }

    public void setVisitSessionId(String visitSessionId) {
        this.visitSessionId = (visitSessionId == null || visitSessionId.isBlank())
                ? UNKNOWN : visitSessionId;
    }
}
