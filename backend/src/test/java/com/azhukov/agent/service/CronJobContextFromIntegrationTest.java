package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.CronExecutionLogEntity;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
import com.azhukov.agent.persistence.repository.CronJobRepository;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.entity.MessageEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Hermes parity integration test for context_from chaining.
 * Verifies that:
 * 1. Job A's output is saved to cron_execution_log.output_text
 * 2. Job B with context_from=A receives Job A's actual output in its prompt
 * 3. Job with context_from=self receives its own previous output
 */
@ExtendWith(MockitoExtension.class)
class CronJobContextFromIntegrationTest {

    @Mock private CronJobRepository cronJobRepository;
    @Mock private CronExecutionLogRepository cronExecutionLogRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private ObjectProvider<AgentRuntimeService> agentRuntimeService;
    @Mock private SkillManager skillManager;

    private AgentProperties properties;
    private CronJobService service;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCron().setEnabled(false);
        lenient().when(agentRuntimeService.getIfAvailable()).thenReturn(null);
        service = new CronJobService(cronJobRepository, agentRuntimeService, properties,
            skillManager, cronExecutionLogRepository, messageRepository,
            new org.springframework.transaction.support.TransactionTemplate(),
            new CronScheduleParser());
    }

    @Test
    void contextFrom_upstreamOutput_injectedIntoPrompt() throws Exception {
        UUID upstreamId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        CronJobEntity upstream = new CronJobEntity();
        upstream.setId(upstreamId);
        upstream.setName("data-collector");
        upstream.setEnabled(false);

        CronJobEntity downstream = new CronJobEntity();
        downstream.setId(UUID.randomUUID());
        downstream.setName("analyzer");
        downstream.setContextFrom(upstreamId.toString());
        downstream.setPrompt("Analyze the data");
        downstream.setEnabled(false);

        // Simulate upstream's last execution with output
        CronExecutionLogEntity upstreamLog = CronExecutionLogEntity.create(
            upstreamId, Instant.now().minusSeconds(3600), Instant.now().minusSeconds(3500),
            "success", null);
        upstreamLog.setOutputText("## Research Results\nFound 5 critical issues:\n1. Memory leak in X\n2. Race condition in Y");

        when(cronJobRepository.findById(upstreamId)).thenReturn(Optional.of(upstream));
        when(cronExecutionLogRepository.findFirstByJobIdOrderByStartedAtDesc(upstreamId))
            .thenReturn(Optional.of(upstreamLog));

        // Invoke loadContextFromOutput via reflection
        java.lang.reflect.Method method = CronJobService.class
            .getDeclaredMethod("loadContextFromOutput", CronJobEntity.class);
        method.setAccessible(true);
        String context = (String) method.invoke(service, downstream);

        assertThat(context).isNotNull();
        assertThat(context).contains("Output from job 'data-collector'");
        assertThat(context).contains("## Research Results");
        assertThat(context).contains("Memory leak in X");
        assertThat(context).contains("Race condition in Y");
        assertThat(context).contains("```");
    }

    @Test
    void contextFrom_self_injectsOwnPreviousOutput() throws Exception {
        UUID jobId = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(jobId);
        job.setName("continuation-job");
        job.setContextFrom("self");
        job.setPrompt("Continue the work");
        job.setEnabled(false);

        CronExecutionLogEntity previousLog = CronExecutionLogEntity.create(
            jobId, Instant.now().minusSeconds(3600), Instant.now().minusSeconds(3500),
            "success", null);
        previousLog.setOutputText("Previous run found 3 items and was processing item 2");

        when(cronJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(cronExecutionLogRepository.findFirstByJobIdOrderByStartedAtDesc(jobId))
            .thenReturn(Optional.of(previousLog));

        java.lang.reflect.Method method = CronJobService.class
            .getDeclaredMethod("loadContextFromOutput", CronJobEntity.class);
        method.setAccessible(true);
        String context = (String) method.invoke(service, job);

        assertThat(context).isNotNull();
        assertThat(context).contains("Your previous run's output");
        assertThat(context).contains("Previous run found 3 items");
        assertThat(context).contains("avoid repeating");
        assertThat(context).contains("continue");
    }

    @Test
    void contextFrom_emptyOutput_returnsNull() throws Exception {
        UUID upstreamId = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(UUID.randomUUID());
        job.setContextFrom(upstreamId.toString());
        job.setPrompt("Run");
        job.setEnabled(false);

        CronJobEntity upstream = new CronJobEntity();
        upstream.setId(upstreamId);
        upstream.setName("empty-source");

        when(cronJobRepository.findById(upstreamId)).thenReturn(Optional.of(upstream));
        when(cronExecutionLogRepository.findFirstByJobIdOrderByStartedAtDesc(upstreamId))
            .thenReturn(Optional.empty());

        java.lang.reflect.Method method = CronJobService.class
            .getDeclaredMethod("loadContextFromOutput", CronJobEntity.class);
        method.setAccessible(true);
        String context = (String) method.invoke(service, job);

        assertThat(context).isNull();
    }

    @Test
    void contextFrom_multipleUpstreams_concatenates() throws Exception {
        UUID upstream1 = UUID.randomUUID();
        UUID upstream2 = UUID.randomUUID();

        CronJobEntity job1 = new CronJobEntity();
        job1.setId(upstream1);
        job1.setName("source-1");

        CronJobEntity job2 = new CronJobEntity();
        job2.setId(upstream2);
        job2.setName("source-2");

        CronJobEntity target = new CronJobEntity();
        target.setId(UUID.randomUUID());
        target.setContextFrom(upstream1 + "," + upstream2);
        target.setPrompt("Combine");
        target.setEnabled(false);

        CronExecutionLogEntity log1 = CronExecutionLogEntity.create(
            upstream1, Instant.now(), Instant.now(), "success", null);
        log1.setOutputText("Data from source 1");

        CronExecutionLogEntity log2 = CronExecutionLogEntity.create(
            upstream2, Instant.now(), Instant.now(), "success", null);
        log2.setOutputText("Data from source 2");

        when(cronJobRepository.findById(upstream1)).thenReturn(Optional.of(job1));
        when(cronJobRepository.findById(upstream2)).thenReturn(Optional.of(job2));
        when(cronExecutionLogRepository.findFirstByJobIdOrderByStartedAtDesc(upstream1))
            .thenReturn(Optional.of(log1));
        when(cronExecutionLogRepository.findFirstByJobIdOrderByStartedAtDesc(upstream2))
            .thenReturn(Optional.of(log2));

        java.lang.reflect.Method method = CronJobService.class
            .getDeclaredMethod("loadContextFromOutput", CronJobEntity.class);
        method.setAccessible(true);
        String context = (String) method.invoke(service, target);

        assertThat(context).isNotNull();
        assertThat(context).contains("source-1");
        assertThat(context).contains("Data from source 1");
        assertThat(context).contains("source-2");
        assertThat(context).contains("Data from source 2");
    }

    @Test
    void recordExecution_persistsOutputText() {
        // Verify recordExecution saves outputText when provided
        UUID jobId = UUID.randomUUID();

        when(cronExecutionLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Use reflection to call recordExecution with outputText
        try {
            java.lang.reflect.Method method = CronJobService.class
                .getDeclaredMethod("recordExecution", UUID.class, Instant.class, Instant.class,
                    String.class, String.class, String.class);
            method.setAccessible(true);
            method.invoke(service, jobId, Instant.now(), Instant.now(), "success", null, "Test output");

            verify(cronExecutionLogRepository).save(argThat((CronExecutionLogEntity e) ->
                "Test output".equals(e.getOutputText())));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void loadLastRunOutput_extractsLastAssistantMessage() {
        UUID sessionId = UUID.randomUUID();

        MessageEntity msg1 = new MessageEntity();
        msg1.setRole("user");
        msg1.setContent("question");

        MessageEntity msg2 = new MessageEntity();
        msg2.setRole("assistant");
        msg2.setContent("first answer");

        MessageEntity msg3 = new MessageEntity();
        msg3.setRole("assistant");
        msg3.setContent("final answer");

        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId))
            .thenReturn(List.of(msg1, msg2, msg3));

        try {
            java.lang.reflect.Method method = CronJobService.class
                .getDeclaredMethod("loadLastRunOutput", UUID.class);
            method.setAccessible(true);
            String output = (String) method.invoke(service, sessionId);

            assertThat(output).isEqualTo("final answer");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void loadLastRunOutput_nullSession_returnsNull() {
        try {
            java.lang.reflect.Method method = CronJobService.class
                .getDeclaredMethod("loadLastRunOutput", UUID.class);
            method.setAccessible(true);
            String output = (String) method.invoke(service, (UUID) null);

            assertThat(output).isNull();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}