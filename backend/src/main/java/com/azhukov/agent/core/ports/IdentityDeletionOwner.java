package com.azhukov.agent.core.ports;

import com.azhukov.agent.core.model.IdentityDeletionProtocol.*;

/** Durable owner barrier; callers must separately verify registered coordinator transport. */
public interface IdentityDeletionOwner {
    /** Counts every declared persisted identity reference under the writer barrier. */
    Summary check(Request request, Window window);
    /** Commits a stable receipt; an exact replay cannot create a second fence. */
    Freeze freeze(Request request, Window window);
    /** Persists cancellation even when no freeze has arrived, preventing a late freeze. */
    Release release(Request request, Window window);
}
