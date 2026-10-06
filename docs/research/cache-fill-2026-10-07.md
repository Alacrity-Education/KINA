# Passive cache fill for KINA (research, 2026-10-07)

Questions from the product owner:

1. How many components can KINA fetch per Mouser call and per TME call, with which endpoints, and under which rate and daily limits?
2. Is it worth making calls in the background (known value/package combinations, or category enumeration) to fill KINA's own database with component metadata?

Context: a new cache model is being built. Component properties and metadata are kept forever. Only stock and price expire. Search lists are cached for 3 days. Stale stock and prices are refreshed with cheap batch calls.

This is research only. No code was changed. All sources were read on 2026-10-07. Facts marked **verified** were checked live today or read in a primary source. Facts marked **inference** are my estimates.

Live calls made for this report: 23 TME calls (including 2 token calls) and 5 Mouser calls (shared daily quota).

## 1. Summary

- **TME is the distributor to fill from. Mouser is not.**
- TME can enumerate whole categories without a search phrase, 100 parts per call, with no page cap (verified). It also lists up to 50 000 product symbols per call (verified). Detail endpoints take 50 symbols per call.
- TME's whole catalogue holds 1 717 632 products. 462 921 are in stock (verified, country RO). The electronics part of the tree (semiconductors, passives, connectors, optoelectronics and similar) holds about 310 000 in-stock products.
- Filling all 310 000 in-stock electronics products with metadata, parameters and datasheets takes about 15 500 TME calls. At a self-imposed 1 call per second, that is 4.3 hours of calls, or 3 nights at 5 000 calls a night.
- TME publishes no daily quota. The legacy terms state "5 per second". The current terms (2026-07-01) move limits into the documentation, and the public v2 documentation states none. KINA production has already seen 2 HTTP 429 answers from TME.
- TME's current terms do not forbid storing data. They give a licence to display API content on the user's own website, without derivative works, and they require the notice "Data powered by TME.eu Data - no guarantee of data accuracy". KINA does not show this notice today. All data must be deleted if TME ends API access.
- **Mouser's API terms forbid caching, recording, pre-fetching or storing any part of Mouser content, and forbid bulk downloads** (search-engine excerpts of mouser.com/en/apiterms; the page itself is behind bot protection). That rules out a Mouser crawler. It also puts the planned "metadata forever" model, and arguably today's 5-day cache, in question for Mouser.
- Mouser paging has a hidden cap: `startingRecord` 1001 works, but 5001 and 10001 silently return the first page again (verified). Mouser has no category browsing endpoint.
- Of 500 random in-stock LCSC part numbers, 125 (25 %) exist at TME by exact MPN (verified, 10 calls). So the TME fill also gives structured parameters to about a quarter of LCSC parts, through a local MPN join that costs no calls.
- **Recommendation:** build a TME-only, off-by-default category filler (option b), plus a local MPN mapping (option d, done locally). Do not build Mouser sweeps or generated query combinations. Write to TME and to Mouser first (section 6).

## 2. Distributor facts

### 2.1 Mouser Search API

Base `https://api.mouser.com/api/v1` (v2 for the manufacturer endpoints). The API key goes in the query string.

| Endpoint | Per call | Paging | Notes | Status |
|---|---|---|---|---|
| `POST /search/keyword` | max 50 records (`records`) | `startingRecord`, 1-based in practice | `searchOptions`: `None`, `Rohs`, `InStock`, `RohsAndInStock` (one at a time; also 1, 2, 4, 8). `searchWithYourSignUpLanguage`, `mouserPaysCustomsAndDuties` optional | verified (swagger and live) |
| `POST /search/partnumber` | max 10 part numbers joined by `\|`, each 3 to 40 characters; max 50 parts returned | none | `partSearchOptions`: `None` or `Exact` | verified (swagger and live) |
| `POST /v2/search/keywordandmanufacturer` | max 50 | `pageNumber` (1-based) | filter by `manufacturerName` | swagger only |
| `POST /v2/search/partnumberandmanufacturer` | 10 part numbers, max 50 parts | none | filter by manufacturer name | swagger only |
| `GET /v2/search/manufacturerlist` | all manufacturer names | none | the only "browse" endpoint; there is no category endpoint | swagger only |

