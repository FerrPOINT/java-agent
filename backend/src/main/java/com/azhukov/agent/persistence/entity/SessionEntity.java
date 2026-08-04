package com.azhukov.agent.persistence.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "sessions")
@Data
public class SessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String userId;

    private String title;

    private String modelProvider;

    private String modelName;

    private Instant createdAt;

    private Instant updatedAt;

    private String subgoal;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "session_cli_state", joinColumns = @JoinColumn(name = "session_id"))
    @MapKeyColumn(name = "state_key")
    @Column(name = "state_value")
    private Map<String, String> cliState = new HashMap<>();

    public String getCliStateValue(String key) {
        return cliState != null ? cliState.get(key) : null;
    }

    public void setCliStateValue(String key, String value) {
        if (cliState == null) {
            cliState = new HashMap<>();
        }
        cliState.put(key, value);
    }

    public void removeCliStateValue(String key) {
        if (cliState != null) {
            cliState.remove(key);
        }
    }
}
