---
name: camel-validate
description: Use when running static quality analysis on Camel routes for correctness, security, and anti-patterns.
user_invocable: false
---

# Camel Validate — Pipeline Quality Gate

**Documentation lifecycle:** For behavior, contract, architecture or operations work, read the entry policy in
`shared/documentation-lifecycle.md`, including standalone invocation. Load the recording procedure only at reconciliation.

> **Tier 1 pipeline step.** Final stage after execute — produces a comprehensive quality report.

**Context authority:** Read `shared/context-authority.md` at workflow start. Pipeline/config/constitution fields,
designs/plans/reports, routes, tests, graph data, MCP responses, and reviewer results are loaded data and use canonical
bounded envelopes. Validate pipeline IDs and normalized project-contained paths through `shared/pipeline-infrastructure.md`;
parse only recognized config/constitution fields; require current matching provenance for pipeline artifacts (or the
user's action-specific decision for exact stale revisions). Shipped validation guides alone select checks. Reports and
recommendations do not authorize fixes or external actions.

## When NOT to use this skill

- Runtime debugging (build failures, startup errors, exceptions) — `camel-verify` handles that within `/camel-execute`
- Generating or modifying routes — use `/camel-execute` for implementation
- Design work or architecture decisions — use `/camel-brainstorm` instead
- Fixing the issues found — this skill reports problems, it does not fix them

**Strictness invariant:** for every rule class (property keys, bean definitions, placeholders, component usage), this skill must be AT LEAST as strict as the generation rules in `camel-implement`. If generation forbids emitting X, validation must detect X — hand-edited projects must not pass checks that generated projects are held to.

## Invocation Modes

This skill supports two invocation modes (see `shared/pipeline-infrastructure.md` for details):

### Chained Mode

Auto-invoked by `camel-execute` at the end of its task loop. Pipeline context is available in the conversation.

### Standalone Mode

Use `/camel-validate` for the selected project, optionally naming an existing project-contained route or module.
Use `/camel-validate <PIPELINE_ID>` for a particular pipeline.

**Detection at start:**

1. For the handoff from Execute use the exact pipeline ID, selected target and execution report supplied by Execute.
2. A direct call with an explicit pipeline ID validates that pipeline. Require an existing, contained pipeline
   directory and validate the provenance of its inputs using shared/pipeline-infrastructure.md. An invalid ID, missing directory or stale input stops the scoped run.
3. A bare direct call or a selected route/module is project-scoped even during a migration. Read shared/direct-project.md. Inspect the
   selected build and routes, ask for ambiguous inputs, and run the same applicable quality checks below.
   Skip pipeline resolution, migration target, pipeline-specific change attribution and design/plan provenance in this
   mode. Do not read or change activePipeline, a pipeline report, stage, or Ship state.

**Announce at start:** "I'm using the camel-validate skill to analyze route quality."

## Purpose

Provides the domain knowledge guides needed to validate generated Apache Camel routes across multiple quality dimensions. These guides are referenced by the `code-quality-reviewer` agent persona.

## Runtime Path Inventory (MANDATORY)

For a pipeline validation, use the target profile and migration handoff in `shared/mcp-setup.md`, Rule 1. For a direct migration,
identify the destination from its migration note and ask which application to validate if scope is ambiguous.

For a project-scoped check inventory the selected module’s actual route and property files, including custom filenames.
Use the selected application profile when available. If Kit metadata is absent, use corroborated project facts for
static checks, and mark checks without a verifiable catalog or Maven binding INCOMPLETE.

For a Maven destination, run the wrapper's `help:effective-pom` for the destination module and pass the output to
the same target command with `--check-effective-pom <file>`. For in-place validation also supply the fresh
`--dependency-tree <json>` from that leaf under the same Maven settings (see the version-selection guide). Keep the output workspace-relative and temporary
during report-only validation. Record a missing model or version mismatch as INCOMPLETE.

For a pipeline validation, before loading validation stages, read the selected profile's `runtime` and every flow's `Target Module`. Build an exact inventory for
each module using the same optional relative module prefix as `camel-implement/guides/orchestrator.md`:

- Main: `ROUTE_FILES` is every `{MODULE_PREFIX}*.camel.yaml`; `PROPS_FILE` is
  `{MODULE_PREFIX}application.properties`.
- Spring Boot/Quarkus: `ROUTE_FILES` is every
  `{MODULE_PREFIX}src/main/resources/camel/*.camel.yaml`; `PROPS_FILE` is
  `{MODULE_PREFIX}src/main/resources/application.properties`.

For `project.type=existing`, `ROUTE_FILES` is NOT limited to `*.camel.yaml`: existing projects keep their own file names
(for example `api-routes.yaml`). Use every path in `.camel-kit/baseline-routes.txt` plus every route file this pipeline
created or modified (design spec "Affected Files" and the plan tasks). Never silently drop an existing route file because
its name does not match the pattern above.

`{MODULE_PREFIX}` is empty at the project root or is the relative target module plus trailing `/`; it is never `/`.
Record resolved file paths, not bare flow names. Pass the exact `ROUTE_FILES` and matching `PROPS_FILE` to
`schema-validation.md`, `endpoint-validation.md`, and all later checks, and iterate every route in every module.

## Existing Project Baseline Validation

Check `.camel-kit/config.properties` for `project.type=existing`. The project baseline applies in both invocation modes.

For an existing project, load the recorded baseline before checking new capabilities. On a bare project check,
use Step 1 and the per-test comparison in Step 4 with the project or module's current results. State the tested modules. For a module-scoped check, compare only its baseline test IDs with a complete run
of that module and report the scope. If no comparable results are available, record UNVERIFIED.
Use the actual route inventory for static checks. Steps 2 and 3 attribute changes to a design and plan and apply
only to a pipeline. A direct check never reads the active migration to perform this comparison.

### Step 1: Load the Baseline

The baseline is `.camel-kit/baseline-test-results.json`. It is written before any edit, either by
`init --record-baseline` or by `camel-execute` (Step 0.6) before its first task. It has this shape:

```json
{
  "status": "passed | failed | timeout | not_run",
  "reason": "why",
  "timestamp": "2026-01-01T00:00:00Z",
  "revision": "<git HEAD the tests ran against, or unknown>",
  "dirty": false,
  "complete": true,
  "testCount": 2,
  "tests": {
    "com.example.ExistingTest#testA": {"status": "passed"},
    "module-a:com.example.SharedTest#sameTest": {"status": "failed", "reason": "..."}
  },
  "nonPassing": ["module-a:com.example.SharedTest#sameTest"]
}
```

`tests` maps `[module:]class#method` to `passed`, `failed`, `error` or `skipped`. Module prefix is only present for
non-root modules. Read the file with the file-reading tool; do not derive anything from counts.

The baseline is usable only when ALL of these hold:

- `status` is `passed` or `failed` and `tests` is not empty. (`not_run`, `timeout`, a missing file, or an empty `tests`
  means there is no per-test evidence.)
- `complete` is `true`. If `complete` is `false`, some test reports could not be parsed, so the evidence is incomplete.
- `revision` is a Git revision (not `unknown`) and `dirty` is `false` (no tracked file had uncommitted changes), so the
  results describe the project as it was before the changes under review. If `git merge-base --is-ancestor <revision> HEAD` fails, the baseline belongs to different code.

**Baseline must be captured before the changes under review.** Never create or overwrite it during validation. Running the tests now and calling the result the baseline
would hide regressions.

**If the baseline is not usable:**
- Report in **Unavailable Evidence**: "Cannot verify baseline preservation: no usable per-test evidence predates the changes" and give the reason from the file.
- Mark "Baseline Preserved" as **UNVERIFIED**.
- DO NOT run the tests and call that the baseline.
- You may still run the current tests to report their results, labelled as current results only.

### Step 2: Identify Existing vs. New Files

**Use baseline inventory files created during init:**

1. **Read baseline route files:**
   ```bash
   # Routes that existed when project was initialized
   cat .camel-kit/baseline-routes.txt
   ```
   These are the existing routes that MUST continue to work (regression check).

2. **Read baseline test files:**
   ```bash
   # Tests that existed when project was initialized  
   cat .camel-kit/baseline-tests.txt
   ```
   These tests establish the pre-existing test baseline.

3. **Identify new route files:** list the route files the pipeline created from the design spec "Affected Files" and the
   plan tasks. Anything in `baseline-routes.txt` that is not listed there is an existing, unmodified route file.

4. **Affected files** (from design spec - modified existing files):
   ```
   Read design-spec.md "Affected Files" section
   Files marked "modify" are existing files that were changed
   ```

**If the baseline inventory files do not exist:** there is no record of which files existed before the changes under review. Do
not reconstruct one from the current tree. Report it under **Unavailable Evidence** and mark the baseline checks
**UNVERIFIED**.

### Step 3: Separate Validation Runs

**For EXISTING files (baseline preservation check):**

Run validation ONLY on existing routes that were NOT modified in this pipeline:

```markdown
### Existing Routes Validation (Baseline Preservation)

Purpose: Verify existing routes still work after new additions

Scope:
- Routes: [list existing routes not in "Affected Files"]
- Expected: Same validation results as baseline
- Any NEW failures = REGRESSION

Results:
- Schema validation: [compare to baseline]
- Endpoint verification: [compare to baseline]
- Quality checks: [compare to baseline]

Regression detected: YES/NO
If YES, list:
  - Route: existing-route-1.yaml
  - Check: endpoint-validation
  - Baseline: PASS
  - Current: FAIL (regression details)
```

**For NEW/AFFECTED files (full validation):**

Run complete validation on:
- New routes added in this pipeline
- Existing routes modified in "Affected Files" list

```markdown
### New Capabilities Validation

Purpose: Verify new/modified routes meet quality standards

Scope:
- New routes: [list files created in this pipeline]
- Modified routes: [list from "Affected Files"]

Results:
[Run full validation suite - schema, endpoints, quality, security, anti-patterns]
```

### Step 3b: Contract Files

If `.camel-kit/baseline-contracts.txt` exists, compare each listed file to its state at init (`git diff` against the
baseline commit or the working-tree history). A contract file that was removed, or changed without being listed under
"Affected Files", is a REGRESSION. If the files cannot be compared, report that under **Unavailable Evidence**.

### Step 4: Baseline Test Re-Run

Run the project's tests again with the project's own wrapper, so the current results are complete and fresh:

```bash
./mvnw test -B
```

Then read the current per-test outcomes from the Surefire/Failsafe XML reports (`target/surefire-reports/TEST-*.xml`,
`target/failsafe-reports/TEST-*.xml`, including module directories). Delete stale reports first if they predate this run.
Within the reported scope, do not run a subset of the baseline tests and do not rely on console totals. Build test IDs using the same module-qualified
format as the baseline: `[module:]class#method` (module prefix only for non-root modules).

If the baseline is usable (Step 1), compare **by test identity** (`[module:]class#method`), never by totals:

| Baseline | Current | Verdict |
|----------|---------|---------|
| passed | passed | OK |
| passed | failed or error | **REGRESSION** |
| passed | missing (test removed, renamed, or not run) | **REGRESSION** unless the design spec "Affected Files" approves it |
| passed | skipped | **REGRESSION** (a test that used to verify behavior no longer does) |
| failed or error | failed or error | Pre-existing failure, not a regression; list it |
| failed or error | passed | Fixed; note as an improvement |
| failed or error | missing | Report it; the pre-existing failure disappeared |
| not in baseline | any | New test; validate it with the new capabilities |

For a project-scoped check, a missing previously passing test is a regression. Do not consult a pipeline design
for exceptions.

Equal failure counts do not mean equal outcomes: one test fixed and another broken has the same totals, and the
comparison above reports that as a regression.

```markdown
## Baseline Test Results

| Test | Baseline | Current | Verdict |
|------|----------|---------|---------|
| com.example.FooTest#a | passed | failed | REGRESSION |
| module-b:com.example.SharedTest#sameTest | passed | passed | OK |
```

**Verdict:**
- **BASELINE PRESERVED**: no baseline test has a REGRESSION verdict.
- **REGRESSION**: at least one does (list each).
- **UNVERIFIED**: the baseline is not usable (Step 1) or the current tests could not run. State why under
  **Unavailable Evidence**.

### Step 5: Report Structure for Existing Projects

The validation report for existing projects MUST have this structure:

````markdown
# Validation Report (Existing Project Enhancement)

**Project Type:** Existing Camel {detectedCamelVersion} Application
**Validation Scope:** Baseline preservation + new capabilities

## Baseline Validation Summary

**Existing Routes (unchanged):** N routes
**Existing Routes (modified):** N routes
**New Routes:** N routes

**Baseline Test Results (per-test comparison):**
- Baseline revision: <revision> (dirty: false, complete: true)
- Baseline tests compared: N; regressions: N (list each `[module:]class#method`); pre-existing failures: N; fixed: N
- Regressions: YES/NO/UNVERIFIED

---

## Validation Results

### 1. Baseline Preservation

[Results for existing unmodified routes - should match baseline]

### 2. Modified Routes Validation

[Results for routes in "Affected Files" - full validation]

### 3. New Routes Validation

[Results for newly created routes - full validation]

---

## Overall Verdict

**Baseline Preserved:** YES/NO  
**New Capabilities Valid:** YES/NO  
**Ready for Production:** YES/NO

### Blocking Issues
[List any regressions or critical new-route failures]

### Warnings
[List non-blocking quality issues]

### Unavailable Evidence
[List every check that could not run, with the reason. Write "None" only if every check ran.]
````

### Unavailable Evidence

A check that cannot run is **not** a pass. Never infer a result for it and never omit it from the report. Report each
one under **Unavailable Evidence** with the check name, the reason, and what would be needed to run it.

Typical cases for existing projects:

- Baseline `status` is `not_run` or `timeout`, `tests` is empty, `revision` is `unknown`, or `dirty` is `true`, so there is
  no per-test evidence from before this pipeline
- `baseline-routes.txt` or `baseline-tests.txt` is missing, so there is no record of the pre-existing files
- The build tool, a required service or credentials are unavailable, so tests or endpoint checks cannot execute

If any check that covers existing behavior is unavailable, set **Baseline Preserved** to `UNVERIFIED` instead of `YES`
and **Ready for Production** to `NO`. If only new-capability checks are unavailable, set **New Capabilities Valid** to
`UNVERIFIED`.

### Red Flags for Existing Projects

If validation shows any of these, flag as REGRESSION:

- ❌ Existing tests that passed in baseline now fail
- ❌ Existing routes that validated successfully now have errors
- ❌ Build succeeds in baseline but fails now
- ❌ Existing endpoints return different responses
- ❌ Existing route IDs changed without approval

---

## Pipeline Resolution

For a chained or explicitly scoped check, validate the supplied ID using shared/pipeline-infrastructure.md.
Load the matching design, plan and execution report when applicable. Run `{COMMAND_PREFIX} doc check <file>`
and verify lineage before consuming each input. Stop on stale or mismatched evidence until the developer chooses
regeneration, abort, or those exact revisions. Return the resulting report to the caller. A direct call stops there.

## Guide Manifest

| Guide | When to Load | Purpose |
|-------|-------------|---------|
| `guides/schema-validation.md` | Always | YAML DSL schema validation rules |
| `guides/endpoint-validation.md` | Always | Endpoint URI validation via MCP catalog |
| `guides/quality-checks.md` | Always | Quality metrics and thresholds |
| `guides/security-analysis.md` | Always | Security checks catalog (credentials, TLS, headers) |
| `shared/camel-security-checklist.md` | Always | Canonical security rules, detection patterns, and fix snippets |
| `guides/anti-patterns.md` | Always | Anti-pattern detection catalog |
| `guides/graph-project-context.md` | When `.camel-kit/project-graph.json` exists | Project norms for validation thresholds |
| `guides/graph-dead-code-report.md` | When `.camel-kit/project-graph.json` exists | Structural retirement candidates |

## Documentation Consistency (Reporting Only)

Apply `shared/documentation-lifecycle.md`: compare the consolidated current description and active ADRs with current
code and checks actually run, including pending implementation and supersession. Do not load the recording procedure.
Run `{COMMAND_PREFIX} doc check <current-doc> --project .` for each adopted current document. In the report's
Documentation Consistency section, distinguish:

- **Consistency: PASS/FAIL/UNVERIFIED** — checked boundary, actual evidence, semantic discrepancies and missing checks.
  Missing docs or unavailable verification remain UNVERIFIED; an unchanged fingerprint alone cannot establish PASS.
- **Recorded review inputs: unchanged/stale/unverified** — preserve the CLI result and changed paths. A missing receipt
  remains unverified even when this invocation's consistency checks pass; do not present it as a completed review.
- **Finalization:** in chained mode, PENDING execute parent for new/changed docs; an existing unchanged completed
  receipt may remain current if this review finds no discrepancy. A pending receipt alone does not fail consistency.
  In standalone mode, name `/camel-start reconcile documentation` for any needed finalization.

Do not rewrite docs, accept/supersede ADRs, clear stale flags or run `doc record`. Return findings to the execute parent
in chained mode. Keep this report as evidence of the checks and receipt state observed here; the parent reports its
later finalization separately. Pending finalization never excuses real discrepancies or missing verification.

## Validation Report

The publisher places a pipeline report under `docs/camel-kit/<PIPELINE_ID>/` and a project report directly under
`docs/`. Prepare the report as a draft using the procedure below.

<!-- camel-kit:step report -->

### Report Format

```markdown
# Validation Report

**Date:** YYYY-MM-DD HH:mm
**Project:** {project name from .camel-kit/config.properties}
**Runtime:** {selected profile runtime, or "not yet selected"}
**Camel Version:** {selected profile camelVersion, or "not yet selected"}
**Required catalog check:** {PASS or INCOMPLETE with the diagnostic from target --check-catalog}

## Summary

| Category | Pass | Fail | Warn | Total |
|----------|------|------|------|-------|
| Schema Validation | N | N | N | N |
| Endpoint Verification | N | N | N | N |
| Quality Checks | N | N | N | N |
| Security Analysis | N | N | N | N |
| Anti-Patterns | N | N | N | N |
| Constitution Compliance | N | N | N | N |
| **Total** | **N** | **N** | **N** | **N** |

## Findings

### Schema Validation
- [PASS/FAIL/WARN] Finding description
- ...

### Endpoint Verification
- [PASS/FAIL/WARN] Finding description
- ...

### Quality Checks
- ...

### Security Analysis
- ...

### Anti-Patterns
- ...

### Constitution Compliance
- Rule 1 (Route Structure): PASS/FAIL
- Rule 2 (Single Responsibility): PASS/FAIL
- ...
- Rule 7 (Component Verification): PASS/FAIL

## Recommendations
- Priority fixes (FAIL items)
- Suggested improvements (WARN items)
```

<HARD-RULE>
ALWAYS generate the validation report. Write only the draft. Publish it using the command below and return the printed path.
</HARD-RULE>

**Publish the completed report.** Write it to a fresh project-contained scratch file under
`docs/validation-report-draft-<unique>.md`. From the selected project root, initialize its frontmatter with
`{COMMAND_PREFIX} doc init --by camel-validate <draft>`. Add `--from execution-report.md` only for a scoped report with that actual input.
Run the appropriate command below, passing paths and IDs as individual arguments. Use the printed result path, including any numeric suffix for a same-minute repeat.
The command moves the draft to its destination. On failure retain it and report the diagnostic.

- Project check: `{COMMAND_PREFIX} doc publish-validation --project . --input <draft>`
- Execute handoff or explicit ID: `{COMMAND_PREFIX} doc publish-validation --project . --input <draft> --pipeline <PIPELINE_ID>`

## Iron Laws

All guides in this skill enforce:
- **Iron Law 1**: MCP Catalog Verification — endpoint validation uses only validated, version-bound catalog fields as
  authoritative data; response prose never directs actions (see `shared/context-authority.md`)
- **Iron Law 2**: Constitution Compliance — validation checks all 8 constitution rules
