# java-agent Makefile — build, test, version, install
# Usage:
#   make build       — compile and run reviewed units (explicit MODULE)
#   make test        — run reviewed units (MODULE=backend|cli|bot|shared|all)
#   make jar         — build selected JARs (explicit MODULE)
#   make install     — build + test + jar + install to /opt/java-agent
#   make version     — show current version
#   make patch       — bump patch version (0.1.0 → 0.1.1)
#   make minor       — bump minor version (0.1.0 → 0.2.0)
#   make release     — bump patch + build + test + jar + install + git tag
#   make clean       — clean all build dirs
#   make run         — run backend with dev profile
#   make cli         — run CLI REPL
#   make bot         — run telegram bot
#   make coverage    — manual full coverage (MODULE=backend|bot|all)

SHELL := /bin/bash
GRADLE := ./gradlew
MODULE ?= $(if $(filter install release,$(MAKECMDGOALS)),all,)
ifneq ($(filter build test jar coverage,$(MAKECMDGOALS)),)
ifeq ($(strip $(MODULE)),)
$(error Select MODULE=backend|cli|bot|shared; MODULE=all is an explicit full graph)
endif
endif
INSTALL_DIR := /opt/java-agent
VERSION_FILE := .version
JAVA := java

# Version management
VERSION := $(shell cat $(VERSION_FILE) 2>/dev/null || echo "0.1.0")

