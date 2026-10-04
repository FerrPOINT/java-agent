package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.UsageEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.persistence.repository.UsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Holds the parent row lock until the usage insert commits. */
@Service
@RequiredArgsConstructor
@Slf4j
public class UsagePersistenceService {
    private final SessionRepository sessionRepository;
    private final UsageRepository usageRepository;

    /** Returns false when deletion committed first; otherwise the parent stays locked through this transaction's commit. */
    @Transactional
    public boolean saveForExistingSession(UsageEntity entity) {
        if (sessionRepository.findUsageParentId(entity.getSessionId()).isEmpty()) {
            return false;
        }
        usageRepository.save(entity);
        return true;
    }
}
