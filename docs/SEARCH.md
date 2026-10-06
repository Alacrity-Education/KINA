# Search behaviour reference

Details of how KINA reads a request, what it returns and how it ranks. The overview is in the [README](../README.md); the binding contracts are in [DESIGN.md](DESIGN.md) and the wire format is in [API.md](API.md).

## Search features in detail

- MCP over Streamable HTTP (stateless) at `/mcp`, plus a REST API under `/api/v1`.
- Three distributors: LCSC (JLCPCB database), TME, Mouser. Each one fails on its own; a broken distributor never fails the whole search.
- Only stock that ships now is returned. Out-of-stock, on-order and factory-stock offers are never ranked, cached or returned.
- Prices are trimmed to the 3 smallest quantity brackets.
- `max_results` is per distributor (1 to 50, default 10). Every distributor entry also reports how many matches the distributor found, how many KINA holds, and how many it returned.
- Cache of 5 days for TME and Mouser. Ask again with a larger `max_results` and the answer comes from the cache; KINA only calls the distributor when the cache holds too few parts.
- `bypass_cache` skips the cache lookup and still refreshes the cache.
- Ratings are minimums: `25V` also accepts 35 V and 50 V parts (an equal rating ranks first), `6A` accepts 8 A. Ratings are never put into the Mouser and TME keyword phrases; LCSC checks them in its database (`>=25V`). For inductors `6A` is the rated current, `Isat 8A` the saturation current; `DCR < 20mOhm` is a maximum and `low DCR` a preference.
- Relaxation ladder: when Mouser or TME find no in-stock part, KINA retries without ratings, then without the tolerance, then with the minimal core (for example `MLCC 22uF X7R 1206`), then without the dielectric (`MLCC 22uF 1206`), and reports the phrase in `fallback_query` and the dropped constraints in `relaxed`. Each part lists what it does not satisfy in `mismatches` (`"dielectric: X5R instead of X7R"`); `exact_matches` counts the parts that satisfy everything. While every match is out of stock KINA reads more pages; `out_of_stock_matches` reports matches that exist but do not ship now.
- Strict mounting and technology: a part whose known mounting (SMD/THT) or technology contradicts the request is left out (`excluded_by_constraints`); a part that does not state it ranks below known matches. `polymer aluminium` excludes tantalum polymer; plain `polymer` accepts both.
- `quantity` (pieces to order): parts that cannot supply it rank lower, and each part gets `ordered_quantity` (raised to the minimum order quantity and multiple), `unit_price_at_quantity` and `total_price`.
- Every part has `availability` (`in_stock`, `limited`, `last_units`, `supply_constrained`, `special_order`, `external_warehouse` with a plain note) and `lifecycle` (`active`, `last_time_buy`, `supply_constrained`, `new`).
- `detail`: `compact` (default) returns identity, stock, prices, availability, links and canonical attributes; `full` adds the category, photo, raw distributor attributes and extra fields.
- Distributor phrasing: when KINA rewrites a request for a distributor (ratings left out, connector wording), `distributor_query` shows the phrase it sent. It is null when your text went through as written. The cache key stays your own query text.
- Connector-aware search. Describe a connector in plain words ("90 degree dupont style female pin header, THT, 6 position") and KINA extracts the type, gender, positions, rows, pitch, orientation and mounting, then rewrites the request into each distributor's own vocabulary (`distributor_query`). Accepted wording:
  - Types: pin header (male), female header, socket or receptacle, box or shrouded header, terminal block or screw terminal, JST series XH, PH, GH, SH and ZH, USB-C, micro USB, FPC or FFC, RJ45, D-sub, barrel jack, or just "connector".
  - Positions: `6-position`, `6 pos`, `6 pin`, `6P`, `6 way`, `PIN: 6`.
  - Rows: `1x6`, `2x3`, "single row", "dual row".
  - Pitch: `2.54mm`, `0.1"`, `1.27mm`. The word `dupont` implies a 2.54 mm header.
  - Orientation: "right angle", `90°`, "angled", "horizontal" versus "vertical" or "straight". Mounting: `THT` or `SMD`.
  - IC packages such as `SOIC-8`, `LQFP-48` and `SOT-23-6` are not read as connector positions.