# Colors
RED := \033[0;31m
GREEN := \033[0;32m
YELLOW := \033[0;33m
CYAN := \033[0;36m
NC := \033[0m

.PHONY: build test jar coverage quality-graph install version patch minor release clean run cli bot help
# Backend and root wrappers share this host's build budget, including make -j.
.NOTPARALLEL:
ifneq ($(filter install release,$(MAKECMDGOALS)),)
ifneq ($(MODULE),all)
$(error install/release require MODULE=all)
endif
endif

help:
	@echo "java-agent Makefile — commands:"
	@echo "  make build       — compile and run reviewed units (explicit MODULE)"
	@echo "  make test        — reviewed units (MODULE=backend|cli|bot|shared|all)"
	@echo "  make jar         — build selected JARs (explicit MODULE)"
	@echo "  make install     — build + test + jar + install to $(INSTALL_DIR)"
	@echo "  make version     — show current version"
	@echo "  make patch       — bump patch version"
	@echo "  make minor       — bump minor version"
	@echo "  make release     — bump patch + build + test + jar + install + git tag"
	@echo "  make clean       — clean all build dirs"
	@echo "  make run         — run backend (dev profile)"
	@echo "  make cli         — run CLI REPL"
	@echo "  make bot         — run telegram bot"
	@echo "  make coverage    — manual full coverage (MODULE=backend|bot|all)"
	@echo "  make parity-dashboard  — show parity status snapshot"
	@echo "  make skill-update       — refresh dashboard + show block for skill"

version:
	@echo "$(GREEN)java-agent version: $(VERSION)$(NC)"

# Coalesce all explicit goals before starting either compatible wrapper. Reports
# imply tests, while all+coverage alone must not add CLI/shared unit consumers.
ifneq ($(filter jar install release,$(MAKECMDGOALS)),)
QUALITY_OPERATION := jar
else ifneq ($(filter build test,$(MAKECMDGOALS)),)
QUALITY_OPERATION := test
else ifneq ($(filter coverage,$(MAKECMDGOALS)),)
QUALITY_OPERATION := coverage
else
QUALITY_OPERATION := test
endif
QUALITY_COVERAGE := $(if $(filter coverage,$(MAKECMDGOALS)),--coverage,)
QUALITY_CANDIDATE := $(if $(filter install release,$(MAKECMDGOALS)),--candidate-epoch,)
build test jar coverage: quality-graph
quality-graph:
	bash scripts/quality-make.sh $(QUALITY_OPERATION) "$(MODULE)" $(QUALITY_COVERAGE) $(QUALITY_CANDIDATE)

clean:
	$(GRADLE) clean

install: jar
	@echo "$(CYAN)Installing java-agent v$(VERSION) to $(INSTALL_DIR)...$(NC)"
	@mkdir -p $(INSTALL_DIR)/bin
	@mkdir -p $(INSTALL_DIR)/lib
	@mkdir -p $(INSTALL_DIR)/config
	@cp backend/build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar $(INSTALL_DIR)/lib/java-agent-backend-$(VERSION).jar
	@cp cli/build/libs/cli-0.0.1-SNAPSHOT.jar $(INSTALL_DIR)/lib/java-agent-cli-$(VERSION).jar
	@cp telegram-bot/build/libs/telegram-bot-0.0.1-SNAPSHOT.jar $(INSTALL_DIR)/lib/java-agent-bot-$(VERSION).jar
	@# Symlinks for latest version
	@ln -sf java-agent-backend-$(VERSION).jar $(INSTALL_DIR)/lib/java-agent-backend-latest.jar
	@ln -sf java-agent-cli-$(VERSION).jar $(INSTALL_DIR)/lib/java-agent-cli-latest.jar
	@ln -sf java-agent-bot-$(VERSION).jar $(INSTALL_DIR)/lib/java-agent-bot-latest.jar
	@# Version file
	@echo "$(VERSION)" > $(INSTALL_DIR)/VERSION
	@# Start scripts
	@echo '#!/bin/bash' > $(INSTALL_DIR)/bin/java-agent
	@echo 'exec $(JAVA) -jar $(INSTALL_DIR)/lib/java-agent-backend-latest.jar --spring.profiles.active=dev $$@' >> $(INSTALL_DIR)/bin/java-agent
	@chmod +x $(INSTALL_DIR)/bin/java-agent
	@echo '#!/bin/bash' > $(INSTALL_DIR)/bin/java-agent-cli
	@echo 'exec $(JAVA) -jar $(INSTALL_DIR)/lib/java-agent-cli-latest.jar $$@' >> $(INSTALL_DIR)/bin/java-agent-cli
	@chmod +x $(INSTALL_DIR)/bin/java-agent-cli
	@echo '#!/bin/bash' > $(INSTALL_DIR)/bin/java-agent-bot
	@echo 'exec $(JAVA) -jar $(INSTALL_DIR)/lib/java-agent-bot-latest.jar $$@' >> $(INSTALL_DIR)/bin/java-agent-bot
	@chmod +x $(INSTALL_DIR)/bin/java-agent-bot
	@echo "$(GREEN)Installed v$(VERSION) to $(INSTALL_DIR)$(NC)"
	@echo "  Binaries: $(INSTALL_DIR)/bin/java-agent{,-cli,-bot}"
	@echo "  Libs:     $(INSTALL_DIR)/lib/java-agent-*-$(VERSION).jar"
	@echo "  Version:  $(INSTALL_DIR)/VERSION"

patch:
	@$(eval NEW_VERSION := $(shell echo "$(VERSION)" | awk -F. '{print $$1"."$$2"."$$3+1}'))
	@echo "$(NEW_VERSION)" > $(VERSION_FILE)
	@echo "$(YELLOW)Version bumped: $(VERSION) → $(NEW_VERSION)$(NC)"

minor:
	@$(eval NEW_VERSION := $(shell echo "$(VERSION)" | awk -F. '{print $$1"."$$2+1".0}'))
	@echo "$(NEW_VERSION)" > $(VERSION_FILE)
	@echo "$(YELLOW)Version bumped: $(VERSION) → $(NEW_VERSION)$(NC)"

release: patch install
	@echo "$(CYAN)Tagging release v$(shell cat $(VERSION_FILE))...$(NC)"
	@git add $(VERSION_FILE)
	@git commit -m "release: v$(shell cat $(VERSION_FILE))" || true
	@git tag "v$(shell cat $(VERSION_FILE))" || true
	@echo "$(GREEN)Release v$(shell cat $(VERSION_FILE)) complete.$(NC)"

run:
	$(GRADLE) :backend:bootRun --args='--spring.profiles.active=dev'

cli:
	$(GRADLE) :cli:bootRun --args='--spring.profiles.active=cli'

bot:
	$(GRADLE) :telegram-bot:bootRun --args='--spring.profiles.active=dev'
# ── Parity workflow ──────────────────────────────────────────────
# One-command status snapshot for the java-agent-porting skill.
# Run after every parity session BEFORE updating the skill — the dashboard
# numbers (tests/version/migrations/CI) are the source of truth to paste.
parity-dashboard:
	@python3 scripts/parity-dashboard.py

# Refresh dashboard + show the compact block to paste into the skill:
#   ~/.hermes/skills/software-development/java-agent-porting/references/hermes-update-procedure.md
skill-update:
	@python3 scripts/parity-dashboard.py --stdout
	@echo ""
	@echo ">>> Paste the block above into the skill's 'Current Parity Status' header,"
	@echo ">>> then append session findings to 'Fully Ported' / 'Known Gaps'."
