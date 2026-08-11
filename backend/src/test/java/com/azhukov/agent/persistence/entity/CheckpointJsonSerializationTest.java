package com.azhukov.agent.persistence.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug 4: CheckpointEntity ↔ CheckpointFileEntity bidirectional relationship
 * caused infinite JSON recursion (nesting depth 501) during serialization.
 * Fix: @JsonIgnore on CheckpointFileEntity.checkpoint back-reference.
 */
class CheckpointJsonSerializationTest {

    @Test
    void checkpointEntitySerializesWithoutInfiniteRecursion() throws Exception {
        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(UUID.randomUUID());
        entity.setDescription("Test checkpoint");
        entity.setFileCount(2);
        entity.setTotalSizeBytes(1024);
        entity.setFilesJson("[]");
        // Note: not setting createdAt to avoid JavaTime serialization issues in unit test

        CheckpointFileEntity file1 = new CheckpointFileEntity();
        file1.setId(UUID.randomUUID());
        file1.setCheckpoint(entity);
        file1.setFilePath("src/main.java");
        file1.setFileHash("abc123");
        file1.setFileSize(512);
        file1.setContentBase64("dGVzdA==");

        CheckpointFileEntity file2 = new CheckpointFileEntity();
        file2.setId(UUID.randomUUID());
        file2.setCheckpoint(entity);
        file2.setFilePath("src/test.java");
        file2.setFileHash("def456");
        file2.setFileSize(512);
        file2.setContentBase64("dGVzdDI=");

        List<CheckpointFileEntity> files = new ArrayList<>();
        files.add(file1);
        files.add(file2);
        entity.setFiles(files);

        ObjectMapper mapper = new ObjectMapper();
        // Before fix: this throws JsonMappingException "Nesting depth (501) exceeds limit"
        String json = mapper.writeValueAsString(entity);

        assertNotNull(json);
        assertFalse(json.contains("\"checkpoint\""), "Back-reference 'checkpoint' should be @JsonIgnore'd");
        assertTrue(json.contains("\"filePath\""));
        assertTrue(json.contains("src/main.java"));
    }

    @Test
    void checkpointFileEntityDoesNotSerializeCheckpointBackReference() throws Exception {
        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(UUID.randomUUID());
        entity.setDescription("Parent");

        CheckpointFileEntity file = new CheckpointFileEntity();
        file.setId(UUID.randomUUID());
        file.setCheckpoint(entity);
        file.setFilePath("test.txt");
        file.setFileHash("hash123");
        file.setFileSize(100);
        file.setContentBase64("dGVzdA==");

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(file);

        assertNotNull(json);
        assertFalse(json.contains("\"checkpoint\""), "Back-reference 'checkpoint' should be @JsonIgnore'd");
        assertFalse(json.contains("\"files\""), "Should not serialize parent's files collection");
        assertTrue(json.contains("\"filePath\""));
        assertTrue(json.contains("test.txt"));
    }

    @Test
    void checkpointEntityRoundTripsThroughJsonWithoutStackOverflow() {
        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(UUID.randomUUID());
        entity.setDescription("Deep nesting test");
        entity.setFileCount(1);
        entity.setTotalSizeBytes(100);

        CheckpointFileEntity file = new CheckpointFileEntity();
        file.setId(UUID.randomUUID());
        file.setCheckpoint(entity);
        file.setFilePath("file.txt");
        file.setFileHash("hash");
        file.setFileSize(100);

        entity.setFiles(List.of(file));

        ObjectMapper mapper = new ObjectMapper();
        assertDoesNotThrow(() -> mapper.writeValueAsString(entity),
            "Serialization should not throw StackOverflow / nesting depth exceeded");
    }
}