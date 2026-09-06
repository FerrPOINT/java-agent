package com.azhukov.agent.tools.memory;

import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.core.agent.SessionLineageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Coverage + regression for SessionSearchService.webSearch and its source-filter
 * helpers (uncovered block ~lines 640-830).
 */
@ExtendWith(MockitoExtension.class)
class SessionSearchServiceWebSearchTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private SessionLineageService sessionLineageService;

    private SessionSearchService service;

    @BeforeEach
    void setUp() {
        service = new SessionSearchService(sessionRepository, messageRepository, sessionLineageService);
    }

    private SessionEntity session(String title, String source) {
        SessionEntity e = new SessionEntity();
        e.setId(UUID.randomUUID());
        e.setTitle(title);
        e.setSource(source);
        return e;
    }

    @Test
    void blankQueryReturnsEmptyResults() {
        Map<String, Object> out = service.webSearch("   ", 5, null, null, null, null);
        assertThat(out.get("results")).isEqualTo(List.of());
    }

    @Test
    void directUuidQueryReturnsThatSession() {
        SessionEntity target = session("target", "telegram");
        when(sessionRepository.findById(target.getId())).thenReturn(Optional.of(target));
        // FTS discovery returns nothing extra
        lenient().when(messageRepository.searchByContentFtsExcludingSources(anyString(), any()))
            .thenReturn(List.of());

        Map<String, Object> out = service.webSearch(target.getId().toString(), 5, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("title")).isEqualTo("target");
    }

    @Test
    void directUuidQueryFiltersOutOtherSource() {
        SessionEntity target = session("target", "telegram");
        when(sessionRepository.findById(target.getId())).thenReturn(Optional.of(target));
        lenient().when(messageRepository.searchByContentFtsExcludingSources(anyString(), any()))
            .thenReturn(List.of());

        // source filter=cli excludes the telegram session
        Map<String, Object> out = service.webSearch(target.getId().toString(), 5, null, "cli", null, null);
        assertThat(out.get("results")).isEqualTo(List.of());
    }

    @Test
    void parseIncludeSourcesPrefersSingleThenCsv() {
        assertThat(service.parseIncludeSources("cli", null)).containsExactly("cli");
        assertThat(service.parseIncludeSources(null, "cli, telegram ,")).containsExactly("cli", "telegram");
        assertThat(service.parseIncludeSources(null, null)).isEmpty();
        assertThat(service.parseIncludeSources("  ", "  ")).isEmpty();
    }

    @Test
    void parseCsvHandlesNullsBlanksAndDuplicates() {
        assertThat(service.parseCsv(null)).isEmpty();
        assertThat(service.parseCsv("   ")).isEmpty();
        assertThat(service.parseCsv("a, a , b")).containsExactly("a", "b");
    }
}
