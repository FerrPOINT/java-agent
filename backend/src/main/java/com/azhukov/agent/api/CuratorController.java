package com.azhukov.agent.api;

import com.azhukov.agent.core.skill.CuratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Curator", description = "Skill curator status, run, pause, resume")
public class CuratorController {

    private final CuratorService curatorService;

    @Operation(summary = "Get curator service status")
    @GetMapping("/agent/curator/status")
    public Map<String, Object> curatorStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", curatorService.isEnabled());
        status.put("paused", curatorService.isPaused());
        status.put("dryRun", curatorService.isDryRun());
        status.put("intervalHours", curatorService.getIntervalHours());
        status.put("minIdleHours", curatorService.getMinIdleHours());
        status.put("staleAfterDays", curatorService.getStaleAfterDays());
        status.put("archiveAfterDays", curatorService.getArchiveAfterDays());
        return status;
    }

    @Operation(summary = "Run a curator cycle")
    @PostMapping("/agent/curator/run")
    public String curatorRun() {
        var report = curatorService.runCycle();
        return report != null ? report.toString() : "Curator cycle completed (no report)";
    }

    @PostMapping("/agent/curator/pause")
    public void curatorPause() {
        curatorService.setPaused(true);
    }

    @PostMapping("/agent/curator/resume")
    public void curatorResume() {
        curatorService.setPaused(false);
    }
}