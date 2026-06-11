package com.example.tonefitserver.domain.user;

import com.example.tonefitserver.core.dto.user.TermsStatusResponse;
import com.example.tonefitserver.core.dto.user.UserResponse;
import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.enums.TermsType;
import com.example.tonefitserver.core.enums.UserStatus;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.domain.correction.repository.RejectedCorrectionRepository;
import com.example.tonefitserver.domain.generation.GenerationMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserTermsAgreementRepository userTermsAgreementRepository;
    private final GenerationMetadataRepository generationMetadataRepository;
    private final RejectedCorrectionRepository rejectedCorrectionRepository;

    public UserResponse getMe(Long userId) {
        User user = userRepository.findByIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorType.USER_NOT_FOUND));
        return toUserResponse(user);
    }

    /**
     * 약관 동의 현황 조회 — 5종 전체를 항상 반환 (기록 없는 타입은 agreed=false).
     *
     * <p>agreed 판정은 {@link #toggleTerms} 와 동일하게 <b>버전 무관 활성</b>
     * (agreed=true AND revoked_at IS NULL). 약관 버전업 과도기에 활성 row 가 여러 버전이면
     * 최신 agreed_at 의 row 를 표시한다.
     */
    public TermsStatusResponse getTerms(Long userId) {
        userRepository.findByIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorType.USER_NOT_FOUND));

        Map<TermsType, UserTermsAgreement> activeByType = userTermsAgreementRepository
                .findByUserId(userId).stream()
                .filter(UserTermsAgreement::isActive)
                .collect(Collectors.toMap(UserTermsAgreement::getType, Function.identity(),
                        (a, b) -> a.getAgreedAt().isAfter(b.getAgreedAt()) ? a : b));

        List<TermsStatusResponse.Item> items = Arrays.stream(TermsType.values())
                .map(type -> {
                    UserTermsAgreement active = activeByType.get(type);
                    return new TermsStatusResponse.Item(
                            type,
                            type.isRequired(),
                            active != null,
                            active == null ? null : active.getVersion(),
                            active == null ? null : active.getAgreedAt());
                })
                .toList();
        return new TermsStatusResponse(items);
    }

    /**
     * 선택 동의(MARKETING / AI_LEARNING) 토글. 필수 항목은 거부.
     *
     * <p>활성 동의 판정은 <b>버전 무관</b>으로 한다 — 약관 버전이 올라간 뒤에도 사용자가
     * 구버전으로 동의한 활성 row 를 철회할 수 있어야 한다(currentVersion 만 보면 그 row 를 놓쳐
     * 철회가 404 로 막힌다).
     * <ul>
     *   <li>agreed=true: 이미 활성이면 no-op. 아니면 currentVersion row 가 revoked 상태로 있으면
     *       reactivate, 없으면 새 row INSERT.</li>
     *   <li>agreed=false: 활성 row(버전 무관)를 모두 revoke. 없으면 NOT_FOUND.</li>
     * </ul>
     */
    @Transactional
    public void toggleTerms(Long userId, TermsType type, boolean agreed) {
        if (type.isRequired()) {
            throw new BusinessException(ErrorType.INVALID_REQUEST,
                    "필수 약관은 철회·재동의할 수 없습니다.");
        }
        User user = userRepository.findByIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorType.USER_NOT_FOUND));

        List<UserTermsAgreement> active = userTermsAgreementRepository
                .findActiveByUserIdAndType(userId, type);

        if (agreed) {
            if (!active.isEmpty()) {
                return; // 이미 동의 상태 — 멱등.
            }
            String version = type.currentVersion();
            userTermsAgreementRepository.findByUserIdAndTypeAndVersion(userId, type, version)
                    .ifPresentOrElse(
                            UserTermsAgreement::reactivate,
                            () -> userTermsAgreementRepository.save(
                                    new UserTermsAgreement(user, type, version, true)));
        } else {
            if (active.isEmpty()) {
                throw new BusinessException(ErrorType.NOT_FOUND,
                        "철회할 활성 동의 기록이 없습니다.");
            }
            active.forEach(UserTermsAgreement::revoke);
            // REQ-Ext-11 #4 / FUNC-Cor-06: AI 학습 활용 동의 철회 시 기 수집 데이터 즉시 파기.
            if (type == TermsType.AI_LEARNING) {
                int purgedMeta = generationMetadataRepository.deleteByUserId(userId);
                int purgedReject = rejectedCorrectionRepository.deleteByUserId(userId);
                if (purgedMeta > 0 || purgedReject > 0) {
                    log.info("AI_LEARNING revoked for user {}: purged {} generation_metadata, {} rejected_correction rows",
                            userId, purgedMeta, purgedReject);
                }
            }
        }
    }

    private UserResponse toUserResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getProvider(),
                user.getPlan(),
                user.getCreditBalance(),
                user.getCreatedAt()
        );
    }
}
