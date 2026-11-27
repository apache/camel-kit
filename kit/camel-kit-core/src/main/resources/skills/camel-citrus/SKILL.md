---
name: camel-citrus
description: Use when authoring or running Citrus integration tests for selected Camel routes.
user_invocable: false
---

# Test a Camel route with Citrus

Read shared/direct-project.md. Identify the route, module, Camel runtime and resolved Citrus version. Inspect
existing tests for the fixture and runner conventions. Ask for the expected input/output or permitted test
service when the project does not establish it. For a request to run an existing test, use the selected
test file and its explicitly permitted effects.

Invoke camel-test/SKILL.md with those inputs, the developer's requested action (author, run, or both), and
the permitted endpoints. Return its test paths, behavior covered and observed results to the developer.
Keep the test files in the selected project. A direct call has no plan, design spec or pipeline ID.