Live findings (2026-10-07):

- **Paging cap.** Keyword `thick film resistor 0603`, `InStock`: `NumberOfResult` 13 634. `startingRecord` 1001 returned new parts (`71-CRCW0603-5.76` ...). `startingRecord` 5001 and 10001 returned exactly the first page again, with no error. So the reachable depth is somewhere between about 1 050 and 5 000 records per query. The exact cap is unknown. A crawler would not notice it without comparing pages.
- **Part number batch with MPNs.** Ten manufacturer part numbers (not Mouser numbers) in one `Exact` call worked. Mouser matched 9 of 10 (`AMS1117-3.3` is not sold there). `Exact` still expands a short MPN: `BSS138` returned 21 variants (`BSS138-7-F`, `BSS138W-7`, ...). The call returned 33 parts. A batch can therefore hit the 50-part cap and lose matches.
- **No structured parameters.** `ProductAttributes` held a median of 4 entries, all packaging (`Packaging`, `Standard Pack Qty`). Mouser gives category, description, MPN, manufacturer, datasheet URL, lifecycle, RoHS, image and product URL. It gives no electrical parameters. About 2.5 KB of raw JSON per part.
- No rate-limit headers in the responses.

Limits:

| Limit | Value | Source | Status |
|---|---|---|---|
| Results per call | 50 | swagger, mouser.com/en/api-search | verified |
| Calls per minute | 30 | mouser.com/en/api-search (search-engine excerpt), `docs/OPERATIONS.md` | documented, not tested |
| Calls per day | 1 000 | same | documented, not tested |
| Daily reset time | unknown | none | open |

