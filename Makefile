# One-command entry points. `make demo` works from a fresh clone with only Java 21+ installed.

GRADLE := ./gradlew --console=plain -q
MODEL ?=

.PHONY: help demo run run-naive agent test lint format clean

help: ## Show this help
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "}; {printf "  make %-12s %s\n", $$1, $$2}'

demo: ## Compare both servers on the same tasks, in-process (no API key, no Docker)
	@$(GRADLE) demo

run: ## Start the designed server at http://localhost:8080/mcp
	./gradlew --console=plain bootRun

run-naive: ## Start the naive server at http://localhost:8081/mcp
	./gradlew --console=plain bootRun --args='--spring.profiles.active=naive'

agent: ## Let Claude use each server on the same tasks (needs ANTHROPIC_API_KEY; MODEL=... to pick one)
	@$(GRADLE) agent $(if $(MODEL),-PagentArgs="--model $(MODEL)")

test: ## Unit, MCP and end-to-end tests
	./gradlew --console=plain test

lint: ## Check formatting
	$(GRADLE) spotlessCheck

format: ## Auto-format the code
	$(GRADLE) spotlessApply

clean: ## Remove build output
	$(GRADLE) clean
