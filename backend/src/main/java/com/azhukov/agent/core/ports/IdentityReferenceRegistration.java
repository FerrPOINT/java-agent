package com.azhukov.agent.core.ports;

import com.azhukov.agent.core.model.IdentityDeletionProtocol.Window;

/** Records actual verified provenance without deriving identity from a UUID or email. */
public interface IdentityReferenceRegistration {
    /** Retains the exact freshly verified central binding before content can refer to it. */
    void central(String issuer, String subject, Window window);
    /** Retains a new storage key only when the actual native caller can name that native scope. */
    void nativeStorage(String storageKey);
}