Terms of use (https://www.mouser.com/en/apiterms/). The page blocks automated readers (HTTP 403, CAPTCHA). The clauses below come from search-engine excerpts of that page, read 2026-10-07. They must be confirmed in a browser.

- Users may not "cache, record, pre-fetch, or otherwise store any portion of the Mouser Electronics Content", or attempt "bulk download" operations.
- The licence is "to copy and display the Mouser Electronics Data solely on your application".
- Not permitted: any use "competitive to or inconsistent with Mouser Electronics' own products and services", use that "aggregates, in any way, any Mouser Electronics Content with third party content (without distinction)", and use that "fails to attribute the Mouser Electronics Data appropriately to Mouser Electronics".
- Purpose: present data to end users "in ways that compliment and enhance Mouser Electronics's own products and services".

What I could not confirm: whether the full text allows short-term caching (some API terms allow a few hours). If it does not, today's 5-day `cached_parts` for Mouser is already outside the terms.

### 2.2 TME API v2

Base `https://api.tme.eu`. OAuth client credentials. Access tokens live 300 s. Sources: the vendored OpenAPI (`docs/vendor/tme-api-v2-openapi.json`), https://developers.tme.eu/en/api-doc/v2 and live calls.

| Endpoint | Per call | Paging | What it gives | Status |
|---|---|---|---|---|
| `GET /products/search` | `limit` 1 to 100 (default 20) | `page`, 1-based, no cap seen | `scope[]`: `products`, `parameters` (facets with value ids and counts), `counters` (count, pages, per-category counts). `phrase` (2 to 40 chars) **or** `category_id` or both. Filters: `filter[in_stock]`, `parameters[n][id]` + `parameters[n][values][]`, `manufacturer_id`, sort | verified |
| `GET /products/symbols` | 50 000 symbols (default `paginate[limit]`) | `paginate[page]` | all active symbols of a category or manufacturer; no stock filter | verified |
| `GET /products` | 50 `symbols[]` **or** 50 `mpns[]` | none | description, MPNs, manufacturer, category, MOQ, packing, photos | verified |
| `GET /products/data` | 50 symbols | none | `prices`, `stock`; `delivery` needs `amounts[]` | verified |
| `GET /products/parameters` | 50 symbols | none | structured parameters with ids | verified |
| `GET /products/files` | 50 symbols | none | datasheets and other documents | documented (used by KINA) |
| `GET /products/categories/tree` | whole tree | none | 1 738 categories, 1 420 leaves, product counts | verified |
| `GET /products/manufacturers` | all, by category | none | manufacturer ids and item counts | documented |

Live findings (2026-10-07, country RO):

- **Category-only search works.** `category_id=100300` (SMD resistors), `filter[in_stock]=true`, `limit=100`: count 23 245, 233 pages. Page 233 held 45 products. Page 234 returned an empty list. No depth cap. About 430 to 600 ms per page.
- **Parameter filters split a category.** Adding case 0603 (`parameters[0][id]=2932`, value 1620227) and tolerance 1 % (`parameters[1][id]=39`, value 1819327) gave 2 630 parts in 27 pages. A `counters`-only call is 240 bytes, a cheap way to size a slice.
- **Facets.** `scope[]=parameters` on SMD resistors returned 30 facets, for example Resistance with 978 values, Case with 26 values, Tolerance with 12 values.
- **Search rows are rich.** Each product row has symbol, MPNs, manufacturer, category, MOQ, multiples, packing, photo and a description that already holds the key values (`Resistor: thick film; 10kΩ; SMD; 0603; 0.1W; ±1%; 50V; -55÷155°C`). About 3 KB of raw JSON per product.
- **Symbol listing.** `/products/symbols?category_id=100300` returned 50 000 symbols in one call (870 KB, 0.9 s), 4 pages for the 171 143 SMD resistors. The whole catalogue is about 35 such calls.
- **Batches of 50.** `/products/parameters` for 50 symbols: 146 ms, median 15 parameters per part, about 420 bytes as compact JSON. `/products/data`: 570 ms. `/products?mpns[]` with 50 MPNs: 214 ms and 70 products (several TME symbols per MPN).
- **Catalogue size.** 1 717 632 products in the tree. 462 921 in stock. The in-stock search can include products with status `EXTERNAL_WAREHOUSE`; KINA should keep deciding stock from `/products/data`.
- No rate-limit headers in any response. KINA's own metrics show 2 rate-limited TME responses and 4 waits so far (`metrics_counters`).

In-stock counts by top-level category (verified, one `counters` call):

| Category | In stock | All |
|---|---:|---:|
| Connectors | 103 454 | 288 849 |
| Passives | 75 327 | 386 632 |
| Wires and Cables | 51 883 | 129 941 |
| Semiconductors | 45 357 | 265 943 |
| Workplace Equipment | 44 099 | 153 114 |
| Automation | 22 214 | 93 890 |
| Mechanical components | 19 590 | 65 686 |
| Optoelectronics | 19 054 | 47 764 |
| Switches and Indicators | 17 689 | 48 365 |
| Power Sources | 16 937 | 52 104 |
| Fuses and Circuit Breakers | 11 900 | 53 277 |
| Relays and Contactors | 10 587 | 37 832 |
| others (12 categories) | 24 830 | 94 235 |
| **Total** | **462 921** | **1 717 632** |

Largest in-stock leaves: SMD resistors 23 245, automotive connectors 16 881, MLCC SMD 10 465, inductors 7 567, board-to-board connectors 6 922. Only 105 leaves have more than 1 000 in-stock products. 640 have more than 100.

Limits:

| Limit | Value | Source | Status |
|---|---|---|---|
| Requests per second | "Standard limit of enquiries to API is 5 per second" (clause 3.4); may change with load; may be set per token | Terms 2013-02-15, amended 2025-03-01 (https://developers.tme.eu/pdfs/en/terms_2013-02-15.pdf) | verified text, legacy version |
| Current limits | "detailed information on applicable request limits" is in the Documentation (definition 6), also "under the 'User Panel' tab" | Terms 2026-07-01 (https://developers.tme.eu/pdfs/en/terms_2026-07-01.pdf) | verified text; the public v2 documentation names no number |
| Daily quota | none published | none | open |
| Symbols per detail call | 50 | OpenAPI | verified |
| Search page size | 100 | OpenAPI, live (101 fails) | verified |

Terms of service, version 2026-07-01 (verified text):

- 3.1(b): the service lets the user "retrieve product data ... including prices and stock levels". 3.5: free of charge.
- 3.2(b): use must not disrupt the service. 3.2(g): do not disclose API data to other businesses that run separate applications in ways that infringe industrial property or unfair competition law.
- 4.12 and 4.13: TME may ask what the API is used for and where results are displayed, and may stop the service if the answer is unsatisfactory.
- 8.1: TME allows using and reproducing API data on other websites "only in accordance with these Terms".
- 8.5: free, non-exclusive, revocable licence, while the user holds a valid key, "to use the content obtained via the API in native desktop applications (for personal use) and to view and display it on a website owned by the User - without the right to create derivative works or to grant any further licences".
- 8.7: where API content is shown, the user must state "Data powered by TME.eu Data - no guarantee of data accuracy". 8.8: users must be told that the intellectual property belongs to TME, its subsidiaries or the manufacturers. 8.9: this does not apply to unprotected content such as stock levels.
- 8.10: when TME stops providing the API, the user must "immediately remove from the Application or destroy all data and materials obtained" through it.
- 8.2: photographs keep the watermark and may not be modified.
- 6.1: TME may change endpoints, parameters and limits.

There is no clause about caching, storage duration or building a database. Storing data is not forbidden. It is also not explicitly allowed. A normalised local copy might count as a "derivative work" (inference). KINA is an internal service of the organisation, which fits "a website owned by the User" reasonably well (inference).

### 2.3 LCSC (JLCPCB database)

Verified on the running compose stack (`kina_kina-data`, file of 2026-10-05):

- 7 146 764 rows. 723 865 with stock above 0. 681 242 distinct MPNs among them.
- Columns: LCSC part, first and second category, MPN, package, solder joint, manufacturer, library type, description, datasheet URL, price, stock. **No structured parameters.** Values live in the description text (`-55℃~+155℃ 100mW 10kΩ 75V Thick Film Resistor ±1% ±100ppm/℃`).
- In stock by category: connectors 142 631, resistors 117 585, capacitors 64 453, transistors 51 558, inductors 46 666.
- **Overlap with TME:** 500 random in-stock LCSC MPNs, looked up with `/products?mpns[]` in 10 calls: **125 (25 %) exist at TME** by exact MPN, regardless of TME stock. Matches are mostly Western brands (onsemi, Vishay, TI, Murata, Panasonic) plus some Chinese ones (UNI-ROYAL, Changjing). Extrapolated: about 170 000 in-stock LCSC MPNs have a TME record (inference, sample of 500).

## 3. How KINA calls the distributors today

From `src/main/java/ro/alacrity/kina/distributor/`:

- **Mouser** (`MouserApi`, `MouserClient`): `/search/keyword` with `InStock`, `records` = min(50, `max-results-per-search`); `/search/partnumber` with `Exact`, one part per lookup, and up to 10 joined with `|` for stock refresh. 5 s connect, 10 s read timeout.
- **TME** (`TmeApi`, `TmeClient`): one `/products/search` page (phrase, `filter[in_stock]`, `limit` up to 100), then `/products/data`, `/products/parameters` and `/products/files` for the page's symbols in parallel, 50 per call. So one TME search page costs **4 HTTP calls** (more above 50 symbols). `/products?mpns[]` is used for MPN lookups.
- **Metrics:** `kina_distributor_calls_total` counts *fetches of a search query* per distributor (`MetricNames.DISTRIBUTOR_CALLS`), not HTTP requests. A TME fetch is 4 or more HTTP calls; a Mouser fetch can be several pages plus refresh calls. It cannot be used to account a quota as it stands. `kina_distributor_rate_limited_responses_total` is counted per HTTP call.
- **Cache today:** 2 165 Mouser and 2 643 TME rows in `cached_parts`, 11 MB in total (about 2.3 KB per row with indexes and TOAST). Average stored payload about 1.6 KB.

## 4. Design options

All options assume the new cache model: metadata (description, MPN, manufacturer, category, parameters, datasheet) is kept; stock and prices expire and are refreshed in batches.

### (a) Generated parametric combinations through the normal search path

Run queries such as `10k 0603 1% resistor` or `100nF 0402 X7R 16V` during idle hours.

- Size: resistors E24 (1 Ω to 10 MΩ, 169 values) x 4 packages x 2 tolerances = 1 352 queries; E96 1 % adds 2 688. MLCC with E6 values (1 pF to 100 µF) x 5 packages x 3 dielectrics x 6 voltages is about 4 900 (many combinations do not exist). Connector and USB requests add a few hundred.
- Cost: TME 4 calls per query page, so 9 000 queries are about 36 000 TME calls. Mouser 1 call per page: 9 000 calls, or 30 days at 300 calls a day.
- Coverage: only the top page of each query, with heavy overlap between queries. The search lists expire after 3 days, so the list warming must be repeated forever. Metadata gained per call is far lower than in (b).
- Terms: for Mouser this is "pre-fetching" by definition. For TME it is allowed but wasteful.
- Verdict: **no.** Option (b) gives the same parts for a tenth of the calls.

### (b) TME category enumeration

1. `/products/categories/tree` once (1 call) and choose the leaves.
2. Per leaf: `/products/search?category_id=..&filter[in_stock]=true&limit=100&scope[]=products&scope[]=counters` page by page.
3. Per 50 new symbols: `/products/parameters` and `/products/files`.
4. Stock and prices are not needed for the fill. They are fetched with `/products/data` when a part is about to be returned (already in place, DESIGN.md 3.2 step 5).
5. Re-sync monthly. `/products/symbols` per category (35 calls for the whole catalogue) finds new and withdrawn symbols. Re-run the in-stock search pages to refresh the in-stock set.

Calls:

| Scope | In-stock parts | Search pages | Parameters | Files | Total |
|---|---:|---:|---:|---:|---:|
| SMD resistors only | 23 245 | 233 | 465 | 465 | 1 163 |
| Electronics (12 top-level groups, no cables, tools, automation, mechanics) | ~310 600 | 3 106 | 6 212 | 6 212 | ~15 530 |
| Whole in-stock catalogue | 462 921 | 4 630 | 9 259 | 9 259 | ~23 150 |
| Electronics without files (datasheet fetched on demand) | ~310 600 | 3 106 | 6 212 | 0 | ~9 320 |

Time, at a self-imposed budget: 1 call per second is 20 % of the legacy 5/s limit. Electronics in 4.3 hours of calls; at 5 000 calls a night, 3 nights. Monthly re-sync: about 35 symbol calls, 3 106 search pages and parameters for new symbols only, so about 3 500 calls a month (inference: assumes a few percent new symbols a month).

Storage: 310 000 parts x about 2.3 KB (measured row cost today, with the parameters added) is about 0.7 GB in PostgreSQL. The whole in-stock catalogue is about 1.1 GB. A normalised MPN index adds tens of MB.

What KINA gains:

- A TME search hit on known symbols costs 2 calls (search + data) instead of 4. Batch searches and repeated queries save half of their TME calls.
- `get_part` and the stock refresh for TME need only `/products/data`.
- Structured parameters for the ranker are present before the user asks.
- If TME is down, metadata still exists. Stock is then stale, and the ships-now rule still applies: such parts must be marked unverified or left out, not presented as in stock.
- A later step could search TME locally (like the LCSC FTS database) and call TME only for stock. That is a separate design.

Risks: TME may count this as heavy use (3.2(b), 4.12). The licence forbids derivative works (8.5). All data must be deleted if access ends (8.10). TME may change limits (6.1).

### (c) Mouser keyword sweeps

- 300 of 1 000 daily calls x 50 records is at most 15 000 part records a day, before overlap.
- The hidden paging cap (somewhere between about 1 050 and 5 000 records) means large result sets need many narrow queries. There is no category endpoint. Mouser gives no structured parameters, so the metadata gained is thin.
- It uses the quota that production users need.
- The terms forbid exactly this (pre-fetching, storing, bulk download).
- Verdict: **no.**

### (d) MPN-driven enrichment

Look up LCSC MPNs at TME and Mouser.

- TME `/products?mpns[]`: 681 242 MPNs / 50 is 13 625 calls, about 3 nights. About 25 % hit (verified on a sample), so about 170 000 TME records, plus 3 400 parameter calls.
- Mouser `/search/partnumber`: 681 242 / 10 is 68 125 calls, 227 days at 300 a day, and forbidden by the terms. **No.**
- Cheaper for TME: after (b), join TME MPNs with the LCSC database **locally, at no call cost**. That covers every LCSC part that TME holds in stock.
- Remote MPN lookups only add LCSC parts that TME lists but does not stock. Their parameters would still enrich LCSC parts. Caution: the stock rule forbids storing a TME `Part` with stock 0. Such data must be stored as LCSC enrichment (parameters keyed by MPN), never as a TME part.
- Also map Mouser MPNs seen in normal searches to LCSC and TME locally. That costs nothing extra but depends on Mouser's answer about storage.

### Comparison

| Option | Calls for target | Coverage per night (5 000 TME / 300 Mouser) | Structured parameters | Terms risk | Effort | Value |
|---|---|---|---|---|---|---|
| (a) combinations | ~36 000 TME + ~9 000 Mouser | ~1 250 queries, top pages only | yes (TME) | Mouser: forbidden; TME: low | low (reuses search) | low; lists expire in 3 days |
| (b) TME categories | ~15 500 (electronics) | ~100 000 parts | yes | TME: medium (derivative works, heavy use) | medium | **high** |
| (c) Mouser sweeps | 300 a day at most | ≤15 000 records | no | **forbidden** | low | low |
| (d) TME by MPN, remote | ~17 000 | ~250 000 MPNs probed, ~62 000 hits | yes | TME: medium | low | medium; after (b), mostly redundant |
| (d) local MPN join | 0 | all of (b) | yes (for LCSC) | inherits (b) | low | **high** |
| (d) Mouser by MPN | 68 000 | 3 000 MPNs | no | **forbidden** | low | none |

## 5. Recommendation and plan

**In five sentences:** Build a TME-only background filler that enumerates in-stock products of the electronics categories by `category_id`, 100 per call, and fetches parameters and datasheets in batches of 50. Run it at night at no more than 1 call per second and 5 000 calls a night, stop for the night on the first 429, and keep it off by default. Store the metadata forever in the new model and fetch stock and prices only when a part is about to be returned. Join TME and LCSC by normalised MPN locally, so about a quarter of LCSC parts gain TME parameters without any extra calls. Make no background calls to Mouser, and get written answers from Mouser and TME about storage before enabling anything by default.

### 5.1 Tables (Flyway V8 onwards, aligned with the new cache model)

The new cache model may already define the part and offer tables. If so, reuse them. The filler needs:

- `part_metadata`: `distributor`, `part_number` (primary key), `mpn`, `mpn_norm` (upper case, letters and digits only), `manufacturer`, `category_id`, `category_path`, `description`, `parameters` JSONB, `datasheet_url`, `source` (`search` or `filler`), `first_seen_at`, `metadata_fetched_at`, `withdrawn_at`. Index on `mpn_norm`. No stock and no prices here.
- `part_offer` (or the new model's equivalent): `distributor`, `part_number`, `stock`, `prices` JSONB, `offer_fetched_at`. Expires as designed.
- `mpn_link`: `mpn_norm`, `distributor`, `part_number` (LCSC rows filled from the SQLite file during the join). Or a view over `part_metadata` plus an LCSC MPN table built at each JLCPCB refresh.
- `filler_task`: `distributor`, `category_id`, `phase` (`search`, `parameters`, `files`, `done`), `next_page`, `pages`, `count`, `started_at`, `updated_at`, `last_error`. Lets the filler resume after a restart.
- `distributor_call_budget`: `day`, `distributor`, `origin` (`user` or `filler`), `calls`. One row per day, updated with each HTTP call. This is the quota ledger.

### 5.2 Quota accounting

`kina_distributor_calls_total` counts query fetches, not HTTP calls (section 3), so it cannot meter a quota. Add one HTTP-level counter in `TmeApi.execute` and `MouserApi.postOnce`, next to the existing rate-limit counter:

- `kina_distributor_http_requests_total{distributor, endpoint, origin}` with `origin` = `user` or `filler`.
- Write the same increments to `distributor_call_budget`, so the daily total survives restarts and the filler can read it before each call.

The filler stops when `filler` calls for the day reach its budget, or when `user + filler` reaches a global ceiling.

### 5.3 Scheduler

- A Spring `@Scheduled` job every minute. It runs only inside a window (default 01:00 to 06:00, Europe/Bucharest) and only when no user search ran in the last 10 minutes.
- A token bucket per distributor (default 1 call per second) shared with nothing else. User requests always go first: the filler pauses while a user request is in flight.
- On the first 429 or `Retry-After`: stop the filler for the night. It must not reuse `RateLimitRetry` waits, which are tuned for user requests. Log one WARN.
- On 5xx or timeouts: back off (1, 5, 15 minutes), then give up for the night after 3 failures.
- Order: biggest value first. Passives, semiconductors, connectors, then the rest. Within a leaf, page order.
- Metrics: the HTTP counter above, plus gauges `kina_filler_parts{distributor}`, `kina_filler_categories_done`, `kina_filler_last_run_timestamp`.
- Admin endpoints (session-authenticated, admin only): status, pause, resume, and "purge TME data" for clause 8.10.

### 5.4 Configuration

Under `kina.filler` in `KinaProperties` (records only), documented in `.env.example`:

| Key | Default | Meaning |
|---|---|---|
| `kina.filler.enabled` | `false` | master kill switch |
| `kina.filler.window` | `01:00-06:00` | local time window |
| `kina.filler.zone` | `Europe/Bucharest` | zone of the window |
| `kina.filler.idle-after` | `10m` | no user search for this long |
| `kina.filler.tme.enabled` | `false` | TME filler |
| `kina.filler.tme.calls-per-second` | `1` | token bucket |
| `kina.filler.tme.max-calls-per-night` | `5000` | budget |
| `kina.filler.tme.categories` | electronics top-level ids | `112140, 112309, 46, 100327, 100232, 67, 42, 112502, 113611, 100440, 63, 113567` |
| `kina.filler.tme.fetch-files` | `true` | datasheets in the fill |
| `kina.filler.tme.resync-after` | `30d` | monthly re-sync |
| `kina.filler.mpn-join.enabled` | `true` | local TME and LCSC join (no calls) |
| `kina.filler.mouser.enabled` | `false`, no implementation | reserved; leave out unless Mouser agrees in writing |

### 5.5 Other changes this needs

- Show "Data powered by TME.eu Data - no guarantee of data accuracy" wherever TME data is returned (MCP tool output, REST responses, token UI). This is needed today, independent of the filler (clause 8.7).
- Decide the Mouser cache policy against the terms: shortest possible lifetime, or a written exception. The "metadata forever" model should not apply to Mouser without that.
- Update `docs/DESIGN.md` (cache model, filler, ledger), `docs/OPERATIONS.md` (switch, budgets, purge) and `.env.example` in the same commit.

### 5.6 Rollout

1. Ask TME and Mouser (section 6). Add the TME attribution.
2. Add the HTTP counter and the budget ledger. Watch one week of normal use.
3. Fill SMD resistors only (about 1 200 calls, one night). Measure calls, time, storage and 429s.
4. Measure the gain on the e2e queries: TME calls per search and latency, before and after.
5. Run the electronics categories over 3 nights. Then enable the monthly re-sync.
6. Turn on the local MPN join and measure how many LCSC results gain parameters.

## 6. What must be confirmed before enabling by default

- **TME:** the current request limit for our token (per second, per day), whether a nightly bulk fill of about 15 000 calls is acceptable, and whether storing product metadata and parameters for internal use is allowed under clause 8.5 ("without the right to create derivative works"). Write to developers@tme.eu or use the issue form. Read the limits in the logged-in User Panel.
- **Mouser:** the full API terms text (in a browser). Whether any caching period is allowed. Whether today's 5-day cache, the planned forever-metadata model and the MPN mapping are acceptable for an internal tool. When the daily quota resets.
- **Internal:** who owns the distributor relationships and signs off the answers.

## 7. Open questions

1. TME current rate limit: the public docs state none, the legacy terms say 5 per second, and KINA has already seen 429s. Is there also a daily cap?
2. Exact Mouser paging cap: between about 1 050 and 5 000 records. Only relevant if Mouser ever allows bulk use.
3. Mouser storage: the search-engine excerpts are unambiguous, but the full text might hold an exception.
4. TME "derivative works" (8.5): does a normalised internal database count?
5. TME stock rule: the in-stock filter returns `EXTERNAL_WAREHOUSE` products. Do they count as ships-now for KINA? Keep deciding from `/products/data` until clarified.
6. Local TME search: worth a separate design once the metadata is local. It would turn a TME search into one `/products/data` call per 50 results.
7. How fast does the TME in-stock set change? This decides the re-sync interval. Measure it after the first two monthly runs.

## 8. Sources (all read 2026-10-07)

- Mouser API swagger: https://api.mouser.com/api/docs/V1 and https://api.mouser.com/api/docs/V2 (fetched as JSON).
- Mouser Search API page: https://www.mouser.com/en/api-search/ (search-engine excerpt; direct fetch timed out).
- Mouser API terms: https://www.mouser.com/en/apiterms/ and https://www.mouser.de/en/apiterms/ (search-engine excerpts; direct fetch blocked).
- TME API v2 documentation: https://developers.tme.eu/en/api-doc/v2 and the vendored `docs/vendor/tme-api-v2-openapi.json`.
- TME terms 2026-07-01: https://developers.tme.eu/pdfs/en/terms_2026-07-01.pdf
- TME terms 2013-02-15 with amendments to 2025-03-01: https://developers.tme.eu/pdfs/en/terms_2013-02-15.pdf
- Live calls: TME `/auth/token`, `/products/categories/tree`, `/products/search` (category-only, last page, past the last page, parameter filters, counters for root and passives), `/products/symbols`, `/products/parameters`, `/products/data`, `/products?mpns[]` (11 calls), plus `/products?mpns[]` for 500 random LCSC MPNs (10 calls). Mouser `/search/keyword` (startingRecord 1, 1001, 5001, 10001) and `/search/partnumber` (10 MPNs).
- Local: JLCPCB `parts-fts5.db` of 2026-10-05; PostgreSQL `cached_parts` and `metrics_counters` of the compose stack.
