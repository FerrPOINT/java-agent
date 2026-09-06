package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProfilesDashboardControllerTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-01T09:00:00Z");

    @TempDir
    private Path tempDir;

    private AgentProperties properties;
    private RuntimeConfigService runtimeConfigService;
    private ProfileService profileService;
    private SessionRepository sessionRepository;
    private MessageRepository messageRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getProfile().setName("default");
        properties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        properties.getCore().setSoulMdPath(tempDir.resolve("soul.md").toString());
        properties.getModel().setProvider("openai-compatible");
        properties.getModel().setModelName("gpt-5");
        runtimeConfigService = new RuntimeConfigService();
        profileService = new ProfileService(properties, runtimeConfigService);
        sessionRepository = mock(com.azhukov.agent.persistence.repository.SessionRepository.class);
        messageRepository = mock(com.azhukov.agent.persistence.repository.MessageRepository.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
            new ProfilesDashboardController(
                properties,
                runtimeConfigService,
                profileService,
                sessionRepository,
                messageRepository,
                command -> {
                })).build();
    }

    private MockMvc mockMvcWithTerminalLauncher(ProfilesDashboardController.ProfileTerminalLauncher launcher) {
        return MockMvcBuilders.standaloneSetup(
            new ProfilesDashboardController(
                properties,
                runtimeConfigService,
                profileService,
                sessionRepository,
                messageRepository,
                launcher)).build();
    }

    @Test
    void profilesRouteReturnsCurrentJavaProfileShape() throws Exception {
        Path work = tempDir.resolve("profiles").resolve("work");
        Files.createDirectories(work.resolve("skills").resolve("alpha"));
        Files.writeString(work.resolve("skills").resolve("alpha").resolve("SKILL.md"), "skill");
        Files.writeString(work.resolve(".env"), "TOKEN=secret");
        runtimeConfigService.setModelSelection("openrouter", "anthropic/claude-sonnet-4-5", null, null);

        mockMvc.perform(get("/api/profiles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profiles[0].name").value("default"))
            .andExpect(jsonPath("$.profiles[0].display_name").value(""))
            .andExpect(jsonPath("$.profiles[0].is_default").value(true))
            .andExpect(jsonPath("$.profiles[0].provider").value("openrouter"))
            .andExpect(jsonPath("$.profiles[0].model").value("anthropic/claude-sonnet-4-5"))
            .andExpect(jsonPath("$.profiles[0].skill_count").isNumber())
            .andExpect(jsonPath("$.profiles[0].gateway_running").value(false))
            .andExpect(jsonPath("$.profiles[0].description").value(""))
            .andExpect(jsonPath("$.profiles[0].description_auto").value(false))
            .andExpect(jsonPath("$.profiles[0].has_alias").value(false))
            .andExpect(jsonPath("$.profiles[1].name").value("work"))
            .andExpect(jsonPath("$.profiles[1].is_default").value(false))
            .andExpect(jsonPath("$.profiles[1].has_env").value(true))
            .andExpect(jsonPath("$.profiles[1].skill_count").value(1));
    }

    @Test
    void profileSoulReadsDefaultAndNamedProfileWithoutCreatingFiles() throws Exception {
        Files.writeString(tempDir.resolve("soul.md"), "Stay sharp.");
        Path work = tempDir.resolve("profiles").resolve("work");
        Files.createDirectories(work);
        Files.writeString(work.resolve("SOUL.md"), "Work soul.");

        mockMvc.perform(get("/api/profiles/default/soul"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exists").value(true))
            .andExpect(jsonPath("$.content").value("Stay sharp."));

        mockMvc.perform(get("/api/profiles/WORK/soul"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exists").value(true))
            .andExpect(jsonPath("$.content").value("Work soul."));

        mockMvc.perform(get("/api/profiles/ghost/soul"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown profile: ghost"));
    }

    @Test
    void setupAndSessionAggregationRoutesExposeStableHermesShapes() throws Exception {
        mockMvc.perform(get("/api/profiles/active"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value("default"))
            .andExpect(jsonPath("$.current").value("default"));

        mockMvc.perform(get("/api/profiles/default/setup-command"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.command").value("java -jar java-agent-backend.jar --agent.profile.name=default"));

        mockMvc.perform(get("/api/profiles/ghost/setup-command"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown profile: ghost"));

        mockMvc.perform(get("/api/profiles/sessions?limit=500&offset=2&profile=all"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.sessions").isArray())
            .andExpect(jsonPath("$.limit").value(500))
            .andExpect(jsonPath("$.offset").value(2))
            .andExpect(jsonPath("$.has_more").value(false));

        mockMvc.perform(get("/api/profiles/projects/tree?preview_limit=10&session_limit=2000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.projects").isArray())
            .andExpect(jsonPath("$.active_id").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.scoped_session_ids").isArray())
            .andExpect(jsonPath("$.errors").isArray());

        mockMvc.perform(get("/api/profiles/sessions/sidebar"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recents.sessions").isArray())
            .andExpect(jsonPath("$.recents.profiles_truncated").isMap())
            .andExpect(jsonPath("$.recents.profiles_usage").isMap())
            .andExpect(jsonPath("$.cron.sessions").isArray())
            .andExpect(jsonPath("$.messaging.sessions").isArray())
            .andExpect(jsonPath("$.messaging.total").value(0))
            .andExpect(jsonPath("$.errors").isArray());

        mockMvc.perform(post("/api/profiles/sessions/pull-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"a\",\"b\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pull_requests").isMap())
            .andExpect(jsonPath("$.scanned[0]").value("a"))
            .andExpect(jsonPath("$.scanned[1]").value("b"));
    }

    @Test
    void openTerminalLaunchesKnownProfileAndReturnsHermesShape() throws Exception {
        Files.createDirectories(tempDir.resolve("profiles").resolve("work"));
        AtomicReference<String> launched = new AtomicReference<>();
        mockMvc = mockMvcWithTerminalLauncher(launched::set);

        mockMvc.perform(post("/api/profiles/WORK/open-terminal"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.command").value("java -jar java-agent-backend.jar --agent.profile.name=work"));

        org.assertj.core.api.Assertions.assertThat(launched.get())
            .isEqualTo("java -jar java-agent-backend.jar --agent.profile.name=work");
    }

    @Test
    void openTerminalMapsLauncherFailuresLikeHermes() throws Exception {
        mockMvc = mockMvcWithTerminalLauncher(command -> {
            throw new IllegalStateException("No supported terminal emulator found");
        });

        mockMvc.perform(post("/api/profiles/default/open-terminal"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("No supported terminal emulator found"));

        mockMvc = mockMvcWithTerminalLauncher(command -> {
            throw new java.io.IOException("launcher failed");
        });

        mockMvc.perform(post("/api/profiles/default/open-terminal"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("launcher failed"));
    }

    @Test
    void scanSessionPullRequestsRecoversOnlyBareGhPrCreateOutputsLikeHermes() throws Exception {
        UUID sessionId = UUID.nameUUIDFromBytes("pr-session".getBytes(StandardCharsets.UTF_8));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId))
            .thenReturn(List.of(
                message(sessionId, "{\"output\":\"Created https://github.com/acme/repo/pull/40\"}", 1),
                message(sessionId, "{\"output\":\"https://github.com/acme/repo/pull/41\"}", 2),
                message(sessionId, "{\"output\":\"https://github.com/acme/repo/pull/42\"}", 3)));

        mockMvc.perform(post("/api/profiles/sessions/pull-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + sessionId + "\",\"" + sessionId + "\",\"external-session\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scanned[0]").value(sessionId.toString()))
            .andExpect(jsonPath("$.scanned[1]").value("external-session"))
            .andExpect(jsonPath("$.pull_requests['" + sessionId + "'].number").value(42))
            .andExpect(jsonPath("$.pull_requests['" + sessionId + "'].url")
                .value("https://github.com/acme/repo/pull/42"));

        verify(messageRepository).findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Test
    void profileSessionsRejectsOutOfRangeQueryParamsLikeHermes() throws Exception {
        mockMvc.perform(get("/api/profiles/sessions?limit=-1"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("limit must be between 0 and 500"));

        mockMvc.perform(get("/api/profiles/sessions?offset=-1"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("offset must be between 0 and 2147483647"));

        mockMvc.perform(get("/api/profiles/sessions?limit=501"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("limit must be between 0 and 500"));

        mockMvc.perform(get("/api/profiles/sessions?limit=abc"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("limit must be an integer"));
    }

    @Test
    void profileSessionsReturnsAggregatedRowsAndProfileTotalsLikeHermes() throws Exception {
        SessionEntity defaultSession = sessionEntity("default chat", "default", "cli", 3, BASE_TIME.plusSeconds(30));
        SessionEntity workSession = sessionEntity("work chat", "work", "api_server", 5, BASE_TIME.plusSeconds(20));
        when(sessionRepository.findProfileDashboardPageOrderByRecent(
                eq(AgentProperties.DEFAULT_USER_ID), isNull(), eq(2), eq(0), eq(false), eq(false), eq(false),
                isNull(), eq(true), anyList(), eq(false), anyList(), eq(1), eq(false), eq(true)))
            .thenReturn(List.of(defaultSession, workSession));
        when(sessionRepository.countProfileDashboardSessions(
                eq(AgentProperties.DEFAULT_USER_ID), isNull(), eq(false), eq(false), eq(false),
                isNull(), eq(true), anyList(), eq(false), anyList(), eq(1), eq(false), eq(true)))
            .thenReturn(3L);
        when(sessionRepository.countProfileDashboardSessionsByProfile(
                eq(AgentProperties.DEFAULT_USER_ID), isNull(), eq(false), eq(false), eq(false),
                isNull(), eq(true), anyList(), eq(false), anyList(), eq(1), eq(false), eq(true)))
            .thenReturn(List.<Object[]>of(new Object[]{"default", 1L}, new Object[]{"work", 2L}));

        mockMvc.perform(get("/api/profiles/sessions")
                .param("limit", "2")
                .param("offset", "0")
                .param("min_messages", "1")
                .param("archived", "exclude")
                .param("order", "recent")
                .param("profile", "all")
                .param("exclude_sources", "cron"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.profile_totals.default").value(1))
            .andExpect(jsonPath("$.profile_totals.work").value(2))
            .andExpect(jsonPath("$.sessions[0].title").value("default chat"))
            .andExpect(jsonPath("$.sessions[0].profile").value("default"))
            .andExpect(jsonPath("$.sessions[0].is_default_profile").value(true))
            .andExpect(jsonPath("$.sessions[0].message_count").value(3))
            .andExpect(jsonPath("$.sessions[1].title").value("work chat"))
            .andExpect(jsonPath("$.sessions[1].profile").value("work"))
            .andExpect(jsonPath("$.data[1].profile").value("work"))
            .andExpect(jsonPath("$.has_more").value(true))
            .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void profileSessionsScopesNamedProfileAndRejectsBadScopesLikeHermes() throws Exception {
        Files.createDirectories(tempDir.resolve("profiles").resolve("work"));
        SessionEntity workSession = sessionEntity("archived cron", "work", "cron", 2, BASE_TIME);
        workSession.setArchived(true);
        when(sessionRepository.findProfileDashboardPageOrderByCreated(
                eq(AgentProperties.DEFAULT_USER_ID), eq("work"), eq(1), eq(1), eq(true), eq(true), eq(true),
                eq("cron"), eq(true), anyList(), eq(false), anyList(), eq(2), eq(false), eq(false)))
            .thenReturn(List.of(workSession));
        when(sessionRepository.countProfileDashboardSessions(
                eq(AgentProperties.DEFAULT_USER_ID), eq("work"), eq(true), eq(true), eq(true),
                eq("cron"), eq(true), anyList(), eq(false), anyList(), eq(2), eq(false), eq(false)))
            .thenReturn(1L);
        when(sessionRepository.countProfileDashboardSessionsByProfile(
                eq(AgentProperties.DEFAULT_USER_ID), eq("work"), eq(true), eq(true), eq(true),
                eq("cron"), eq(true), anyList(), eq(false), anyList(), eq(2), eq(false), eq(false)))
            .thenReturn(List.<Object[]>of(new Object[]{"work", 1L}));

        mockMvc.perform(get("/api/profiles/sessions")
                .param("profile", "WORK")
                .param("limit", "1")
                .param("offset", "1")
                .param("archived", "only")
                .param("order", "created")
                .param("source", "cron")
                .param("exclude_sources", "desktop")
                .param("min_messages", "2")
                .param("include_hidden", "true")
                .param("include_pinned", "false"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profile").value("work"))
            .andExpect(jsonPath("$.order").value("created"))
            .andExpect(jsonPath("$.sessions[0].profile").value("work"))
            .andExpect(jsonPath("$.sessions[0].source").value("cron"))
            .andExpect(jsonPath("$.sessions[0].archived").value(true))
            .andExpect(jsonPath("$.profile_totals.work").value(1))
            .andExpect(jsonPath("$.profiles_truncated.work").value(false));

        verify(sessionRepository).findProfileDashboardPageOrderByCreated(
            eq(AgentProperties.DEFAULT_USER_ID), eq("work"), eq(1), eq(1), eq(true), eq(true), eq(true),
            eq("cron"), eq(true), anyList(), eq(false), anyList(), eq(2), eq(false), eq(false));

        mockMvc.perform(get("/api/profiles/sessions").param("profile", "ghost"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown profile: ghost"));

        mockMvc.perform(get("/api/profiles/sessions").param("profile", "../ghost"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Invalid profile name: ../ghost"));

        mockMvc.perform(get("/api/profiles/sessions").param("archived", "stale"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("archived must be one of: exclude, only, include"));

        mockMvc.perform(get("/api/profiles/sessions").param("order", "random"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("order must be one of: created, recent"));

        verify(sessionRepository, never()).findProfileDashboardPageOrderByRecent(
            eq(AgentProperties.DEFAULT_USER_ID), eq("ghost"), eq(20), eq(0), eq(false), eq(false), eq(false),
            isNull(), eq(true), anyList(), eq(true), anyList(), eq(0), eq(false), eq(true));
    }

    @Test
    void sidebarSessionsScopesAllSlicesAndBackfillsPinnedRowsLikeHermes() throws Exception {
        Files.createDirectories(tempDir.resolve("profiles").resolve("work"));
        SessionEntity workChat = sessionEntity("work chat", "work", "cli", 1, BASE_TIME.plusSeconds(30));
        SessionEntity pinnedOld = sessionEntity("pinned old", "work", "cli", 1, BASE_TIME.minusSeconds(600));
        pinnedOld.setPinned(true);
        SessionEntity workCron = sessionEntity("work cron", "work", "cron", 1, BASE_TIME.plusSeconds(20));
        SessionEntity workTelegram = sessionEntity("work telegram", "work", "telegram", 1, BASE_TIME.plusSeconds(10));

        when(sessionRepository.findProfileDashboardPageOrderByRecent(
                eq(AgentProperties.DEFAULT_USER_ID), eq("work"), eq(1), eq(0), eq(false), eq(false), eq(false),
                isNull(), eq(true), anyList(), eq(false), anyList(), eq(1), eq(false), eq(false)))
            .thenReturn(List.of(workChat));
        when(sessionRepository.findProfileDashboardPinnedOrderByRecent(
                eq(AgentProperties.DEFAULT_USER_ID), eq("work"), eq(false), eq(false), eq(false),
                isNull(), eq(true), anyList(), eq(false), anyList(), eq(1), eq(false)))
            .thenReturn(List.of(pinnedOld));
        when(sessionRepository.findProfileDashboardPageOrderByRecent(
                eq(AgentProperties.DEFAULT_USER_ID), eq("work"), eq(2), eq(0), eq(false), eq(false), eq(false),
                eq("cron"), eq(true), anyList(), eq(true), anyList(), eq(1), eq(false), eq(false)))
            .thenReturn(List.of(workCron));
        when(sessionRepository.findProfileDashboardPageOrderByRecent(
                eq(AgentProperties.DEFAULT_USER_ID), eq("work"), eq(2), eq(0), eq(false), eq(false), eq(false),
                isNull(), eq(true), anyList(), eq(false), anyList(), eq(1), eq(false), eq(false)))
            .thenReturn(List.of(workTelegram));
        when(sessionRepository.countProfileDashboardSessionsByProfile(
                eq(AgentProperties.DEFAULT_USER_ID), eq("work"), eq(false), eq(false), eq(false),
                isNull(), eq(true), anyList(), eq(false), anyList(), eq(1), eq(false), eq(false)))
            .thenReturn(List.<Object[]>of(new Object[]{"work", 2L}));
        when(sessionRepository.countProfileDashboardUsageByProfile(AgentProperties.DEFAULT_USER_ID, "work"))
            .thenReturn(List.<Object[]>of(new Object[]{"work", 42L, 1.25d}));
        when(sessionRepository.countProfileDashboardSessions(
                eq(AgentProperties.DEFAULT_USER_ID), eq("work"), eq(false), eq(false), eq(false),
                isNull(), eq(true), anyList(), eq(false), anyList(), eq(1), eq(false), eq(true)))
            .thenReturn(1L);

        mockMvc.perform(get("/api/profiles/sessions/sidebar")
                .param("recents_profile", "work")
                .param("recents_limit", "1")
                .param("cron_limit", "2")
                .param("messaging_limit", "2")
                .param("recents_exclude", "cron,telegram")
                .param("messaging_exclude", "cli,cron"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").isArray())
            .andExpect(jsonPath("$.recents.sessions[0].title").value("work chat"))
            .andExpect(jsonPath("$.recents.sessions[0].profile").value("work"))
            .andExpect(jsonPath("$.recents.sessions[1].title").value("pinned old"))
            .andExpect(jsonPath("$.recents.sessions[1].pinned").value(true))
            .andExpect(jsonPath("$.recents.profiles_truncated.work").value(true))
            .andExpect(jsonPath("$.recents.profiles_usage.work.tokens").value(42))
            .andExpect(jsonPath("$.recents.profiles_usage.work.cost_usd").value(1.25d))
            .andExpect(jsonPath("$.cron.sessions[0].title").value("work cron"))
            .andExpect(jsonPath("$.cron.sessions[0].profile").value("work"))
            .andExpect(jsonPath("$.messaging.sessions[0].title").value("work telegram"))
            .andExpect(jsonPath("$.messaging.sessions[0].profile").value("work"))
            .andExpect(jsonPath("$.messaging.total").value(1));
    }


    @Test
    void profileProjectsTreeGroupsByRepoRootAndCwdWorktrees() throws Exception {
        // Two sessions in the same repo, different cwd worktree lanes + one legacy no-project row.
        SessionEntity repoA1 = sessionEntity("repo a wt1", "default", "cli", 2, BASE_TIME.plusSeconds(40));
        repoA1.setCwd("/work/my-repo/packages/one");
        repoA1.setGitRepoRoot("/work/my-repo");
        SessionEntity repoA2 = sessionEntity("repo a wt2", "default", "cli", 3, BASE_TIME.plusSeconds(30));
        repoA2.setCwd("/work/my-repo/packages/two");
        repoA2.setGitRepoRoot("/work/my-repo");
        SessionEntity legacy = sessionEntity("legacy home", "work", "telegram", 1, BASE_TIME.plusSeconds(20));
        when(sessionRepository.findProfileDashboardPageOrderByRecent(
                eq(AgentProperties.DEFAULT_USER_ID), isNull(), eq(2000), eq(0), eq(false), eq(false), eq(false),
                isNull(), eq(true), anyList(), eq(true), anyList(), eq(0), eq(false), eq(true)))
            .thenReturn(List.of(repoA1, repoA2, legacy));
        when(sessionRepository.countProfileDashboardSessions(
                eq(AgentProperties.DEFAULT_USER_ID), isNull(), eq(false), eq(false), eq(false),
                isNull(), eq(true), anyList(), eq(true), anyList(), eq(0), eq(false), eq(true)))
            .thenReturn(3L);
        when(sessionRepository.countProfileDashboardUsageByProfile(AgentProperties.DEFAULT_USER_ID, null))
            .thenReturn(List.<Object[]>of());

        mockMvc.perform(get("/api/profiles/projects/tree"))
            .andExpect(status().isOk())
            // project lane from persisted repo root
            .andExpect(jsonPath("$.projects[0].id").value("repo:/work/my-repo"))
            .andExpect(jsonPath("$.projects[0].label").value("my-repo"))
            .andExpect(jsonPath("$.projects[0].isNoProject").value(false))
            .andExpect(jsonPath("$.projects[0].sessionCount").value(2))
            // two cwd groups inside the repo
            .andExpect(jsonPath("$.projects[0].repos[0].groups.length()").value(2))
            .andExpect(jsonPath("$.projects[0].repos[0].groups[0].label").value("one"))
            .andExpect(jsonPath("$.projects[0].repos[0].groups[0].sessions.length()").value(1))
            .andExpect(jsonPath("$.projects[0].repos[0].groups[1].label").value("two"))
            // legacy row keeps the Home bucket, listed after project lanes
            .andExpect(jsonPath("$.projects[1].id").value("__no_project__"))
            .andExpect(jsonPath("$.projects[1].isNoProject").value(true))
            .andExpect(jsonPath("$.projects[1].sessionCount").value(1))
            // session payloads expose the grouping metadata
            .andExpect(jsonPath("$.scoped_session_ids.length()").value(3));
    }

    @Test
    void sidebarSessionsUnknownConcreteProfileReturnsEmptyScopedPayloadLikeHermes() throws Exception {
        mockMvc.perform(get("/api/profiles/sessions/sidebar").param("recents_profile", "ghost"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recents.sessions").isEmpty())
            .andExpect(jsonPath("$.cron.sessions").isEmpty())
            .andExpect(jsonPath("$.messaging.sessions").isEmpty())
            .andExpect(jsonPath("$.messaging.total").value(0));
    }

    @Test
    void profileProjectsTreeReturnsHomeBucketFromSessionRowsLikeHermes() throws Exception {
        SessionEntity defaultSession = sessionEntity("default home", "default", "cli", 1, BASE_TIME.plusSeconds(30));
        SessionEntity workSession = sessionEntity("work home", "work", "telegram", 1, BASE_TIME.plusSeconds(20));
        when(sessionRepository.findProfileDashboardPageOrderByRecent(
                eq(AgentProperties.DEFAULT_USER_ID), isNull(), eq(2), eq(0), eq(false), eq(false), eq(false),
                isNull(), eq(true), anyList(), eq(true), anyList(), eq(0), eq(false), eq(true)))
            .thenReturn(List.of(defaultSession, workSession));
        when(sessionRepository.countProfileDashboardSessions(
                eq(AgentProperties.DEFAULT_USER_ID), isNull(), eq(false), eq(false), eq(false),
                isNull(), eq(true), anyList(), eq(true), anyList(), eq(0), eq(false), eq(true)))
            .thenReturn(2L);
        when(sessionRepository.countProfileDashboardUsageByProfile(AgentProperties.DEFAULT_USER_ID, null))
            .thenReturn(List.<Object[]>of(new Object[]{"default", 11L, 0.5d}, new Object[]{"work", 7L, 0.25d}));

        mockMvc.perform(get("/api/profiles/projects/tree")
                .param("preview_limit", "1")
                .param("session_limit", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active_id").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.errors").isArray())
            .andExpect(jsonPath("$.scoped_session_ids[0]").value(defaultSession.getId().toString()))
            .andExpect(jsonPath("$.scoped_session_ids[1]").value(workSession.getId().toString()))
            .andExpect(jsonPath("$.projects[0].id").value("__no_project__"))
            .andExpect(jsonPath("$.projects[0].label").value("Home"))
            .andExpect(jsonPath("$.projects[0].isNoProject").value(true))
            .andExpect(jsonPath("$.projects[0].sessionCount").value(2))
            .andExpect(jsonPath("$.projects[0].totalTokens").value(18))
            .andExpect(jsonPath("$.projects[0].totalCostUsd").value(0.75d))
            .andExpect(jsonPath("$.projects[0].previewSessions[0].title").value("default home"))
            .andExpect(jsonPath("$.projects[0].repos[0].id").value("__no_project__"))
            .andExpect(jsonPath("$.projects[0].repos[0].groups[0].id").value("__no_project__"))
            .andExpect(jsonPath("$.projects[0].repos[0].groups[0].isHome").value(true))
            .andExpect(jsonPath("$.projects[0].repos[0].groups[0].sessions[0].profile").value("default"))
            .andExpect(jsonPath("$.projects[0].repos[0].groups[0].sessions[1].profile").value("work"));
    }

    @Test
    void desktopOverlayReadsProfileOverlayWhenPresent() throws Exception {
        Path work = tempDir.resolve("profiles").resolve("work");
        Files.createDirectories(work);
        Files.writeString(work.resolve("desktop.json"), "{\"skin\":\"vscode-dark\",\"version\":1}");

        mockMvc.perform(get("/api/profiles/work/desktop-overlay"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exists").value(true))
            .andExpect(jsonPath("$.desktop.skin").value("vscode-dark"))
            .andExpect(jsonPath("$.desktop.version").value(1));

        mockMvc.perform(get("/api/profiles/default/desktop-overlay"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exists").value(false));
    }

    @Test
    void profileMutationsPersistProfileStateLikeHermes() throws Exception {
        Path work = tempDir.resolve("profiles").resolve("work");
        Files.createDirectories(work);

        mockMvc.perform(post("/api/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Fresh",
                      "description": "coder",
                      "provider": "openrouter",
                      "model": "anthropic/claude-sonnet-4-5",
                      "base_url": "https://openrouter.example/api/v1"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.name").value("fresh"))
            .andExpect(jsonPath("$.model_set").value(true));

        org.assertj.core.api.Assertions.assertThat(tempDir.resolve("profiles").resolve("fresh").resolve(".env"))
            .isRegularFile();
        org.assertj.core.api.Assertions.assertThat(tempDir.resolve("profiles").resolve("fresh").resolve("SOUL.md"))
            .isRegularFile();
        org.assertj.core.api.Assertions.assertThat(Files.readString(
                tempDir.resolve("profiles").resolve("fresh").resolve("profile.yaml")))
            .contains("description: coder")
            .contains("description_auto: false");
        org.assertj.core.api.Assertions.assertThat(Files.readString(
                tempDir.resolve("profiles").resolve("fresh").resolve("config.yaml")))
            .contains("provider: openrouter")
            .contains("default: anthropic/claude-sonnet-4-5")
            .contains("base_url: https://openrouter.example/api/v1");

        mockMvc.perform(post("/api/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"work\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Profile already exists: work"));

        mockMvc.perform(post("/api/profiles/active")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"work\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.active").value("work"));

        mockMvc.perform(get("/api/profiles/active"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value("work"))
            .andExpect(jsonPath("$.current").value("default"));

        mockMvc.perform(post("/api/profiles/active")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ghost\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown profile: ghost"));

        mockMvc.perform(patch("/api/profiles/default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"new_name\":\"Main Desk\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("default"))
            .andExpect(jsonPath("$.display_name").value("Main Desk"));

        mockMvc.perform(patch("/api/profiles/default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"new_name\":\"\"}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/profiles/default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"new_name\":\"work\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(patch("/api/profiles/fresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"new_name\":\"fresh2\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("fresh2"));

        org.assertj.core.api.Assertions.assertThat(tempDir.resolve("profiles").resolve("fresh")).doesNotExist();
        org.assertj.core.api.Assertions.assertThat(tempDir.resolve("profiles").resolve("fresh2")).isDirectory();

        mockMvc.perform(delete("/api/profiles/default"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/profiles/ghost"))
            .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/profiles/fresh2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        org.assertj.core.api.Assertions.assertThat(tempDir.resolve("profiles").resolve("fresh2")).doesNotExist();

        mockMvc.perform(put("/api/profiles/default/soul")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"new\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        org.assertj.core.api.Assertions.assertThat(Files.readString(tempDir.resolve("soul.md"))).isEqualTo("new");

        mockMvc.perform(post("/api/profiles/work/open-terminal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.command").value("java -jar java-agent-backend.jar --agent.profile.name=work"));

        mockMvc.perform(post("/api/profiles/ghost/open-terminal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown profile: ghost"));

        mockMvc.perform(put("/api/profiles/work/description")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"coder\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.description").value("coder"))
            .andExpect(jsonPath("$.description_auto").value(false));

        mockMvc.perform(put("/api/profiles/work/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"openai\",\"model\":\"gpt-5\",\"base_url\":\"https://api.example/v1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.provider").value("openai"))
            .andExpect(jsonPath("$.model").value("gpt-5"))
            .andExpect(jsonPath("$.base_url").value("https://api.example/v1"));

        mockMvc.perform(put("/api/profiles/work/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"openai\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("provider and model are required"));

        mockMvc.perform(post("/api/profiles/work/describe-auto")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"overwrite\":true}"))
            .andExpect(status().isNotImplemented());

        Path archive = tempDir.resolve("exports").resolve("work.tar.gz");
        Files.writeString(work.resolve("SOUL.md"), "Work soul.");
        mockMvc.perform(post("/api/profiles/work/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"output\":\"" + jsonEscapePath(archive) + "\",\"extra_files\":{\"desktop.json\":\"{\\\"accent\\\":\\\"blue\\\"}\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.archive").isString());
        org.assertj.core.api.Assertions.assertThat(archive).isRegularFile();

        mockMvc.perform(post("/api/profiles/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("archive path is required"));

        mockMvc.perform(post("/api/profiles/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"archive\":\"" + jsonEscapePath(archive) + "\",\"name\":\"imported\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.name").value("imported"))
            .andExpect(jsonPath("$.desktop.accent").value("blue"));
        org.assertj.core.api.Assertions.assertThat(tempDir.resolve("profiles").resolve("imported"))
            .isDirectory();
    }

    private static String jsonEscapePath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static SessionEntity sessionEntity(
        String title,
        String profile,
        String source,
        int messageCount,
        Instant lastActive
    ) {
        SessionEntity entity = new SessionEntity();
        entity.setId(UUID.nameUUIDFromBytes((profile + ":" + title).getBytes(StandardCharsets.UTF_8)));
        entity.setUserId(AgentProperties.DEFAULT_USER_ID);
        entity.setTitle(title);
        entity.setModelProvider("openai-compatible");
        entity.setModelName("gpt-test");
        entity.setProfile(profile);
        entity.setSource(source);
        entity.setCreatedAt(lastActive.minusSeconds(120));
        entity.setUpdatedAt(lastActive.minusSeconds(30));
        entity.setLastActive(lastActive);
        entity.setMessageCount(messageCount);
        entity.setPreview("preview for " + title);
        return entity;
    }

    private static MessageEntity message(UUID sessionId, String content, int index) {
        MessageEntity entity = new MessageEntity();
        entity.setId(UUID.nameUUIDFromBytes(("message-" + index).getBytes(StandardCharsets.UTF_8)));
        entity.setSessionId(sessionId);
        entity.setRole("tool");
        entity.setContent(content);
        entity.setCreatedAt(BASE_TIME.plusSeconds(index));
        return entity;
    }
}