- USB connector precision. USB requests are read in more detail than other connectors:
  - Type: Type-C or USB-C, Micro-B, Micro-AB, Mini-B, Type-A, Type-B, "USB 3.0 Micro-B", "USB 3.0 Type-A".
  - Gender: receptacle, socket or female versus plug or male.
  - Standard, mapped to one speed class: USB 2.0 is 480 Mbps. USB 3.0, USB 3.1 Gen 1 and USB 3.2 Gen 1 are all 5 Gbps. USB 3.1 Gen 2 and USB 3.2 Gen 2 are 10 Gbps. USB 3.2 Gen 2x2 is 20 Gbps. USB4 is 40 Gbps.
  - Type-C pin configuration: 6P (power only), 12P, 14P, 16P (USB 2.0) and 24P (full featured).
  - Mounting style: SMD, THT, hybrid, mid-mount (also "recessed"), top-mount. Also orientation.
  - Features: power only, PD, waterproof or IPX7, board lock.
  - Examples: `USB-C receptacle 16 pin SMD USB 2.0`, `USB Type-C 24 pin USB 3.1 receptacle horizontal`, `micro USB B receptacle 5 pin SMD`, `USB-C 6 pin power only`, `waterproof USB-C receptacle IP67`, `mid-mount USB-C 16P`.
  - Pin counts are normalised. Some distributors count 1 or 2 shell or mounting pins, so a 16-pin connector can be listed as 17P or 18P. KINA keeps the reported `Positions` and adds the canonical `PinConfiguration`. Ranking compares configurations, so a 17P listing fully matches a 16-pin request, and a "17 pin" request finds 16-pin parts.
