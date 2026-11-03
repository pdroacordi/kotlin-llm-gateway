---
name: ai-eval
description: Measure the AI-quality parts of agent-gateway against labelled datasets - semantic cache threshold (false hits vs hit rate), guardrail classifiers (PII, prompt injection), routing policies (cost vs quality), agent task success - and report a before/after metrics table. Use whenever a change touches a similarity threshold, an embedding model, a classifier, a routing or downgrade policy, or an agent prompt, and when the user says "eval", "calibra o limiar", "mede o cache", "avalia o guardrail".
---

# ai-eval

A threshold or classifier change without numbers is a guess. This skill turns it into a table. Datasets and the runner live in `evals/` (built by CACHE-03 and GRD-03); until then, say the eval is not possible and why.

## Ground rules

- **Fixed splits.** Every dataset has `dev` and `test` splits, fixed by a seed and committed. Tune on `dev` only; report `test` once per change. Tuning on `test` invalidates the number.
- **The costly error first.** For the semantic cache the metric that matters is the **false-hit rate** (serving an answer to a different question), then hit rate. For guardrails it is recall on the blocked class at a stated false-positive rate. Say which error the change trades for which.
- **No paid calls without the user's go-ahead.** Embeddings and judge models default to the local models the stack provides. A run against a paid API is announced with its estimated cost first.
- **Same conditions before and after:** same dataset version, same model versions, same seed. Record them.
- **Clean-room:** datasets are synthetic or public with their licence noted; no data copied from any employer.

## Steps

1. **Baseline:** run the suite on the current `HEAD` before the change (`./gradlew :evals:run --args="--suite <suite> --split dev"`); save the JSON output to the scratchpad.
2. **Change:** apply the candidate (threshold, model, prompt, policy). For a threshold, sweep a range on `dev` and show the trade-off as a table, not a single point.
3. **Pick** the operating point by the rule the spec states (for example "highest hit rate with false-hit rate at most 0.5%"). If the spec has no rule, write one as a local ADR first.
4. **Test split:** run once at the chosen point.
5. **Report** in the spec log and in `docs/knowledge/runs/YYYY-MM-DD-eval-<suite>.md`:

   | Metric | Before | After | Delta |
   |---|---|---|---|
   | false-hit rate (test) | | | |
   | hit rate (test) | | | |
   | p95 added latency | | | |

   Plus: dataset version and size per class, model versions, seed, the sweep table, and the examples the new point gets wrong (a handful, not a dump).
6. A metric that regresses beyond the spec's tolerance goes back to the implementer; it is not merged with a note.

## Suites (target)

| Suite | Measures | Built by |
|---|---|---|
| `semantic-cache` | false-hit rate, hit rate, latency vs threshold, per embedding model | CACHE-03 |
| `pii` | precision and recall per entity (CPF, CNPJ, e-mail, phone, card) on input and output | GRD-02 |
| `injection` | recall on attacks at a fixed false-positive rate on benign prompts | GRD-03 |
| `routing` | cost per task vs judged quality for each policy | RTE-03 |
| `agents` | task success rate, steps and cost per task | AGT-04 |
