package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.domain.session.Purpose;
import com.example.tonefitserver.domain.session.Receiver;
import jakarta.validation.constraints.AssertTrue;

public record RecorrectRequest(
        Receiver receiverType,
        Purpose purpose
) {
    @AssertTrue(message = "receiverType 또는 purpose 중 최소 1개는 필요합니다.")
    public boolean hasAtLeastOne() {
        return receiverType != null || purpose != null;
    }
}
