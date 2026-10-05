We are building KINA, which is a MCP and HTTP API for querying electronic components from different distributors: LCSC, TME, Mouser.

We will be using java and spring.
Security posture of the application web interface:
 * Development mode - NO authentication running with a admin fake user
 * Production mode - OAuth enabled, against a generic OIDC provider. No provider-specific code: the issuer URL, client id and client secret come from configuration and discovery is done via the issuer's `.well-known/openid-configuration`. Any compliant provider (Keycloak, Authentik, Google, Entra ID, ...) must work unchanged.

The web interface is used by users to provisions an access token with a validity of 30 days.

MCP authentication (decided):
 * Claude's remote MCP connector does not accept a static bearer token. It authenticates with OAuth (authorization code + PKCE, dynamic client registration, per the MCP authorization spec) or runs with no auth at all.
 * Therefore KINA must itself expose an OAuth 2.1 authorization-server surface for MCP clients:
   - `/.well-known/oauth-protected-resource` and `/.well-known/oauth-authorization-server` metadata
   - `/oauth/register` (dynamic client registration), `/oauth/authorize`, `/oauth/token`
   - `/oauth/authorize` delegates the actual login to the configured OIDC provider in production, and to the fake admin user in development mode.
   - The access tokens it issues ARE the 30-day KINA tokens (same table, same validity, same revocation). Refresh tokens are optional.
 * The web-interface-provisioned 30-day tokens remain usable as plain `Authorization: Bearer` for the HTTP API and for MCP clients that do support static headers (e.g. Claude Code `claude mcp add --header`).
 * The HTTPS termination in front of all of this is out of scope, but the server must honour `X-Forwarded-*` headers so the OAuth metadata and redirect URIs advertise the public HTTPS origin.

Main feature: the app acts as an mcp for Claude. The LLM can query the mcp to find a component (10uF X7R MLCC SMD) and the application will query the distributors to find suitable parts. The returned parts wilkl contain all the relevant details offered by the distributors. IF distributors give a photo link, it will be passed too.
Batch search of component must also be supported.
Pricing should be returned for at most the 3 smallest brackets.
The LLM can specify a max number of search results for each distributors. Default is 10.
The response should include how many results the distributor gave, even if not all were sent back to the LLM. 
The LLM can call the same tool again with more responses and they should be returned from cache (except when further querying is needed).
The LLM can request uncached request and that will not search the cache, but will populate the cache with results. 

We do not crrently have a KiCad api key, therefore we will use the technique used by github.com/Bouni/kicad-jlcpcb-tools.
 - download a "cache" DB of their components and answer from that. The cache shouls be downloaded again when the old cache is more than 5 days older OR this is the first startup and there is no cached DB.
 - JLCPCB is treated as LCSC (decided): the JLCPCB parts database IS the "LCSC" distributor. JLCPCB stock counts as LCSC stock, JLCPCB price brackets as LCSC prices, and the LCSC part number (Cxxxxx) is the distributor part number. No separate LCSC API call is made.
 - The downloaded JLCPCB database is used as-is (read-only SQLite file on a persistent volume), not imported into Postgres; only its download timestamp is tracked.

.env contains api keys for mouser and tme.eu

MCP is exposed over http.
The MCP should be able to be installed in Claude as a remote MCP (after being exposed to with a HTTPS upgrade proxy - outside the scope of this implementation!)


Database (decided): PostgreSQL. It holds everything KINA owns: the Mouser/TME component cache, users, 30-day access tokens, registered OAuth clients and authorization codes. Runs as a second service in docker compose; schema managed by migrations (Flyway).

All component data gotten from Mouser and TME should be cached in the Postgres DB locally, together with an access date. Anything younger than 5 days is considered up to date and fresh. 
Matching of the search algorithm is done by ranking the relevant parts using laya local decision model. 
https://github.com/NandhaKishorM/laya
Try to keep the ranking algorithm under 20 seconds. 

