package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.domain.correction.model.ReasonPrimary;
import com.example.tonefitserver.domain.correction.model.ReasonSecondary;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RejectRequest(
        @NotNull Integer index,
        ReasonPrimary reasonPrimary,
        ReasonSecondary reasonSecondary,
        @Size(max = 200) String reasonText
) {
}
