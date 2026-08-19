package com.azhukov.agent.health;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DatabaseHealthIndicatorTest {

    @Test
    void upWhenConnectionValid() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.isValid(5)).thenReturn(true);
        DatabaseHealthIndicator h = new DatabaseHealthIndicator(ds);
        assertThat(h.health().getStatus()).isEqualTo(org.springframework.boot.health.contributor.Status.UP);
    }

    @Test
    void downWhenConnectionInvalid() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.isValid(5)).thenReturn(false);
        DatabaseHealthIndicator h = new DatabaseHealthIndicator(ds);
        assertThat(h.health().getStatus()).isEqualTo(org.springframework.boot.health.contributor.Status.DOWN);
    }

    @Test
    void downOnException() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new RuntimeException("boom"));
        DatabaseHealthIndicator h = new DatabaseHealthIndicator(ds);
        assertThat(h.health().getStatus()).isEqualTo(org.springframework.boot.health.contributor.Status.DOWN);
    }
}
