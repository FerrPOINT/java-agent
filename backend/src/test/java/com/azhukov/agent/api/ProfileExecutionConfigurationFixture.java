package com.azhukov.agent.api;

import com.azhukov.agent.service.ProfileExecutionConfiguration;

import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

final class ProfileExecutionConfigurationFixture {
    static ProfileExecutionConfiguration settings() {
        var configuration = mock(ProfileExecutionConfiguration.class);
        var snapshot = mock(ProfileExecutionConfiguration.Snapshot.class);
        lenient().when(snapshot.revision()).thenReturn("a".repeat(64));
        lenient().when(snapshot.baseInstructions()).thenReturn(new com.azhukov.agent.service.ProfileInstructions.BasePackage(
            null, "", java.util.Map.of(), null));
        lenient().when(snapshot.use(any())).thenAnswer(call -> call.<Supplier<?>>getArgument(0).get());
        lenient().when(configuration.capture(any())).thenReturn(snapshot);
        return configuration;
    }
}
