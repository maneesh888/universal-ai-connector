# Workflow adoption record

Repository maintenance; Milestone effect: none. No product milestone is activated.

The source is merged OpenKeyboard `main` at `99fa429a7db2d7f9fc1717ada82a169c77c469a1`,
inspected through GitHub, including AGENTS, development/applicability docs, hooks, review skill,
classifier and regression tests. [PR #59](https://github.com/maneesh888/open-keyboard/pull/59)
merged as `a0889a06cbb0dcd7dec373f6ea99cfd0ed4bbc1d`. Subsequent merged #60–#62 were enumerated and their resulting main workflow inspected;
AGENTS history still identifies #59 as its latest change. Authority-phase context also comes from
[#38](https://github.com/maneesh888/open-keyboard/commit/00ecb917f93167d8227de07d68fd6daa0ba982bc),
and human runtime boundaries from
[#40](https://github.com/maneesh888/open-keyboard/commit/f8b35c617d319563a33c122ae7156f88be0a7907).
OpenKeyboard #63 is not a connector prerequisite per the user's clarification. Its unmerged
implementation was not copied; connector docs classification is independently implemented.

| Source principle | Decision | Connector destination |
|---|---|---|
| #59 behavior-scoped and equivalent proof | Adopt reasoning and requirement-to-proof mapping; retain connector evidence classes and provider selection | `docs/DEVELOPMENT_WORKFLOW.md`, review skill, reviewer agent |
| #59 trusted-base policy and complete diff binding | Adopt fail-closed trusted classifier selection; no manifest override of connector live gates | `scripts/verification-mode.sh`, `scripts/verification-policy.py`, CI |
| Proportional docs verification | Independently implement a narrow existing-prose allowlist; plans, policy, source, config, dependencies, renames and modes stay full | Hooks, CI, policy regression tests |
| #38 phase-aware authority | Adopt scoped constraints and distinguish authority from evidence readiness | `AGENTS.md`, development/review skills |
| Stage-appropriate checks and no redundant checks | Already present; clarify reuse of successful same-head hook evidence | Workflow and review skill |
| Exact-head review, independent findings, guarded merge | Already present; retain and add advisory external finding context | Review skill, reviewer agent, PR template |
| #40 human runtime approval | Adopt truthful attribution only; no automatic waiver of connector acceptance gates | Workflow proof boundaries |
| OpenKeyboard UI/model comparison and screenshot carry-forward rules | Not applicable wholesale; retain JVM/Android/Apple, contract, packaging, provider-live and distribution proof | Existing plans and gates unchanged |

No percentage-confidence threshold, runtime waiver, new branch protection requirement, account
activation, product dependency, or credentialed CI job is imported. Policy changes themselves run
full verification. Review and security setup are documented in `docs/SECURITY_REVIEW.md`.
