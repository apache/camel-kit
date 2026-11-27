---
name: camel-prototype
description: Use when trying a small runnable Camel route with sample input before choosing whether to start design.
user_invocable: false
---

# Try a Camel integration

Use shared/direct-project.md and shared/context-authority.md. On `continue` go directly to **Continue into design**.
For a new sample, ask for the input or desired result only if the request leaves it open. Use a new project-relative
`prototypes/<name>/` directory. Ask which sample to use if the name already exists. Verify each path is
contained in that directory and never overwrite existing work. Leave manual and Ship state untouched.
Creating a sample writes its files. Start a service, route or test only when the developer asks to run it.

1. Choose the smallest route that shows the behavior. For a file copy, copy the installed
   `examples/copy.camel.yaml.template` to `copy.camel.yaml`, `examples/order.txt` to `inbox/order.txt`,
   and `examples/README.md.template` to `README.md`.
   The file endpoints need no service. From that directory the one run command is `camel run copy.camel.yaml`.
   On a requested run, verify the copy by comparing `outbox/order.txt` to the input, then stop the process you started.
2. For another example, write the route, a concrete sample input, the expected observable output, and the
   minimum local service configuration it needs. Check endpoint options against the selected Camel catalog.
   Give one reproducible run command from the sample directory, with prerequisites and cleanup in its README.
   If a required service cannot run locally and no test endpoint is configured, stop and ask **local stub or
   sandbox endpoint?** Record the choice and use only the selected test service.
   Include transactions or observability only on request.
3. Record `not run` by default.
   After a requested run, record the actual command used, exit or stop status, observed output or failure and
   any remaining setup. Report the sample paths and run command and stop.

## Continue into design

Keep the handoff in the sample's `README.md` alongside the files. Record the goal, relative paths to the route,
input and local setup, service choices (redact credentials), expected result and observed run status. For an
observed run, include the hashes of the route, input and setup that produced it. Refresh the record after each run. An example run, including a success, never starts a pipeline.

Only when the developer asks to take this prototype into design, or explicitly invokes `/camel-prototype continue`,
read that README and recheck the listed files against the selected directory. If several samples exist ask
which one. If the note or a file is missing or has changed, identify it and confirm the current input.
Label observations for earlier file contents as historical.
Pass those facts as loaded data to camel-brainstorm/SKILL.md in the current conversation. Keep expected and
observed behavior distinct. Brainstorm owns the design interview, pipeline selection and approval gate.