Laya: what it is and what it needs (investigated 2026-10-05, laya 0.3.27, Apache-2.0)
 * Laya is a non-autoregressive "System 1" decision model: a bidirectional encoder (ModernBERT-large 421M params, or mmBERT-base 322M for the multilingual checkpoint) plus a typed head. It does NOT generate text. It answers typed questions about one "state" (any text/JSON) in a single forward pass:
   - `choice`: pick one label from a described option set, with per-option probabilities
   - `score`: ordinal level on a described scale, returns expected level + distribution
   - `noul`: yes/no statement, returns calibrated P(true)
   All questions about the same state share one forward pass; many states share one batch.
 * Runtime: Python 3.10+ package `laya` (deps: torch >= 2.0, transformers, safetensors, huggingface_hub, numpy). Checkpoints are public on Hugging Face (no token needed) and are downloaded on first use into the HF cache:
   - `convaiinnovations/laya` (English, 843 MB safetensors, 512 token context)
   - `convaiinnovations/laya-multilingual` (644 MB + 34 MB tokenizer, 1024-8192 context, fastest on CPU)
   - `convaiinnovations/laya-typed-decisions` (843 MB, fine-tuned for typed-decision workflows)
   About 1.5 GB RAM per loaded checkpoint. Set `LAYA_MODELS` to load only one; `HF_HUB_OFFLINE=1` after the first download.
 * Hardware: CPU is fully supported (fp32, optional bf16 via `LAYA_CPU_AMP`). GPU: NVIDIA CUDA via PyTorch cu128 wheels + NVIDIA Container Toolkit (also MPS/XPU on bare metal, not in containers). Official Dockerfile with build arg `TORCH_INDEX=cpu|cu128|cu130`; `compose.yaml` + `compose.http.yaml` run the HTTP server, `compose.cuda.yaml` is the GPU overlay. CPU quickstart needs ~8 GB RAM and ~10 GB disk (torch image + weights).
 * HTTP server `laya-serve` (pip `laya[serve]`): 
   - `GET /health` (liveness + loaded checkpoints + real device + CPU-fallback counts)
   - `POST /v1/systemone` body `{state, questions, model?, lang?, max_len?, head_max_len?, min_confidence?}`
   - `POST /v1/systemone/batch` body `{states: [...], questions, batch_size?, sort_by_length?}` -> one result per state, same shape
   - Optional `LAYA_API_KEY` -> requires `Authorization: Bearer`; `LAYA_MAX_CONCURRENT` (default 16, excess gets 503 + Retry-After); `LAYA_PRELOAD=1` loads at startup; `LAYA_THREADS` must be <= physical cores (oversubscribing SMT siblings is a 10x regression); `LAYA_DEVICE=cpu|cuda`.
 * Measured latency (project benchmarks, fp32):
   - CPU, 4-core AMD EPYC: `laya`/`typed-decisions` ~600 ms per question, `multilingual` ~185 ms per question; roughly linear in question count; batching across states saves little on CPU.
   - GPU, Tesla T4: ~33 ms for 1 question, ~7 ms per additional question; batching is a large win.
   - Cold load 0.5-4.4 s per checkpoint.
 * Limits that shape the ranking design:
   - A `choice` question's options share a fixed token budget (`head_max_len` 192/256). Above ~20 options labels get truncated and become indistinguishable; the server hard-refuses >100 options. `predict_shortlist` (embedding pre-filter to top-k) exists only in the Python SDK / Laya MCP tools, not on the HTTP routes.
   - Shipped checkpoints are over-confident out of the box and the base checkpoints score near chance on zero-shot typed-decisions benchmarks; fine-tuning on domain data is where accuracy comes from. Zero-shot relevance scoring for parts must be validated on a small labelled set, and KINA must keep a deterministic fallback ranking.
   - One `Agent` is not safe for concurrent predict calls; the HTTP server serialises this internally.
 * JVM option `laya-java/` (in the same repo): pure-JVM inference, JDK 17+, one dependency `com.microsoft.onnxruntime:onnxruntime:1.20.0`. Needs a one-time Python export of the checkpoint to ONNX (`scripts/export_onnx.py --model <ckpt> --output laya.onnx`, optional `--quantize` INT8 for CPU; fused graph ~1.2 GB) plus the checkpoint's `rl_agent_config.json` and `tokenizer/`. Not on Maven Central (build from source, `./gradlew publishToMavenLocal`). Not implemented: Router, `predictLong`, shortlist, hooks. CPU-only as written (GPU would need the `onnxruntime_gpu` artifact plus a CUDA execution provider in `LayaSession`). `laya-java-client` (HTTP client module) is an empty stub.

Laya integration decision for KINA:
 * Run Laya as a sidecar container (`laya-serve`, official image) in docker compose and call it from Java over HTTP using the batch endpoint. CPU by default (`TORCH_INDEX=cpu`, `LAYA_DEVICE=cpu`, `LAYA_THREADS` = physical cores); GPU is an opt-in compose overlay (`compose.cuda.yaml`, `TORCH_INDEX=cu128`, `LAYA_DEVICE=cuda`) with no change to KINA code. This is the only route that satisfies "must support CPU, may support GPU" without modifying Laya.
 * Default checkpoint on CPU: `laya-multilingual` (3x faster per question than the English checkpoints; part descriptions are mostly symbols and English anyway). Make the checkpoint configurable (`LAYA_MODELS`) so `typed-decisions` or a fine-tuned checkpoint can be swapped in.
 * Ranking shape: one state per candidate part (query + normalised part attributes), questions = a `noul` "this part satisfies the request" plus a `score` on a relevance scale; use `/v1/systemone/batch` with `sort_by_length=true`. Do NOT put all candidates as options of a single `choice` question (token-budget limit above).
 * Staying under 20 s on CPU: pre-filter deterministically before Laya (parametric match on value/package/tolerance/voltage/dielectric parsed from the query, lexical match on description), cap the Laya candidate set (default 40 total across distributors, configurable), preload the checkpoint at startup, and give the Laya call a hard timeout (default 18 s). On timeout or Laya unavailability fall back to the deterministic ranking and flag `ranking: "fallback"` in the response. With a GPU the same code runs well under 1 s.
 * Put the ranker behind a `PartRanker` interface so an in-process `laya-java` + ONNX Runtime implementation can replace the sidecar later without touching search code.
 * Laya calls go only to the local sidecar; never send part data to a third-party inference service.
 * Concurrency towards Laya is configurable and defaults to 1: KINA holds a semaphore (`kina.ranking.laya.max-concurrent-requests`, default 1) in front of the sidecar so at most one ranking request is in flight at a time; further search requests queue for a Laya slot (bounded wait, counted inside the 20 s budget) and fall back to the deterministic ranking if no slot frees in time. The sidecar's own `LAYA_MAX_CONCURRENT` is set to the same value in compose so the two limits cannot drift apart. The default of 1 is deliberate: one forward pass already saturates the CPU cores given to Laya, and a second concurrent pass only slows both down and hogs the host.


All requests from distributors should contain available stock. Stock marked by distributor as "expected stock on XXX" is not considered available for our purposes.
Only "Ships now" stock is eligible. Out of stock options are NOT ranked and NOT cached and NOT returned. 


Work with Opus agents for coding tasks and Sonnet for documentation.
Use a maven project structure. This should be package-able as a docker container (docker compose with three services: kina, postgres, laya-serve).

