-- Named profile rows previously described configuration and skills only.
-- Every existing named profile remained eligible to start API work.
UPDATE profile_runtime_state
SET worker_state = 'running', updated_at = CURRENT_TIMESTAMP
WHERE profile <> 'default' AND worker_state = 'stopped';
