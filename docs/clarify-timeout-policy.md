# Clarify Timeout Policy

A blocking clarify prompt waits for a human decision. It is not a short request
or model timeout.

The shipped default is one day (`86400` seconds). This is intentional: it lets a
user return to a Telegram choice prompt after stepping away without silently
turning the answer into a new conversation turn.

For unattended or asynchronous channels, configure a window of two to seven
days when the transport connection, session retention, and operational capacity
are designed to preserve the original turn for that period.

The timeout remains necessary. It releases abandoned pending entries, agent
threads, and session locks. A reply after expiry is rejected rather than being
applied to stale work.

Every transport deadline that carries the blocked turn must outlive the selected
clarify timeout. The Telegram bot and its backend SSE client use eight-day
transport windows, exceeding the recommended seven-day maximum. This preserves
the original stream through a valid delayed answer rather than turning it into a
new conversation turn.
