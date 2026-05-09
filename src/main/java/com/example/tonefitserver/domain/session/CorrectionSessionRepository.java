package com.example.tonefitserver.domain.session;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CorrectionSessionRepository extends JpaRepository<CorrectionSession, Long> {

    @Query("""
            select s from CorrectionSession s
            where s.user.id = :userId and s.status in :statuses
            order by s.createdAt desc
            """)
    List<CorrectionSession> findByUserIdAndStatusIn(@Param("userId") Long userId,
                                                   @Param("statuses") Collection<Status> statuses);

    @Query("""
            select s from CorrectionSession s
            where s.user.id = :userId and s.status = :status
              and (:receiverType is null or s.receiverType = :receiverType)
              and (:purpose is null or s.purpose = :purpose)
            """)
    Page<CorrectionSession> searchHistory(@Param("userId") Long userId,
                                          @Param("status") Status status,
                                          @Param("receiverType") Receiver receiverType,
                                          @Param("purpose") Purpose purpose,
                                          Pageable pageable);
}
