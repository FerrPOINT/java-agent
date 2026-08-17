# java-agent Makefile — build, test, version, install
# Usage:
#   make build       — compile all modules
#   make test        — run all tests
#   make jar         — build fat JARs
#   make install     — build + test + jar + install to /opt/java-agent
#   make version     — show current version
#   make patch       — bump patch version (0.1.0 → 0.1.1)
#   make minor       — bump minor version (0.1.0 → 0.2.0)
#   make release     — bump patch + build + test + jar + install + git tag
#   make clean       — clean all build dirs
#   make run         — run backend with dev profile
#   make cli         — run CLI REPL
#   make bot         — run telegram bot
#   make coverage    — generate coverage report

SHELL := /bin/bash
GRADLE := ./gradlew
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

.PHONY: build test jar install version patch minor release clean run cli bot coverage help

help:
	@echo "java-agent Makefile — commands:"
	@echo "  make build       — compile all modules"
	@echo "  make test        — run all tests"
	@echo "  make jar         — build fat JARs"
	@echo "  make install     — build + test + jar + install to $(INSTALL_DIR)"
	@echo "  make version     — show current version"
	@echo "  make patch       — bump patch version"
	@echo "  make minor       — bump minor version"
	@echo "  make release     — bump patch + build + test + jar + install + git tag"
	@echo "  make clean       — clean all build dirs"
	@echo "  make run         — run backend (dev profile)"
	@echo "  make cli         — run CLI REPL"
	@echo "  make bot         — run telegram bot"
	@echo "  make coverage    — generate coverage report"

version:
	@echo "$(GREEN)java-agent version: $(VERSION)$(NC)"

build:
	@echo "$(CYAN)Building java-agent v$(VERSION)...$(NC)"
	$(GRADLE) compileJava compileTestJava
	@echo "$(GREEN)Build successful.$(NC)"

test: build
	@echo "$(CYAN)Running tests...$(NC)"
	$(GRADLE) test
	@echo "$(GREEN)All tests passed.$(NC)"

jar: test
	@echo "$(CYAN)Building JARs...$(NC)"
	$(GRADLE) bootJar
	@echo "$(GREEN)JARs built.$(NC)"

coverage:
	$(GRADLE) jacocoTestReport
	@echo "Coverage report: backend/build/reports/jacoco/test/html/index.html"

clean:
	$(GRADLE) clean

install: jar
	@echo "$(CYAN)Installing java-agent v$(VERSION) to $(INSTALL_DIR)...$(NC)"
	@mkdir -p $(INSTALL_DIR)/bin
	@mkdir -p $(INSTALL_DIR)/lib
	@mkdir -p $(INSTALL_DIR)/config
	@cp backend/build/libs/backend-0.0.1-SNAPSHOT.jar $(INSTALL_DIR)/lib/java-agent-backend-$(VERSION).jar
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