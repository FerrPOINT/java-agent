package com.azhukov.agent.api.dto;

public record DoctorDto(
    String name,
    String version,
    String status,
    String model,
    String provider,
    int maxTurns,
    int maxModelCallsPerTurn,
    boolean memoryEnabled,
    boolean ttsEnabled,
    boolean transcriptionEnabled,
    long skillCount,
    long activeSessionCount
) {}
