package com.example.tonefitserver.core.web;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

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
