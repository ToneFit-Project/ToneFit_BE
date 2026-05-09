package com.example.tonefitserver.domain.user;

import com.example.tonefitserver.core.dto.user.UpdateUserRequest;
import com.example.tonefitserver.core.dto.user.UserResponse;
import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.enums.UserStatus;
import com.example.tonefitserver.core.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserResponse getMe(Long userId) {
        User user = userRepository.findByIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorType.USER_NOT_FOUND));
        return toUserResponse(user);
    }

    @Transactional
    public UserResponse updateMe(Long userId, UpdateUserRequest request) {
        User user = userRepository.findByIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorType.USER_NOT_FOUND));
        user.updateProfile(request.industry(), request.careerLevel());
        return toUserResponse(user);
    }

    private UserResponse toUserResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.isGuest(),
                user.getEmail(),
                user.getNickname(),
                user.getIndustry(),
                user.getCareerLevel(),
                user.getPlan(),
                user.getFreeUsed(),
                user.getCreditBalance(),
                user.getCreatedAt()
        );
    }
}
