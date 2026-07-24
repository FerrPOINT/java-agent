package com.azhukov.agent.core.context;

import com.azhukov.agent.core.model.ContextReference;
import com.azhukov.agent.core.model.ReferenceType;

import java.util.List;
import java.util.Optional;

public interface ContextReferenceService {

    List<ContextReference> resolve(List<String> refs);

    Optional<String> loadContent(ContextReference reference);
}