- Batch search of up to 20 queries in one call.
- Graceful rate limits: when Mouser or TME answer with a rate limit, KINA waits and retries instead of failing at once, for up to 2 minutes per request (`kina.search.max-request-duration`). `rate_limit_waited_ms` in each distributor entry says how long it waited.
- Ranking: deterministic parametric ranker blended 50/50 by rank with an in-process cross-encoder (ONNX Runtime, CPU, no extra container, nothing leaves the host), with a 5 s budget per query and an automatic fallback (`ranking: "fallback"`). Search never fails because of the model.
- OAuth 2.1 authorization server for Claude's remote connector (dynamic client registration, PKCE, Client ID Metadata Documents, consent page). Login is delegated to your organisation's OIDC provider.
- Access by group: in `prod`, only members of the configured groups can sign in. KINA checks again on every refresh and on bearer requests, so a removed member is cut off within about an hour.
- Two security modes: `dev` (no login, fake admin) and `prod` (OIDC login, bearer tokens required).
- Provider-agnostic OIDC: only the issuer URL, client id and client secret are required, plus the group names. Authentik is the worked example in [docs/OPERATIONS.md](OPERATIONS.md#authentik-setup); Keycloak, Google, Entra ID and other compliant providers work unchanged.
- Static access tokens (30 days) for scripts and machines without a browser, created in the web UI. They can be switched off.

## MCP tools

| Tool | Parameters | Purpose |
|---|---|---|
| `search_parts` | `query` (required), `max_results` (1 to 50, default 10, per distributor), `distributors` (`LCSC`, `TME`, `MOUSER`; default all configured), `bypass_cache` (default false), `quantity` (default 1), `detail` (`compact` or `full`) | Search and rank in-stock parts. Can take up to 2 minutes when a distributor is rate limited. |
| `search_parts_batch` | `queries` (1 to 20 of `{query, max_results, quantity}`), `distributors`, `bypass_cache`, `detail` | Several searches in one call. Returns `{"results": [...]}` in request order. The whole batch shares one 2-minute limit for rate-limit waits. |
| `get_part` | `distributor`, `part_number`, `bypass_cache`, `quantity`, `detail` | One part by distributor part number (LCSC `C15850`, TME symbol, Mouser number) or by MPN (hyphens and spaces ignored: `HCMA0703 2R2 R` finds `HCMA0703-2R2-R`). Returns `found: false` with `reason` `not_found` or `out_of_stock` (then `identity` names the listed part). Can take up to 2 minutes when the distributor is rate limited. |
| `list_distributors` | none | State of each distributor, cache statistics and ranking status (mode, model, readiness, latency, last error). Never calls the Mouser or TME APIs. |
| `ping` | none | `{"status":"ok","version":"..."}`. |

Full schemas are in [docs/API.md](API.md).

### Example

Request (tool arguments of `search_parts`):

```json
{"query": "10uF X7R 0805", "max_results": 10, "distributors": ["MOUSER"]}
```

Response (abridged):

```json
{
  "query": "10uF X7R 0805",
  "parsed": {"family": "capacitor", "capacitance": "10uF", "dielectric": "X7R", "package": "0805", "keywords": []},
  "ranking": "blended",
  "ranking_note": null,
  "distributors": [
    {
      "distributor": "MOUSER",
      "total_results": 113,
      "fetched": 50,
      "returned": 10,
      "cache": "hit",
      "error": null,
      "rate_limit_waited_ms": 0,
      "relaxed": [],
      "exact_matches": 7,
      "excluded_by_constraints": 0,
      "out_of_stock_matches": 0,
      "parts": [
        {
          "rank": 1, "score": 0.93, "match": 1.0, "distributor": "MOUSER", "part_number": "603-CC0805MKX77BB106",
          "manufacturer": "YAGEO", "mpn": "CC0805MKX7R7BB106", "description": "...",
          "stock": 76689, "min_order_qty": 1, "order_multiple": 1,
          "prices": [
            {"qty": 1, "unit_price": 1.40, "currency": "EUR"},
            {"qty": 10, "unit_price": 0.853, "currency": "EUR"},
            {"qty": 50, "unit_price": 0.631, "currency": "EUR"}
          ],
          "availability": {"status": "in_stock", "note": "Ships now from stock."}, "lifecycle": "active",
          "datasheet_url": "...", "product_url": "...",
          "attributes": {"Capacitance": "10uF", "Voltage": "25V", "Dielectric": "X7R", "Package": "0805"}
        }
      ]
    }
  ]
}
```

`total_results` is what the distributor reported. `fetched` is how many in-stock parts KINA holds for the query. `returned` is `min(max_results, fetched)`.

### USB connectors: pin counts and standards

- **Pin-count normalisation.** KINA maps a reported count to the canonical configuration: 17 or 18 to 16, 25 or 26 to 24, 7 or 8 to 6. 14 stays 14, because it is a real USB 2.0 Type-C configuration. `Positions` stays as the distributor reported it. `ShieldPinsCounted` says how many extra pins were counted (1 or 2).
- **Inference.** If you name no pin count, USB 2.0 Type-C implies 16 pins and USB 3.x Type-C implies 24. `parsed.connector.pin_configuration_implied` is then `true`, and the pin signal counts half.
- **Physical consistency.** A Type-C part with 12 to 16 pins is treated as USB 2.0, and one with 2 to 6 pins as power only, whatever the distributor label says. JLCPCB labels many 16P and 6P parts "USB 3.1", although they cannot carry SuperSpeed.
- **Honest limit.** No distributor lists a 17P or 18P Type-C part in the data seen on 2026-10-05. The rule is covered by tests and by the "17 pin" request direction.
- **Data quality differs.** LCSC has description text only (mid-mount shows as "Recessed" or "Sink board", waterproofing only in part numbers). TME has the richest parameters. Mouser's search API returns no USB attributes, so everything comes from descriptions, and many Mouser descriptions give no pin count.

Example: `USB-C receptacle 17 pin` gives this `parsed.connector` (abridged):

```json
{"type": "usb-c", "gender": "female", "positions": 17,
 "usb_type": "Type-C", "pin_configuration": 16, "shield_pins_counted": 1}
```

Checked live on 2026-10-05: it returns 16-pin Type-C parts on all three distributors (TME `USB4145-03-0170-C`, Mouser `217182-0001` and `DX07S016JA3R1500`). Before, it returned power supplies, cables and circular connectors. `USB Type-C 24 pin USB 3.1 receptacle horizontal` now ranks exact-class 24P parts above USB4 ones.

### Example: a connector query

Request:

```json
{"query": "90 degree dupont style female pin header, THT, 6 position", "max_results": 3}
```

Response (abridged, one distributor entry shown in full):

```json
{
  "query": "90 degree dupont style female pin header, THT, 6 position",
  "parsed": {
    "family": "connector", "mounting": "THT", "keywords": [],
    "connector": {"type": "female header", "gender": "female", "positions": 6, "pitch": "2.54mm", "orientation": "right angle"}
  },
  "ranking": "blended",
  "distributors": [
    {"distributor": "LCSC", "distributor_query": "\"Female Header\" 6P \"Right Angle\" 2.54mm", "fallback_query": null,
     "parts": [{"rank": 1, "part_number": "C...", "mpn": "PM254-1-06-W-8.5",
                "attributes": {"ConnectorType": "female header", "Gender": "female", "Positions": "6", "Pitch": "2.54mm", "Orientation": "right angle"}}]},
    {"distributor": "TME", "distributor_query": "pin strips female 6 angled", "...": "..."},
    {"distributor": "MOUSER", "distributor_query": "female header 6 pos right angle", "...": "..."}
  ]
}
```

Checked on a local instance on 2026-10-05. The top results were LCSC `PM254-1-06-W-8.5`, `DW254W-11-06-85` and `X5511FR-06`; TME `ZL263-6SG` and `DS1002-01-1X06R13`; Mouser `PRT-12590`, `613006143121` and `M22-6540642R`.

Known limit: KINA does not send rows to Mouser, and Mouser keyword search is loose. A `2x3` request can therefore return single-row parts there. TME and LCSC handle rows.

## How ranking works

1. The query is parsed: component family, value, tolerance (`.1%` too), ratings (voltage, current, saturation current, power, temperature, lifetime; all minimums) and DCR (a maximum), dielectric, package, mounting, the technology of a resistor, capacitor or inductor (thin film, thick film, wirewound, tantalum, polymer, film, multilayer...), and leftover keywords.
2. A deterministic ranker scores every part from 0 to 1: primary value (0.30), package (0.20), dielectric (0.15), technology (0.15), ratings (0.10; a higher rating counts as a match, an equal one ranks a little higher), tolerance (0.10), mounting (0.05), family keyword (0.05), lexical match (0.10), and small tie-break bonuses for stock, price and the JLCPCB Basic/Preferred library. A mismatch on value, package, dielectric, technology, rating or tolerance is penalised by the same amount a match earns. The same signals give each part its `match` grade (0 to 1, 1.0 = every stated parameter matches), which is absolute while `score` is relative to the other candidates.
   Parts whose known mounting or technology contradicts the request are removed first; parts that do not state them, and parts with less stock than `quantity`, rank after the others. A `quantity` above 1, a large minimum order quantity and a `last_time_buy` or `supply_constrained` lifecycle lower the score.
   For USB requests see the weights in [docs/API.md](API.md#usb-connector-queries). For other connector requests the value feature is replaced by connector features: positions (0.30), gender (0.20), orientation (0.15), pitch (0.15, where 2.54 mm equals 0.1"), connector type (0.10) and mounting (0.05). A wrong row count costs 0.10. Multi-row parts cost 0.08 when you did not ask for rows. Attributes a part does not list never count against it.
3. The top 40 candidates (shared across distributors, at least 5 per distributor) go to the cross-encoder `cross-encoder/ms-marco-MiniLM-L6-v2`. It reads the query text and the part text (manufacturer, MPN, description, category, package, attributes) together and returns one relevance score per part. It runs inside the KINA JVM through ONNX Runtime on the CPU. The score is cached in memory for 1 hour.
4. Both orders are turned into ranks inside the candidate set, and the final score is `0.5 * deterministic rank + 0.5 * model rank`. Parts that were not sent to the model come after the scored ones. The response says `"ranking": "blended"`.
5. When the model cannot score, the deterministic order is used and the response says `"ranking": "fallback"` with a `ranking_note`: `cross-encoder disabled`, `cross-encoder model not loaded yet`, `cross-encoder timeout after 5s`, `cross-encoder timeout: budget exhausted`, `cross-encoder busy: no free slot within ...` or `cross-encoder failed: ...`. In a batch, queries reached after the 60 s ranking budget also fall back. Search never fails because of the model.

### Measured results

The study is in [docs/research/ranking-evaluation-2026-10-05.md](research/ranking-evaluation-2026-10-05.md). It uses 32 labelled queries (1259 candidates). Score is NDCG@10, higher is better. The dataset in `docs/research/data` has since grown to 41 queries and 1 619 candidates with 9 labelled USB connector queries; the numbers below are from the 32-query study.

| Ranking | NDCG@10 | Time per search |
|---|---|---|
| Deterministic ranker alone | 0.898 | under 5 ms |
| Blend with the cross-encoder, zero-shot (shipped default) | 0.913 | 130 to 300 ms (40 candidates, 4 threads, int8); 16 ms when the scores are cached |
| Blend with a fine-tuned cross-encoder | 0.918 | same |

The cross-encoder helps most on discrete parts, ICs and connectors, where the parser does not model words such as `RS-485` or `1x4P`. Passives and vague requests are not hurt.

### Model files

- Docker: the image build downloads the files from the pinned revision `233902d25c440f23af6f7d6e94d2946bac0bee0a`, checks them against `docker/model/ms-marco-MiniLM-L6-v2.sha256` (a mismatch fails the build) and stores them read only in `/opt/kina/cross-encoder` with a `model.json` (source, revision, hashes). The running container has no dependency on Hugging Face. `list_distributors` shows `model_revision` and `model_dir`.
- Build arguments: `CROSS_ENCODER_VARIANTS=int8` leaves out fp32 (about 90 MB smaller image); `CROSS_ENCODER_SOURCE` takes another HTTP(S) directory (a mirror or a fine-tuned model) with its own hash file (`CROSS_ENCODER_SHA256_FILE`) or `CROSS_ENCODER_SKIP_VERIFY=1`. See [docs/OPERATIONS.md](OPERATIONS.md#ranking-model).
- The default is the int8 file (about 23 MB). Set `KINA_CROSS_ENCODER_VARIANT=fp32` for the 91 MB file. It is slower and scored the same in the study.
- Local runs (`./mvnw spring-boot:run`) download the files on the first start, in the background, from `KINA_CROSS_ENCODER_MODEL_URL` into `./data/cross-encoder`, check size and SHA-256 and retry every hour on failure. `docker/model/fetch-model.sh <dir>` pre-fetches the pinned, verified files instead. KINA never waits for the model at startup.

### Fine-tuning

You can fine-tune the model on your own labelled parts. The script runs in a Docker image (`kina-ce-finetune:local`, built from `python:3.11-slim`) and takes about 4 to 5 minutes on 16 cores:

```bash
scripts/ranking/finetune_cross_encoder.sh                    # mode synth (default): synthetic labels only
scripts/ranking/finetune_cross_encoder.sh -m synth_real -o ./data/ce-synth-real
```

It writes a model directory in the Hugging Face layout plus `model.json`. Point `KINA_CROSS_ENCODER_MODEL_URL` at it (a local path is used in place), or bake it into the image with `--build-arg CROSS_ENCODER_SOURCE=...` (see [docs/DEVELOPMENT.md](DEVELOPMENT.md)). Details are in `scripts/ranking/README.md`.

Check a model before you ship it. The evaluation test runs when `KINA_CROSS_ENCODER_TEST_MODEL_DIR` is set, and it asserts a blended NDCG@10 of at least 0.90 on `docs/research/data/ranking-eval.jsonl`:

```bash
KINA_CROSS_ENCODER_TEST_MODEL_DIR=$PWD/data/cross-encoder-finetuned ./mvnw test -Dtest=CrossEncoderEvaluationTest
```

Part data is never sent to a third-party service for ranking.
