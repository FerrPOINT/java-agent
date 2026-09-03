package com.azhukov.agent.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class BrowserControlDisabledController {

    @PostMapping({"/v1/browser-control/register", "/p/{profile}/v1/browser-control/register"})
    public ResponseEntity<Map<String, Object>> register() {
        return browserControlDisabled();
    }

    @GetMapping({"/v1/browser-control/ws", "/p/{profile}/v1/browser-control/ws"})
    public ResponseEntity<Void> websocket() {
        return ResponseEntity.notFound().build();
    }

    @PostMapping({"/v1/artifacts/upload", "/p/{profile}/v1/artifacts/upload"})
    public ResponseEntity<Map<String, Object>> uploadArtifact() {
        return browserControlDisabled();
    }

    @GetMapping({"/v1/artifacts/download/{artifactId}", "/p/{profile}/v1/artifacts/download/{artifactId}"})
    public ResponseEntity<Void> downloadArtifact(@PathVariable String artifactId) {
        return ResponseEntity.notFound().build();
    }

    private ResponseEntity<Map<String, Object>> browserControlDisabled() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", "Browser control is not enabled on this server.");
        error.put("type", "invalid_request_error");
        error.put("param", null);
        error.put("code", "browser_control_disabled");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", error));
    }
}
