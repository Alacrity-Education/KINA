# KINA API reference

KINA exposes three surfaces:

- a REST API under `/api/v1`,
- an MCP server at `/mcp` (Streamable HTTP, stateless),
- an OAuth 2.1 authorization server under `/oauth` and `/.well-known`, used by MCP clients such as Claude's remote connector.

All paths are relative to the public origin, for example `https://kina.example.com`. Response fields are snake_case. Distributor names are case-insensitive in every input (`lcsc`, `LCSC`, ` Mouser `) and upper-case in every output (`LCSC`, `TME`, `MOUSER`).

## Authentication

| Mode | `/api/**` and `/mcp` |
|---|---|
| `dev` | No credentials needed; requests run as the "Development Admin". A bearer token that is sent is still validated. |
| `prod` | `Authorization: Bearer <token>` required. |

A token is either a static token from the web UI (`kina_` followed by 43 characters, valid 30 days) or an access token issued by the OAuth flow (same format and table, valid 1 hour, `kina.oauth.access-token-validity`).

A missing, unknown, expired or revoked token gives `401` with an `application/problem+json` body and:

```
WWW-Authenticate: Bearer realm="kina", resource_metadata="https://<host>/.well-known/oauth-protected-resource"[, error="invalid_token"]
```

`error="invalid_token"` is added only when a token was sent. The `resource_metadata` link is how MCP clients find the authorization server.

In `prod` with required groups (`OIDC_REQUIRED_GROUPS`), a user who is no longer a member is blocked: every token of that user gives `401` with `error="invalid_token"`, and the web session ends. A static token of a member triggers a background membership re-check at most once per user per interval; the request itself never waits for the identity provider.

`/actuator/health`, `/actuator/info` and `/actuator/prometheus` are not on this port: they are served on the management port (`KINA_METRICS_PORT`, default 9090) without authentication, see [OPERATIONS.md](OPERATIONS.md#monitoring).

CORS is enabled (any origin, no credentials) for `/mcp`, `/.well-known/**`, `/oauth/register`, `/oauth/token` and `/oauth/revoke`.

## REST API

### Common types

`SearchResponse`:

| Field | Type | Meaning |
|---|---|---|
| `query` | string | The query as sent. |
| `parsed` | object | What KINA understood: `family`, value fields such as `capacitance`, `resistance`, `inductance`, `impedance` (ferrite beads, with the test frequency: `"120ohm @100MHz"`), `voltage`, `current` (an inductor's rated current), `saturation_current`, `dcr` (a maximum), `power`, `temperature` (maximum operating temperature, `"105°C"`), `lifetime` (`"2000h"`) (display form, for example `"10uF"`), `tolerance` (`.1%` and `0.1%` both work), `dielectric`, `package`, `mounting`, `polarity` (`N-channel`, `P-channel`, `NPN`, `PNP`, `complementary`), `subtype` (`standard` for a rectifier or switching diode, `fixed` or `adjustable` for a regulator; a stated output voltage means `fixed`), `technology` (resistors, capacitors, inductors: `thin film`, `thick film`, `metal film`, `carbon film`, `metal oxide`, `wirewound`, `metal foil`, `metal strip`, `current sense`; `ceramic`, `tantalum`, `tantalum polymer`, `aluminium polymer`, `hybrid polymer`, `polymer`, `aluminium electrolytic`, `film`, `polypropylene`, `polyester`, `PPS`, `supercapacitor`; `multilayer`; MOSFETs, transistors and gate drivers: `GaN`, `SiC`, `silicon`), `elements` (`"4"` for `4 lines` or `4 elements`, `"array"` for `array` or `network` without a count; omitted for a single element), `form_factor` (`chassis` when the words say heatsink, chassis, bolt or screw mount, or aluminium housed; omitted otherwise), `part_numbers` (the part numbers the query names, as sent, for example `["uP1966E"]`; omitted when none, see [Part numbers in a query](#part-numbers-in-a-query)), `keywords`, and for connectors `connector` (see [`parsed.connector`](#parsedconnector)). Absent values are omitted. Units are case-sensitive where the case matters: `m` is milli and `M` mega (`mhz` is still read as MHz), `2000h` is hours and `1H` henry. |
| `query_understood` | boolean | False when KINA recognised no component type and no typed parameter (value, rating, tolerance, dielectric, package, mounting, technology, connector attribute, element count), for example `asdfqwerty zz9` or a bare part number. The parts were then found by keywords only: every `match` is null and every `exact_matches` is null, so "no parametric understanding" is not mistaken for "no matches". |
| `hint` | string | When `query_understood` is false: what to change (name the component type and its key parameters, or use `get_part` for a part number). For an understood query, when one or more distributors returned nothing: the same kind of text as the distributor `hint`, naming those distributors. Omitted otherwise. |
| `ranking` | string | `blended` (deterministic score blended 50/50 by rank with the in-process cross-encoder) or `fallback` (deterministic order only). |
| `ranking_note` | string or null | Why the ranking fell back. Always present, null when `ranking` is `blended`. See [Ranking notes](#ranking-notes). |
| `currencies` | array of strings | The price currencies in this response, sorted, for example `["EUR", "USD"]`. LCSC prices are USD (the JLCPCB database), TME and Mouser prices EUR (the account currency). KINA never converts prices, so compare across distributors with care. |
| `distributors` | array | One `DistributorResult` per searched distributor. |

### Ranking notes

Ranking is the deterministic parametric score blended 50/50 by rank with a cross-encoder model that runs inside KINA. When the model cannot score, KINA returns the deterministic order, sets `ranking` to `fallback` and explains why in `ranking_note`. A search never fails because of the model.

| `ranking_note` | Meaning |
|---|---|
| `cross-encoder disabled` | `KINA_CROSS_ENCODER_ENABLED=false`. |
| `cross-encoder model not loaded yet` | The model is still loading (first seconds after startup), or its files are missing or unusable (checked again hourly). Outside Docker it may still be downloading. |
| `cross-encoder timeout after 5s` | Scoring did not finish within the ranking budget (`kina.ranking.timeout`). |
| `cross-encoder timeout: budget exhausted` | No ranking time was left before the model was called. |
| `cross-encoder busy: no free slot within 5s` | Other searches were using all scoring slots for the whole budget. |
| `cross-encoder failed: <reason>` | The model raised an error. |
| `batch ranking budget of 60s exhausted` | Batch only: the query was reached after the batch ranking budget (`kina.ranking.batch-timeout`) ran out. |

### `parsed.connector`

Present when KINA reads the query as a connector request (`parsed.family` is then `"connector"`). Absent attributes are omitted.

| Field | Type | Meaning |
|---|---|---|
| `type` | string | For example `pin header`, `female header`, `box header`, `terminal block`, `usb-c`, `micro usb`, `usb`, `fpc`, `rj45`, `d-sub`, `barrel jack`, or a generic `connector`. |
| `series` | string | A series such as JST `XH`, `PH`, `GH`, `SH`, `ZH`. |
| `gender` | string | `male` or `female`. |
| `positions` | integer | Number of positions. Read from `6-position`, `6 pos`, `6 pin`, `6P`, `6 way`, `PIN: 6` or from `rows x pins`. IC packages such as `SOIC-8` are not read as positions. |
| `rows` | integer | From `1x6`, `2x3`, "single row", "dual row". |
| `pitch` | string | Display form, for example `"2.54mm"`. `0.1"` becomes `2.54mm`. `dupont` implies `2.54mm`. |
| `orientation` | string | `right angle` or `vertical`. |
| `usb_type` | string | USB requests only. `Type-C`, `Micro-B`, `Micro-AB`, `Mini-B`, `Mini-AB`, `Type-A` or `Type-B`. |
| `usb_standard` | string | USB requests only. Canonical name: `USB 2.0`, `USB 3.2 Gen 1`, `USB 3.2 Gen 2`, `USB 3.2 Gen 2x2`, `USB4`, `Thunderbolt 3`, `Thunderbolt 4`, `USB 1.1`, or `USB 3.x` when a 3.x version is written without a generation (for example `USB 3.1`). |
| `usb_speed_gbps` | number | USB requests only. Speed class: 0.48 (USB 2.0), 5 (USB 3.0, 3.1 Gen 1, 3.2 Gen 1, and `USB 3.x`), 10 (3.1 Gen 2, 3.2 Gen 2), 20 (Gen 2x2), 40 (USB4). |
| `pin_configuration` | integer | USB requests only. The canonical pin configuration: your `positions` normalised (17 or 18 to 16, 25 or 26 to 24, 7 or 8 to 6; 14 stays 14), or the implied one. |
| `pin_configuration_implied` | boolean | Only present, as `true`, when you gave no pin count and KINA inferred it from the standard (see below). |
| `shield_pins_counted` | integer | USB requests only. 1 or 2 when your `positions` count shell or mounting pins on top of the configuration (17 gives 1, 18 gives 2). |
| `mounting_style` | string | USB requests only. `mid-mount`, `hybrid` or `top-mount`. Plain `SMD` or `THT` stays in `parsed.mounting`. |
| `features` | array of strings | USB requests only. For example `power only`, `PD`, `waterproof`, `board lock`. |

Mounting (`THT` or `SMD`) stays in `parsed.mounting`. The server also knows whether a pitch was implied rather than written, but it does not put that in the response.

`DistributorResult` (a part a query names by its part number can be listed without stock: it then comes last, with `stock` 0, and is not counted in `fetched`):

| Field | Type | Meaning |
|---|---|---|
| `distributor` | string | `LCSC`, `TME` or `MOUSER`. |
| `total_results` | integer or null | How many matches the distributor reported for the phrase that produced the parts. Null when unknown or on error. |
| `fetched` | integer | Every in-stock part KINA received from the distributor for the query, before any exclusion. |
| `excluded_by_constraints` | integer | Parts of `fetched` left out because a known attribute contradicts a hard constraint of the request (see [Hard constraints](#hard-constraints)): the value, the package (except for inductors, crystals and oscillators), mounting, technology, the component type, the polarity, an exact voltage, connector attributes, an array or network for a single-element request, the form factor (key `form factor`: a chip or axial resistor for a chassis or SOT-227 request). A part that does not state the attribute stays, lists it in `unverified` and ranks below verified matches. |
| `excluded_by_constraints_detail` | object | `excluded_by_constraints` per constraint, for example `{"capacitance": 12, "package": 3}`. Each part is counted once, under the first constraint it contradicts, so the values add up to `excluded_by_constraints`. Keys: the value by its kind (`capacitance`, `resistance`, `inductance`, `impedance`, `frequency`), `package`, `mounting`, `technology`, `elements`, `type`, `polarity`, `voltage`, `load capacitance`, `connector type`, `gender`, `positions`, `pitch`, `usb type`, `pin configuration`, `usb standard`. Empty object when nothing was excluded. |
| `hint` | string | Present when this distributor returned no part for an understood query and did not fail: which hard constraints could not be met, how many parts were below a stated rating, and that no substitutes are returned, for example `No in-stock 22uF capacitor in package 0201 at TME; capacitance and package are never relaxed. No substitutes are returned; try another package or value.` Also present when `requested_part_found` is false, first: `EPC23101 is not listed in stock at MOUSER; the parts below are keyword matches.` or `EPC2218 is listed at MOUSER (EPC2218A) but was left out: voltage 80V below 100V (pass "allow_below_spec": true to see it).` |
| `requested_part_found` | boolean or null | Null when the query names no part number (`parsed.part_numbers`) or the distributor failed. True when a returned in-stock part is the requested one for every part number. False otherwise; the `hint` then says why. A requested part the distributor lists without stock is still returned (last, `stock` 0) but leaves this false: it cannot ship now. See [Part numbers in a query](#part-numbers-in-a-query). |
| `excluded_below_spec` | integer | Parts of `fetched` left out because a known rating is below the request (see [Ratings](#ratings-are-hard-minimums)). 0 with `allow_below_spec`, which returns them flagged instead. |
| `excluded_below_spec_detail` | array | Up to 5 of the `excluded_below_spec` parts, closest to the request first, so a count names the part and the failing rating: `{"part_number": "65-EPC2218A", "mpn": "EPC2218A", "rating": "voltage", "part_value": "80V", "requested": "100V"}`. `part_value` is what the distributor's data says (KINA does not correct it). Empty when nothing was left out. |
| `returned` | integer | The length of `parts`: at most `max_results` and at most `fetched - excluded_by_constraints - excluded_below_spec`. |
| `out_of_stock_matches` | integer or null | Matches the distributor has but cannot ship now (dropped by the stock rule) on the pages KINA read: the part exists but is not returned. Not part of `fetched`. While every match is out of stock KINA reads up to 2 more pages and then the next relaxation step. Null when unknown (a cached search stored before this field existed). |
| `cache` | string | `hit` (served from the cached list), `partial` (the cached list held too few parts for `max_results` and KINA fetched more from the distributor), `miss` (searched live), `bypassed` (`bypass_cache`), `not_applicable` (LCSC, and distributors that were never looked up) or `stale` (the live search failed, `error` says why, and the parts come from the query's expired cached list; their stock and prices may be marked `stale`). |
| `fallback_query` | string or null | Set when the first phrase found nothing that meets the request at this distributor and a relaxed phrase produced the parts in the entry. Only Mouser and TME; null otherwise (always present in the JSON). See [Relaxation](#relaxation). |
| `distributor_query` | string or null | The phrase KINA sent instead of your text: ratings are left out (see [Ratings](#ratings-are-hard-minimums)); at LCSC they are sent as `>=25V` terms that the local database checks; connector requests are rewritten into the distributor's vocabulary. Null when your text went through as written. Always present. If it found nothing that meets the request, `fallback_query` is what was sent after it. |
| `query_terms_dropped` | array of strings | Informational: the stated terms that were not in the phrase that produced the parts, for example `["voltage"]` (Mouser and TME never get ratings, so a rated request always lists them), `["voltage", "dielectric"]` after a relaxation, and at LCSC the free-text words its database search dropped. The ranker still checks every constraint. Replaces the former `relaxed` field. |
| `constraints_relaxed` | array of strings | The constraints actually loosened to obtain the parts: of what the relaxation loosened (`dielectric`, `package`, `tolerance` from the ladder at Mouser and TME; the terms the LCSC database search dropped), those the returned parts really miss. Empty when nothing was relaxed. Each affected part names what it misses in `mismatches`. A rating is never loosened. Replaces the former `relaxed` field. |
| `exact_matches` | integer or null | Returned parts whose typed constraints are all verified and met (`match` 1.0, no `unverified`, not `below_spec`). Free-text words such as `housed` or `heatsink` never block an exact match. Null when `query_understood` is false. |
| `error` | string or null | `rate_limited`, `unavailable`, `not_configured`, `timeout` or `bad_response`. A failing distributor has an empty `parts` list, except that parts already in hand are kept. `rate_limited` means the rate limit outlasted the request deadline (see [Rate limits and timing](#rate-limits-and-timing)). |
| `rate_limit_waited_ms` | integer | Milliseconds this distributor's fetch spent waiting on rate limits, including waiting for a shared cool-down. Always present, 0 when KINA did not wait. |
| `parts` | array | `PartResponse` entries, best first. |

`PartResponse` has two detail levels (`detail` parameter). `compact` (the default for searches) has `rank`, `match`, `below_spec`, `mismatches`, `unverified`, `distributor`, `part_number`, `manufacturer`, `manufacturer_id` (TME), `mpn`, `description`, `stock`, `stock_as_of`, `stale`, `min_order_qty`, `order_multiple`, `prices`, the order fields when `quantity` is above 1, `availability`, `lifecycle`, `datasheet_url`, `product_url` and the canonical `attributes` only. `full` (the default for a single-part lookup) adds `score`, `category`, `package`, `photo_url`, every raw distributor attribute (TME `Operating voltage`, `Case - inch`...) and `extra`, and always has the order fields. A field a level leaves out is not in the JSON; null `category`, `package` and `photo_url` are omitted too.

| Field | Type | Meaning |
|---|---|---|
| `rank` | integer or null | 1 is best within the distributor. Null for single-part lookups. |
| `score` | number | `detail=full` only (omitted in `compact` and for single-part lookups). 0 to 1, relative to the other candidates, so the last of several good parts can show 0; `rank` carries the order. |
| `match` | number or null | 0 to 1, two decimals. How well the part satisfies the stated typed parameters it states (free-text words do not count): a stated constraint the part does not state is listed in `unverified` and left out of `match` (out of both what it earned and what it could earn). So `match` 1.0 with a non-empty `unverified` is **not** a confirmed fit: check the datasheet. Null for single-part lookups, when the query was not understood, and when the part states none of the stated constraints. |
| `below_spec` | boolean | Only present, as `true`, on a part whose known rating is below the request. Such parts are returned only with `allow_below_spec`, after every part that meets the request, closest to the target first. |
| `distributor` | string | `LCSC`, `TME`, `MOUSER`. |
| `part_number` | string | Distributor part number (LCSC `Cxxxxx`, TME symbol, Mouser number). |
| `manufacturer` | string | |
| `manufacturer_id` | string | The distributor's own manufacturer id, as the distributor provides it (TME only; Mouser and LCSC have none). Omitted when absent. |
| `mpn` | string | Manufacturer part number. |
| `description` | string | |
| `category` | string or null | |
| `package` | string or null | For example `0805`, `SOT-23`. |
| `stock` | integer | Quantity that ships now; above 0, except for a part you asked for by its part number that the distributor lists without stock: then 0, with `availability.status` `out_of_stock`. |
| `stock_as_of` | string | When the stock and prices were fetched from the distributor (ISO 8601, to the second). Cached TME and Mouser figures older than `kina.cache.stock-ttl` (24 h) are refreshed before a part is returned; LCSC figures are the JLCPCB database's. |
| `stale` | boolean | Only present (and true) when the TME or Mouser stock and prices are older than `kina.cache.ttl` (3 days) and could not be refreshed (the distributor failed or is not configured). `availability.status` is then `stale`, and the part ranks below the fresh parts of its distributor. Treat stock and prices as unconfirmed. Within 3 days a failed refresh returns the cached figures without the flag. |
| `min_order_qty` | integer or null | Null when unknown (always null for LCSC). |
| `order_multiple` | integer or null | Null when unknown (always null for LCSC). |
| `prices` | array | At most 3 entries, the smallest quantity brackets: `{"qty": 1, "unit_price": 1.40, "currency": "EUR"}`. LCSC prices are in USD. |
| `ordered_quantity` | integer | Pieces you would order for `quantity`: raised to the minimum order quantity (and the first price bracket) and rounded up to the order multiple. |
| `unit_price_at_quantity` | number | Unit price of the bracket that applies to `ordered_quantity` (from all brackets, not only the 3 returned). Omitted when the part has no prices. |
| `total_price` | number | `unit_price_at_quantity * ordered_quantity`, in the price currency. |
| `availability` | object | `{"status": ..., "note": "<plain sentence>"}`. The status is the stock situation only: `in_stock`; `low_stock` (fewer than `kina.search.low-stock-threshold` pieces, default 10, or fewer than twice `quantity`; such a part ranks lower); `limited` (fewer pieces ship now than `quantity`); `last_units` (no restocking: TME `AVAILABLE_WHILE_STOCKS_LAST`, Mouser end of life, obsolete or not recommended for new designs); `special_order` and `external_warehouse` (TME; excluded by default); `out_of_stock` (stock 0: only a part you asked for by its part number, in the query or with `get_part`, that the distributor lists without stock; note `Out of stock at MOUSER; shown because the part number was requested explicitly.`); `stale` (stock and prices older than 3 days that could not be refreshed: the note says when they were last confirmed and the last known stock). Supply and lifecycle flags are in `lifecycle`. The note also carries TME `HARDLY_AVAILABLE` ("limited market availability", a supply warning: TME may still hold a large stock), `MOQ_VALID_WHILE_STOCKS_LAST` ("the MOQ may change after the product is sold out"), `DANGEROUS`/`OVERSIZED` (shipping restrictions) and the Mouser maximum order quantity when it is below `quantity`; with `detail=full` also TME `NEW`/`PROMOTED`, the Mouser lifecycle and reel option and the JLCPCB library type (Basic, Preferred, Extended). |
| `lifecycle` | string | `active`, `new` (TME `NEW`, Mouser "New Product"), `supply_constrained` (TME `HARDLY_AVAILABLE`) or `last_time_buy` (TME `AVAILABLE_WHILE_STOCKS_LAST`, Mouser end of life / obsolete / NRND). `supply_constrained` costs 0.05 and `last_time_buy` 0.1 of score (`kina.search.lifecycle.*`), taken from the deterministic and from the final score. |
| `mismatches` | array of strings | Search results only, omitted when empty: the stated parameters the part is known not to satisfy, for example `"dielectric: X5R instead of X7R"`, `"package: 1210 instead of 1206"`, `"voltage: 16V below 25V"`, `"dcr: 40mohm above 20mohm"`, `"elements: single instead of array"`. A parameter the part does not state is not listed here but in `unverified`. |
| `unverified` | array of strings | Search results only, omitted when empty: stated constraints the distributor does not state for this part, for example `["current"]` for an inductor listed without a current rating, or `["package", "dielectric"]`. They are left out of `match`, and such a part ranks below every part whose stated constraints are all verified and met. |
| `datasheet_url` | string or null | TME: the `DTE` document, else a document named "datasheet", else the TME product page (whose documentation section links the manufacturer's files; TME lists no datasheet for many Eaton and Murata parts). `extra.datasheet_source` says which (`dte`, `document`, `product_page`). |
| `photo_url` | string or null | Null when the distributor gives none (LCSC). |
| `product_url` | string or null | |
| `attributes` | object | Parametric attributes, for example `{"Capacitance": "10uF"}`. Canonical keys: `Capacitance`, `Resistance`, `Inductance`, `Impedance` (ferrite beads, `"120ohm @100MHz"`), `Frequency`, `Voltage`, `Current` (`RatedCurrent` for inductors and ferrite beads), `SaturationCurrent`, `DCR`, `Power` (watts below 1 kW, `"250W"`, `"0.125W"`; kilowatts from 1 kW, `"1.5kW"`; TME's `0.25kW` is shown as `250W`), `MaxTemperature`, `OperatingTemperature` (the printed range, `"-55...155°C"`), `FormFactor` (`chip`, `through_hole`, `chassis`, `power_package`, `power_smd`; only when the package does not already say it), `Lifetime` (`"2000h @105°C"`), `Tolerance`, `Dielectric`, `Package` (imperial, see [Hard constraints](#hard-constraints)), `Mounting`, `Family`, `Technology`, `Polarity` (transistors: `N-channel`, `P-channel`, `NPN`, `PNP`, `complementary`), `Subtype` (`standard` rectifier or switching diode; `fixed` or `adjustable` regulator) and the connector keys below. Crystals and oscillators carry their size code (`3225`) as `Package`, also when the distributor states only the body (`3.2x2.5x0.8mm`). A ferrite bead or inductor never has `Resistance`: its ohm values are `Impedance` and `DCR`. Resistors, capacitors and inductors carry `Technology` (same values as `parsed.technology`) when the distributor data names it. A chip resistor or capacitor without a stated package gets `Package` from a known MPN series (`TNPW0805...`, `RC0805...`, TE `RN73C2A...`). Connector parts also carry `ConnectorType`, `Series`, `Gender`, `Positions`, `Rows`, `Pitch`, `Orientation` and `Mounting` when the distributor data allows it. USB connector parts add `UsbType`, `UsbStandard`, `UsbSpeedGbps`, `PinConfiguration`, `ShieldPinsCounted`, `MountingStyle`, `Waterproof` (the IP rating, or `yes`) and `Features` (comma separated). `Positions` stays as the distributor reported it; `PinConfiguration` is the canonical count. Passive parts also carry, when the distributor data states them: `Elements` (an array or network: `"4"`, or `"array"` without a count), `RippleCurrent` (a capacitor's current is always its ripple current, never `Current`; TME calls it "Operating current"), `ESR` and `Impedance` of a capacitor in ohm with the test frequency when stated (`"15mohm @100kHz"`), `Dimensions` (`"D6.3 x 5.8mm"` for a can: diameter x height; `"8.8 x 8.4 x 3.8mm"` otherwise), `Qualification` (`"AEC-Q200"`), `Features` (`"low ESR"`, `"shielded"`, `"long life"`...). The `Package` of a can capacitor (aluminium electrolytic, polymer) is its size (`"D6.3 x 5.8mm"`), the vendor case code (Panasonic `D`) is in `Case`. |
| `extra` | object | `detail=full` only. Distributor-specific details (TME `product_status`, `category_id`, `packing`, `price_type`; Mouser lifecycle, RoHS, compliance, lead time; LCSC library type, and so on). |

### Ratings are hard minimums

Voltage, current, saturation current, power, maximum temperature and lifetime in a query are minimum ratings: `25V` accepts 35 V and 50 V parts, `6A` accepts 8 A. An equal rating ranks a little above a higher one (25 V, then 35 V, 50 V, 100 V). A voltage far above the request costs more: above twice the requested voltage (three times for capacitors, where derating makes 2x to 3x normal) a part loses 0.25 of score per doubling beyond that, at most 0.5, so for `GaN FET 100V` a 600 V part ranks below the 100 V to 200 V parts. It is still returned with `match` 1.0. For MOSFETs a lower on-resistance (the `Resistance` attribute, R_DS(on)) ranks a little higher. For an inductor `6A` is the rated current; write `Isat 8A` or `saturation current 8A` for the saturation current. `DCR < 20mOhm` or `DCR 20mOhm max` is a maximum; `low DCR` is a preference (lower DCR ranks higher).

A part whose **known** rating is below the request (or whose DCR is above the stated maximum) is never returned by default: it is counted in `excluded_below_spec`. Pass `allow_below_spec=true` to see such parts anyway: they come after every part that meets the request, flagged `"below_spec": true`, with the shortfall in `mismatches`, ordered by how close they are to the target (the sum of `|ln(part / requested)|` over the failed ratings, closest first), never by the blended score. A part that does not state a requested rating is not excluded: it lists it in `unverified` and ranks below verified parts.

A regulator output voltage and a Zener voltage must match (within 2 %); a part with another one is excluded (a hard constraint). A fuse current must match too; a different one is a mismatch, not below spec. Ratings are not sent to the Mouser and TME keyword searches (a phrase with `25V` only finds parts that print `25V`); LCSC checks them in its local database (`>=25V`).

### Part numbers in a query

A token with letters and digits mixed (at least 5 characters, at least two digits, for example `uP1966E`, `EPC2302`, `LMG2100R026`) is a part number: `parsed.part_numbers` lists it as sent. Values (`100V`, `4k7`), packages (`SOT-23`), standards and quantities (`RS485`, `AEC-Q200`, `IP67`, `2-channel`, `1000pcs`) are not. A part whose MPN or distributor part number equals the token, or starts with it, is the requested part: letters and digits are compared, so hyphens, spaces and case do not matter (`EPC2218` requests `EPC2218A`). It is returned first in its distributor, as long as it meets the hard constraints and ratings like any other part. Each distributor entry says whether it is there (`requested_part_found`) and, when not, the `hint` says why: not listed in stock at that distributor (the other parts are keyword matches, not the requested one), or listed but left out, with the reason. A query that is only a part number is still `query_understood: false`; `get_part` looks a number up directly.

### Hard constraints

Hard constraints are never relaxed and never substituted. The table per component family is in [SEARCH.md](SEARCH.md#hard-and-relaxable-constraints) and in DESIGN.md 3.4. In short: the primary value (resistance, capacitance, inductance, a ferrite bead's impedance at its frequency, a crystal's or oscillator's frequency), mounting, technology and the package are hard for every family, except that the package of inductors, crystals and oscillators is relaxable. The type is hard: crystals and oscillators are never mixed; MOSFETs and gate drivers (including GaN power stages and half-bridges with an integrated driver) are never mixed; Schottky, standard rectifier or switching, Zener and TVS diodes are different types; N-channel and P-channel, NPN and PNP; GaN, SiC and silicon; fixed and adjustable regulators. The capacitor technology words are read narrowly: `polymer aluminium` excludes tantalum polymer parts, a bare `polymer` accepts both. The Zener voltage, a regulator's output voltage and a crystal's load capacitance are exact. For connectors the type, gender, positions and pitch are hard; for USB connectors the type, the stated pin configuration (normalised) and the standard (a higher one is accepted). Relaxable: dielectric, tolerance (looser), TCR, ESR and DCR preferences, connector orientation, and the package of inductors, crystals and oscillators.

Arrays and networks: a request for a single element excludes a bead array or resistor network. Write `array`, `network` or `4 lines` to ask for one (`parsed.elements`).

Form factor (passives): the words `heatsink`, `chassis`, `bolt` or `screw mount`, or `aluminium housed` ask for a chassis part, that is a chassis body or a power package such as SOT-227, TO-220 or TO-247 (`parsed.form_factor` is `chassis`). A package in the query names its class as well: SOT-227 is a power package, 0805 a chip, D2PAK a power SMD package. Chip resistors and leaded (axial, radial) bodies are excluded from a chassis or power-package request (key `form factor` in `excluded_by_constraints_detail`).

Packages are imperial, always. A bare four-digit chip code is the inch code (`0603` is imperial 0603, never metric 0603 = imperial 0201). A metric code counts only where the source labels it as millimetres (TME `Case - mm`, Mouser `(1608 metric)`, `3216M`, `0603mm`); it is converted to the imperial code (`1005` to `0402`, `1608` to `0603`, `2012` to `0805`, `3216` to `1206`, `3225` to `1210`, `4532` to `1812`, `5025` to `2010`, `6332` to `2512`, `0603` to `0201`, `0402` to `01005`). `parsed.package`, the `Package` attribute and the phrases sent to the distributors use the imperial code. Crystal and oscillator sizes (`3225`, `2520`) are their own codes. A can capacitor size (`6.3x5.4mm`, `D6.3xL5.4mm`) matches a part within 0.2 mm in diameter and length. A package string KINA cannot read never excludes a part.

When a distributor has nothing that satisfies the hard constraints after the relaxable steps, its `parts` list is empty, `exact_matches` is 0, `excluded_by_constraints` and `excluded_by_constraints_detail` count what was left out, and `hint` (per distributor and for the response) names the constraints that could not be met. No substitutes are returned.

### Relaxation

When a distributor has nothing that meets the request (every part is excluded by a hard constraint or a rating, or nothing is in stock), KINA first reads further pages of the same phrase (TME, up to `max-pages-per-search`; higher-rated parts often sit on later pages because the rating is not in the phrase), then relaxes the search in this order: the dielectric, then the package (inductors, crystals and oscillators only), then the tolerance. A rating and a hard constraint are never relaxed. Mouser and TME get these phrases (the relaxation ladder), stopping at the first one that finds a part that meets the request:

1. your text without ratings (normally already the `distributor_query`),
2. the minimal core: family word, values, technology, dielectric, package and tolerance (connectors: the type words plus the positions (TME, which loosens the orientation: `constraints_relaxed: ["orientation"]`) or plus pitch and orientation (Mouser); keyword-only queries: the 3 to 5 most informative words),
3. the core without the dielectric (and the technology, which stays a hard constraint): `constraints_relaxed: ["dielectric"]`,
4. for inductors, crystals and oscillators also without the package: `["dielectric", "package"]`,
5. also without the tolerance: `["dielectric", "package", "tolerance"]` (`["dielectric", "tolerance"]` when the package stays).

A step whose words equal what was already sent (in any order) is skipped. For `22uF X7R 1206 25V MLCC` at TME (verified 2026-10-06) the first phrase `22uF X7R 1206 MLCC` finds only 6.3 V to 16 V parts, so the next one is `MLCC 22uF 1206`, which finds 25 V X5R parts: `fallback_query: "MLCC 22uF 1206"`, `constraints_relaxed: ["dielectric"]`, `mismatches: ["dielectric: X5R instead of X7R"]`. When no step finds a part that meets the request, the least relaxed result that found parts is kept. LCSC relaxes inside its database search (keywords and features first, then the dielectric, the tolerance, the package, and only then a rating); a part it finds without a hard term is excluded by the ranker, and only relaxable constraints are reported in `constraints_relaxed`. A cached search that would return nothing (every part excluded) is not used: KINA searches live, and such a result is never stored as a reusable search.

### Quantity

`quantity` (default 1) is the number of pieces you want; pass it for BOM work. Parts with less stock rank below every part that can supply it (0.3 of score). A `low_stock` part (fewer than 10 pieces, or fewer than twice `quantity`) loses 0.3. A minimum order quantity above `quantity` loses up to 0.3, `0.3 * min(1, log10(moq / quantity) / 3)`, also for a quantity of 1: a 2000-piece MOQ for one piece loses all of it, an MOQ of 10 a third. These penalties (`kina.search.quantity.*`) are taken from the deterministic score and again from the final score, so the model cannot hide them. Each part gets `ordered_quantity`, `unit_price_at_quantity` and `total_price` when `quantity` is above 1.

### Connector queries

Write a connector request in plain words, for example `90 degree dupont style female pin header, THT, 6 position`. KINA extracts type, gender, positions, rows, pitch, orientation and mounting (`parsed.connector`), rewrites the request for each distributor (`distributor_query`) and ranks with connector features. For that request the phrases were:

| Distributor | `distributor_query` |
|---|---|
| LCSC | `"Female Header" 6P "Right Angle" 2.54mm` |
| TME | `pin strips female 6 angled` (TME phrases are cut at 40 characters) |
| MOUSER | `female header 6 pos right angle` |

LCSC searches the local JLCPCB database. A known connector type becomes a category filter, `THT` matches `Through Hole` or `Plugin`, and `SMD` or `SMT` match `Surface Mount`. If the first search finds nothing, KINA relaxes it step by step: all terms; terms that occur nowhere in the database removed; one term dropped at a time (least informative first); parametric terms only; an OR of all terms.

Ranking for connectors uses positions (0.30), gender (0.20), orientation (0.15), pitch (0.15, 2.54 mm equals 0.1"), type (0.10) and mounting (0.05). A wrong row count costs 0.10 and multi-row parts cost 0.08 when rows were not requested. Unknown attributes never lower a score.

Known limit: rows are not sent to Mouser and Mouser keyword search is loose, so a `2x3` request returns single-row parts there. TME and LCSC handle rows.

The cache key is your own query text, not the distributor phrase.

### USB connector queries

KINA reads USB wording in detail: type (Type-C or USB-C, Micro-B, Micro-AB, Mini-B, Type-A, Type-B, "USB 3.0 Micro-B"), gender (receptacle, socket, female versus plug, male), standard, Type-C pin configuration, mounting style (SMD, THT, hybrid, mid-mount, top-mount), orientation and features (power only, PD, waterproof or IPX7, board lock). Fields are in [`parsed.connector`](#parsedconnector).

Standards map to speed classes: USB 2.0 is 480 Mbps. USB 3.0, USB 3.1 Gen 1 and USB 3.2 Gen 1 are the same class (5 Gbps). USB 3.1 Gen 2 and USB 3.2 Gen 2 are 10 Gbps. USB 3.2 Gen 2x2 is 20 Gbps. USB4 is 40 Gbps.

**Pin-count normalisation.** Distributors sometimes count 1 or 2 shell or mounting pins, so a 16-pin Type-C can be listed as 17P or 18P. KINA keeps `Positions` as reported and derives `PinConfiguration` and `ShieldPinsCounted`. Mapping: 17 or 18 to 16, 25 or 26 to 24, 7 or 8 to 6; 14 stays 14. Ranking compares configurations on both sides. A 17P listing fully matches a 16-pin request, and a "17 pin" request matches 16-pin parts. No distributor lists a 17P or 18P Type-C part in the data seen on 2026-10-05, so this rule is covered by tests and by the "17 pin" request direction.

**Inference.** Without a stated pin count, USB 2.0 Type-C implies 16 pins and USB 3.x Type-C implies 24. The response then has `pin_configuration_implied: true`. On the part side, a Type-C with 12 to 16 pins is treated as USB 2.0 and one with 2 to 6 pins as power only, whatever the distributor label says. JLCPCB labels many 16P and 6P parts "USB 3.1".

Ranking for USB requests replaces the generic connector weights. Unknown attributes never lower a score.

| Signal | Weight |
|---|---|
| USB type | +0.30 match, -0.30 mismatch |
| Pin configuration | +0.20 match, -0.20 mismatch (half of that when only implied) |
| Standard | +0.20 same speed class, +0.10 higher class, -0.20 lower class or power-only part |
| Gender | +0.15 / -0.15 |
| Mounting style | +0.10 / -0.10 |
| Orientation | +0.05 / -0.05 |
| Features | +0.03 for each requested feature the part has (waterproof, board lock, power only) |

Scores cap at 1.0, so complete matches can tie. Tied parts keep the distributor's order.

Example phrases for `USB-C receptacle 16 pin SMD USB 2.0`:

| Distributor | `distributor_query` |
|---|---|
| LCSC | `"USB Connectors" Type-C 16P/17P/18P "USB 2.0" "Surface Mount"` |
| TME | `USB C socket SMT 2.0` |
| MOUSER | `USB type C receptacle SMD 2.0` |

LCSC groups the pin alternatives, so 17P and 18P listings are not excluded. TME and Mouser phrases leave out the pin count. The one exception is a power-only request, where the Mouser phrase adds `6 pos power only`.

Data quality differs by distributor. LCSC has description text only (mid-mount appears as "Recessed" or "Sink board", waterproofing only in part numbers). TME parameters are the richest: type, gender, pins, version, data rate, mounting variant, charging only, IP rating and hybrid. Mouser's search API returns no USB attributes, so everything comes from descriptions, and many Mouser descriptions give no pin count.

Checked live on 2026-10-05: `USB-C receptacle 17 pin` returns 16-pin Type-C parts at all three distributors (TME `USB4145-03-0170-C`, Mouser `217182-0001` and `DX07S016JA3R1500`).

### Rate limits and timing

When Mouser or TME rate limit a call, KINA waits and retries instead of failing at once.

- Triggers: HTTP 429; HTTP 502, 503 or 504 only when a `Retry-After` header is present; Mouser's in-body error code `TooManyRequests` on HTTP 200. A 503 without `Retry-After` is an outage and fails fast with `unavailable`. TME has no throttling error code, so only the statuses count.
- Wait: the `Retry-After` value (seconds or HTTP date, at least 1 s). Without it, 2, 4, 8, 16, 30, 30... seconds with 20 percent jitter, never below 1 s. KINA retries while the next wait fits inside the request deadline.
- Request deadline: `kina.search.max-request-duration`, default `2m`, for each incoming request. A batch shares one deadline for all its queries. The waits extend a distributor's 12 s work budget but never the deadline.
- Shared cool-down: after a rate limit, other calls to the same distributor wait for the cool-down to end if that fits their deadline. Otherwise they fail at once with `rate_limited`, without calling the distributor.
- Result: `rate_limit_waited_ms` in each distributor entry tells how long KINA waited. `error: "rate_limited"` means the limit outlasted the deadline. Parts fetched before that (earlier pages, a cached list) are still returned.
- Ranking runs after fetching (5 s per query, 60 s per batch). The worst case is therefore about 2 minutes plus ranking.
- Mouser quotas stay at 1 000 calls a day and 30 a minute. The retry helps with the per-minute limit, not with an exhausted daily quota.

Set the read timeout of your HTTP client or proxy above about 2.5 minutes for the search endpoints.

### `GET /api/v1/parts/search`

Search one query.

| Parameter | Required | Meaning |
|---|---|---|
| `q` | yes | Query text. Must not be blank. |
| `max_results` | no | Parts per distributor, 1 to 50. Default 10. Out of range gives 400. |
| `distributors` | no | `LCSC`, `TME`, `MOUSER`. Repeat the parameter or separate with commas. Default: all configured distributors. |
| `bypass_cache` | no | `true` skips the cache lookup; the cache is still refreshed. Default `false`. |
| `quantity` | no | Pieces to order, 1 to 10 000 000. Default 1. See [Quantity](#quantity). |
| `detail` | no | `compact` (default) or `full`. See `PartResponse`. |
| `allow_below_spec` | no | `true` returns parts whose known rating is below the request, flagged `below_spec` and listed last. Default `false`. See [Ratings](#ratings-are-hard-minimums). |

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://kina.example.com/api/v1/parts/search?q=10uF%20X7R%200805&max_results=5&distributors=LCSC,TME"
```

Returns a `SearchResponse` (see the example in the [README](../README.md#example)). With a rate-limited distributor the call can take up to 2 minutes plus ranking.

### `POST /api/v1/parts/search/batch`

Search 1 to 20 queries. `distributors`, `bypass_cache`, `detail` and `allow_below_spec` apply to every query; `max_results` and `quantity` are per query (a query may also set its own `allow_below_spec`).

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  https://kina.example.com/api/v1/parts/search/batch -d '{
    "queries": [
      {"query": "10uF X7R 0805", "max_results": 5, "quantity": 100},
      {"query": "4k7 1% 0603 resistor"}
    ],
    "distributors": ["LCSC", "MOUSER"],
    "bypass_cache": false,
    "detail": "compact"
  }'
```

Response: `{"results": [SearchResponse, ...]}` in request order. Queries are fetched in parallel (4 at a time) and ranked one after another within a 60 s budget; queries reached after the budget is spent return `"ranking": "fallback"` with `"ranking_note": "batch ranking budget of 60s exhausted"`. All queries share one 2-minute deadline for rate-limit waits, so the whole call can take about 2 minutes plus ranking.

### `GET /api/v1/parts/{distributor}/{partNumber}`

Get one part by distributor part number. The part number is the rest of the path, so TME symbols that contain `/` work unencoded.

| Parameter | Meaning |
|---|---|
| `bypass_cache` | Query the distributor live. Default `false`. |
| `quantity` | Pieces to order, for `ordered_quantity`, `unit_price_at_quantity` and `total_price`. Default 1. |
| `detail` | `full` (default: every attribute the distributor gives, canonical and raw, plus `photo_url` and `extra`) or `compact` (canonical attributes only). |

```bash
curl -s -H "Authorization: Bearer $TOKEN" https://kina.example.com/api/v1/parts/lcsc/C15850
```

Returns one `PartResponse` (without `rank`, `score` and `match`, prices trimmed to 3 brackets). The part number can also be the manufacturer part number; spelling differences in hyphens and spaces are ignored (`ERA6AEB5361V` finds Mouser `667-ERA-6AEB5361V`; `HCMA0703 2R2 R` is sent as `HCMA0703-2R2-R`, then `HCMA07032R2R`, and a part number TME refuses as invalid input is `not_found`). A part the distributor lists without ships-now stock is returned too, with `stock` 0 and `availability.status` `out_of_stock` (you asked for this part explicitly). It returns 404 when the part is not available: the problem has `reason` `not_found` (the distributor does not know it) or `out_of_stock` (listed without stock, but the distributor gives only its identity, for example a Mouser catalogue part without a Mouser number; `identity` then gives `part_number`, `manufacturer`, `mpn` and `description`). Lookup failures return 503, 429, 504 or 502 (see below). On a rate limit the call waits and retries for up to 2 minutes; 429 means the limit outlasted that. The response has no `rate_limit_waited_ms`.

### `GET /api/v1/distributors`

State of each distributor. It never calls the Mouser or TME APIs. Same payload as the `list_distributors` MCP tool.

```bash
curl -s -H "Authorization: Bearer $TOKEN" https://kina.example.com/api/v1/distributors
```

```json
{
  "distributors": [
    {
      "distributor": "LCSC", "configured": true, "available": true,
      "detail": "...", "uses_cache": false, "max_results_per_search": 200,
      "jlcpcb": {"available": true, "library": "parts-fts5.db", "downloaded_at": "2026-10-05T08:00:00Z",
                 "source_date": "...", "part_count": 0, "downloading": false, "last_error": null}
    },
    {"distributor": "TME", "configured": true, "available": true, "detail": "...", "uses_cache": true,
     "cached_parts": 0, "max_results_per_search": 60},
    {"distributor": "MOUSER", "configured": true, "available": true, "detail": "...", "uses_cache": true,
     "cached_parts": 0, "max_results_per_search": 50}
  ],
  "cache": {"ttl": "PT72H", "parts": 0, "fresh_parts": 0, "searches": 0, "oldest_fetch": null},
  "ranking": {"mode": "blended", "cross_encoder_enabled": true, "ready": true,
              "model": "cross-encoder/ms-marco-MiniLM-L6-v2", "model_variant": "int8",
              "model_revision": "<hugging face commit>", "model_dir": "/opt/kina/cross-encoder",
              "threads": 4, "avg_latency_ms": 180.0, "last_error": null,
              "max_candidates": 40, "weight": 0.5, "timeout": "PT5S"},
  "metrics": {"searches": 120, "search_queries": 310,
              "tool_calls": {"get_part": 12, "list_distributors": 3, "search_parts": 90, "search_parts_batch": 10},
              "cache_added": {"MOUSER": 900, "TME": 1400}, "rate_limited_calls": {"MOUSER": 2},
              "cross_encoder_executions": 300,
              "search_queries_by_type": {"capacitor": 140, "connector": 40, "resistor": 95, "unknown": 35}}
}
```

The `metrics` object holds usage counters since the first start against this database (they survive restarts):

| Field | Meaning |
|---|---|
| `searches`, `search_queries` | Search requests (a batch counts once) and queries (every query of a batch). |
| `tool_calls` | MCP tool calls per tool. |
| `cache_added` | New rows in the part cache per distributor. |
| `rate_limited_calls` | Distributor HTTP calls answered with a rate limit, per distributor (retried or not). |
| `cross_encoder_executions` | Runs of the ranking model. |
| `search_queries_by_type` | Search queries per component type, sorted: the family the parser recognised (`capacitor`, `resistor`, `mosfet`, `connector`...; the full list is in [DESIGN.md 3.7](DESIGN.md#37-observability)) or `unknown`. Queries counted before 0.5 are under `unknown`. |

The `ranking` object:

| Field | Meaning |
|---|---|
| `mode` | `blended` when the cross-encoder is enabled and loaded, else `fallback`. |
| `cross_encoder_enabled` | `kina.ranking.cross-encoder.enabled`. |
| `ready` | The model is loaded and warmed up. |
| `model`, `model_variant`, `model_revision`, `model_dir` | Model name (the Hugging Face repository, also for the model bundled in the image; else the configured URL or path), `int8` or `fp32`, source revision (Hugging Face commit, null when unknown) and directory (`/opt/kina/cross-encoder` in Docker). |
| `threads` | ONNX Runtime threads used for scoring. |
| `avg_latency_ms` | Mean scoring time per query since start. Null before the first scored query. |
| `last_error` | Why the model is not loaded. Null when fine. |
| `max_candidates`, `weight`, `timeout` | Candidates scored per query (40), weight of the model in the blend (0.5) and ranking budget per query (ISO-8601 duration). |

Numbers above are placeholders. `available` for TME and Mouser means "configured"; there is no live probe. `cache` is null when the database cannot be read. Null fields in a distributor entry are omitted.

### `GET /api/v1/metrics/summary`

The usage counters as JSON, for clients without Prometheus. Needs a bearer token like every `/api` endpoint. The Prometheus format is on the management port (`/actuator/prometheus`, port 9090, no authentication; see [OPERATIONS.md](OPERATIONS.md#monitoring)).

```bash
curl -s -H "Authorization: Bearer $TOKEN" https://kina.example.com/api/v1/metrics/summary
```

```json
{
  "summary": {"searches": 120, "search_queries": 310, "tool_calls": {"search_parts": 90},
              "cache_added": {"MOUSER": 900, "TME": 1400}, "rate_limited_calls": {"MOUSER": 2},
              "cross_encoder_executions": 300, "search_queries_by_type": {"resistor": 95, "unknown": 35}},
  "counters": [
    {"name": "kina_distributor_calls_total", "tags": {"distributor": "MOUSER", "outcome": "ok", "type": "resistor"},
     "value": 85.0},
    {"name": "kina_search_duration_seconds_count", "tags": {}, "value": 120.0},
    {"name": "kina_search_duration_seconds_sum", "tags": {}, "value": 96.4},
    {"name": "kina_searches_total", "tags": {}, "value": 120.0}
  ]
}
```

`summary` is the `metrics` object of `GET /api/v1/distributors`. `counters` lists every counter and timer in Prometheus naming, sorted by name and tags (timer sums in seconds). The search counters (`kina_search_queries_total`, `kina_distributor_calls_total`, `kina_parts_fetched_total`, `kina_parts_returned_total`, `kina_cache_search_lookups_total`) carry a `type` tag, the component type of the query. Gauges such as the cache size are only in the Prometheus output. Metric names and tags: [DESIGN.md 3.7](DESIGN.md#37-observability).

### Errors

REST errors are RFC 9457 `application/problem+json`. They never contain stack traces. Types are `urn:kina:problem:<name>`.

| Status | `type` | When |
|---|---|---|
| 400 | `urn:kina:problem:validation` | Blank `q`, `max_results` out of range, bad batch body, malformed JSON. Body and parameter validation add `errors: [{"field": "...", "message": "..."}]`. |
| 400 | `urn:kina:problem:unknown-distributor` | A distributor name other than LCSC, TME, MOUSER. |
| 401 | `about:blank` | Missing or invalid token (see Authentication). |
| 404 | `urn:kina:problem:not-found` | Unknown part, or a part listed without stock of which the distributor gives only the identity. Adds `distributor`, `part_number`, `reason` (`not_found` or `out_of_stock`) and, for `out_of_stock`, `identity`. |
| 429, 502, 503, 504 | `urn:kina:problem:distributor-error` | Single-part lookup failed: 503 for `not_configured` and `unavailable`, 429 for `rate_limited` (only after the 2-minute retry budget), 504 for `timeout`, 502 for `bad_response`. Adds `distributor` and `error`. |
| 500 | `urn:kina:problem:internal` | Anything else. |

Search endpoints do not return distributor errors as HTTP errors; they put the code in the `error` field of the distributor entry. A rate limit is retried first; `error: "rate_limited"` appears only after the retry budget is spent.

Example:

```json
{
  "type": "urn:kina:problem:validation",
  "title": "Invalid request",
  "status": 400,
  "detail": "Request parameter validation failed.",
  "instance": "/api/v1/parts/search",
  "errors": [{"field": "max_results", "message": "must be less than or equal to 50"}]
}
```

## MCP

- Endpoint: `POST /mcp`, JSON-RPC 2.0 over Streamable HTTP, stateless: no `initialize` handshake or session is required.
- Send `Content-Type: application/json` and `Accept: application/json, text/event-stream`.
- Server name `kina`; version comes from the build.
- Tool results are JSON, returned as text content.

```bash
curl -s -X POST https://kina.example.com/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_parts","arguments":{"query":"10uF X7R 0805","max_results":5}}}'
```

List the tools with `{"jsonrpc":"2.0","id":1,"method":"tools/list"}`. The authoritative schemas are generated from the Java method parameters (parameter names are the argument names). They look like this:

### `search_parts`

Search electronic components across distributors and return ranked, in-stock offers. The tool description is a summary of about 2,500 characters (it is sent to the model on every connection) and ends with "Field reference: docs/API.md in the KINA repository": this document is that reference. Only stock that ships now is returned, except a part the query names by its part number (see [Part numbers in a query](#part-numbers-in-a-query)): when the distributor lists it without stock it is still returned, last, with `stock` 0 and `availability.status` `out_of_stock`. Results are cached for 3 days (a search that found nothing, for only 1 hour); calling again with a larger `max_results` is served from the cache. Component data stays cached after that; stock and prices older than 24 hours are refreshed before they are returned, and older than 3 days without a refresh they are flagged `stale`.

```json
{
  "type": "object",
  "properties": {
    "query": {"type": "string", "description": "Component description or part number, e.g. \"10uF X7R 0805\", \"100nF 50V C0G 0603\", \"2N7002 SOT-23\"."},
    "max_results": {"type": "integer", "description": "Maximum number of parts returned PER DISTRIBUTOR (1-50, default 10)."},
    "distributors": {"type": "array", "items": {"type": "string"}, "description": "Any of \"LCSC\", \"TME\", \"MOUSER\" (case-insensitive). Default: all configured distributors."},
    "bypass_cache": {"type": "boolean", "description": "Default false. true queries the distributors live for fresh stock and prices. Mouser has a small daily quota, so use it only when fresh data matters. No effect on LCSC."},
    "quantity": {"type": "integer", "description": "Pieces to order (default 1). Ranks by whether stock and minimum order fit, and adds ordered_quantity, unit_price_at_quantity and total_price to each part."},
    "detail": {"type": "string", "description": "\"compact\" (default) or \"full\" (adds score, category, package, photo_url, the raw distributor attributes and extra fields)."},
    "allow_below_spec": {"type": "boolean", "description": "Default false. true also returns parts whose known rating is below the request, flagged below_spec: true and listed after every compliant part, closest first. Use it only when no compliant part exists."}
  },
  "required": ["query"]
}
```

Returns a `SearchResponse`. If a distributor is rate limited the call can take up to 2 minutes plus ranking; see [Rate limits and timing](#rate-limits-and-timing). The MCP server request timeout is `3m`; set client timeouts above 2.5 minutes.

### `search_parts_batch`

Run 1 to 20 searches at once, for example every line of a BOM (give each line its `quantity`). Same semantics and result shape as `search_parts`. `distributors`, `bypass_cache`, `detail` and `allow_below_spec` apply to every query; each query has its own `max_results` and `quantity`.

```json
{
  "type": "object",
  "properties": {
    "queries": {
      "type": "array",
      "description": "1-20 searches, each {\"query\": \"...\", \"max_results\": 10, \"quantity\": 1}; max_results is per distributor (1-50, default 10), quantity the pieces to order (default 1).",
      "items": {
        "type": "object",
        "properties": {
          "query": {"type": "string", "description": "Component description or part number."},
          "max_results": {"type": "integer", "description": "Parts per distributor, 1-50, default 10."},
          "quantity": {"type": "integer", "description": "Pieces to order, default 1."}
        }
      }
    },
    "distributors": {"type": "array", "items": {"type": "string"}},
    "bypass_cache": {"type": "boolean"},
    "detail": {"type": "string"},
    "allow_below_spec": {"type": "boolean"}
  },
  "required": ["queries"]
}
```

Returns `{"results": [SearchResponse, ...]}` in request order. An empty or missing `queries` is an error. The whole batch shares one 2-minute deadline for rate-limit waits.

### `get_part`

Current details of one part by distributor part number (the `part_number` of a search result) or by manufacturer part number. For a manufacturer part number, case, spaces and hyphens are ignored (`ERA6AEB5361V` finds Mouser `ERA-6AEB5361V`), and `part.part_number` is the distributor's own number (the top-level `part_number` echoes what you sent). `detail` defaults to `full` here: every attribute the distributor gives (the canonical keys such as `RippleCurrent`, `ESR`, `Impedance`, `Dimensions`, `Qualification`, `Features`, and the raw distributor attributes), `photo_url` and `extra`. Cached TME and Mouser stock and prices older than 24 hours are refreshed first (`stock_as_of`); a part that sold out meanwhile is looked up live and reported as `out_of_stock` (KINA keeps its component data).

```json
{
  "type": "object",
  "properties": {
    "distributor": {"type": "string", "description": "\"LCSC\", \"TME\" or \"MOUSER\" (case-insensitive)."},
    "part_number": {"type": "string", "description": "Distributor part number, or the manufacturer part number."},
    "bypass_cache": {"type": "boolean"},
    "quantity": {"type": "integer"},
    "detail": {"type": "string"}
  },
  "required": ["distributor", "part_number"]
}
```

Returns:

```json
{"found": true, "distributor": "LCSC", "part_number": "C15850", "cache": "not_applicable", "error": null, "reason": null, "part": {"...": "PartResponse"}}
```

A part the distributor lists without ships-now stock is returned (you asked for it explicitly): `found: true`, `reason: "out_of_stock"`, `identity`, and `part` with `stock` 0, the prices as listed and `availability.status` `out_of_stock`. KINA keeps its component data in the cache but never serves it to a search that does not name it.

`found` is false (and `part` null) in three cases. `reason: "not_found"`: the distributor does not know the part. `reason: "out_of_stock"` with only an `identity`: it lists the part without stock but gives no data for it (a Mouser catalogue part without a Mouser number):

```json
{"found": false, "distributor": "MOUSER", "part_number": "ERA6AEB5361V", "cache": "miss", "error": null, "reason": "out_of_stock",
 "identity": {"part_number": "667-ERA-6AEB5361V", "manufacturer": "Panasonic", "mpn": "ERA-6AEB5361V", "description": "Thin Film Resistors - SMD 0805 5.36Kohm 0.1% 25ppm"}, "part": null}
```

The lookup failed: `error` carries the failure code and `reason` is null. On a rate limit the call waits and retries for up to 2 minutes before it reports `rate_limited`. The response has no waited-time field. `cache` is `hit`, `miss`, `bypassed` or `not_applicable`. A cached TME or Mouser part whose stock and prices are older than 3 days and cannot be refreshed is looked up live; if that fails too (or the distributor is not configured), the cached part is returned with `stale: true`. An unknown distributor name is a tool error.

### `list_distributors`

No parameters. Returns the same payload as `GET /api/v1/distributors`: per-distributor state, cache statistics, ranking status and the usage counters (`metrics`). Does not call the Mouser or TME APIs.

### `ping`

No parameters. Returns `{"status": "ok", "version": "<build version>"}`.

## OAuth 2.1 authorization server

Used by Claude's remote connector and any other MCP client that supports OAuth. The access tokens it issues are KINA tokens like the web UI ones, but they live 1 hour (`expires_in` is 3600) and come with a 30-day refresh token. They appear in the web UI as `MCP: <client name>` and can be revoked there. Scope: `kina` (the only scope).

Flow: the client calls `/mcp`, gets 401 with `resource_metadata`, reads the metadata documents, identifies itself (an `https` `client_id` URL, or a registration at `/oauth/register`), sends the user to `/oauth/authorize` (PKCE `S256`), receives a code on its redirect URI and exchanges it at `/oauth/token`.

In `prod`, the user signs in at the organisation's OIDC provider. With required groups configured, a user outside the groups ends on `GET /login-denied` (see [Web UI endpoints](#web-ui-endpoints)) and no code is issued.

### Discovery documents

`GET /.well-known/oauth-protected-resource` and `GET /.well-known/oauth-protected-resource/mcp` (RFC 9728):

```json
{
  "resource": "https://kina.example.com/mcp",
  "authorization_servers": ["https://kina.example.com"],
  "bearer_methods_supported": ["header"],
  "scopes_supported": ["kina"],
  "resource_name": "KINA"
}
```

`GET /.well-known/oauth-authorization-server`, `GET /.well-known/oauth-authorization-server/mcp` and `GET /.well-known/openid-configuration` (RFC 8414; same document at all three):

```json
{
  "issuer": "https://kina.example.com",
  "authorization_endpoint": "https://kina.example.com/oauth/authorize",
  "token_endpoint": "https://kina.example.com/oauth/token",
  "registration_endpoint": "https://kina.example.com/oauth/register",
  "revocation_endpoint": "https://kina.example.com/oauth/revoke",
  "response_types_supported": ["code"],
  "response_modes_supported": ["query"],
  "grant_types_supported": ["authorization_code", "refresh_token"],
  "code_challenge_methods_supported": ["S256"],
  "token_endpoint_auth_methods_supported": ["none", "client_secret_basic", "client_secret_post"],
  "revocation_endpoint_auth_methods_supported": ["none", "client_secret_basic", "client_secret_post"],
  "scopes_supported": ["kina"],
  "client_id_metadata_document_supported": true
}
```

`client_id_metadata_document_supported` is `true` unless `kina.oauth.trusted-client-metadata-hosts` is empty. See [Client ID Metadata Documents](#client-id-metadata-documents).

The origin comes from `KINA_PUBLIC_BASE_URL` or, when empty, from the `X-Forwarded-*` headers of the request.

### `POST /oauth/register`

Dynamic client registration (RFC 7591), anonymous, JSON body.

| Field | Meaning |
|---|---|
| `redirect_uris` | Required, 1 to 20 absolute URIs without fragment. `https`, `http` for `localhost`, `127.0.0.1` or `[::1]` (any port), or a custom scheme. `javascript`, `data`, `file`, `vbscript`, `about`, `blob`, `ftp`, `ws`, `wss` are rejected. |
| `client_name` | Optional, up to 200 characters. Shown on the consent page. |
| `token_endpoint_auth_method` | `none` (default), `client_secret_basic` or `client_secret_post`. |
| `grant_types` | Default `["authorization_code", "refresh_token"]`; must include `authorization_code`. |
| `response_types` | Only `["code"]`. |
| `scope` | Optional. |

```bash
curl -s -X POST https://kina.example.com/oauth/register -H 'Content-Type: application/json' \
  -d '{"client_name":"My client","redirect_uris":["http://localhost:8765/callback"],"token_endpoint_auth_method":"none"}'
```

Returns 201 with `client_id`, `client_secret` (only when the method is not `none`; it is shown once and stored hashed), `client_id_issued_at`, `client_secret_expires_at` (always 0) and the registered metadata. Errors are `invalid_redirect_uri` or `invalid_client_metadata` with status 400.

The endpoint is rate limited per client IP (`kina.oauth.register-rate-limit-per-minute`, default 30, `0` disables). Over the limit KINA answers `429` with a `Retry-After` header (seconds) and the body `{"error": "too_many_requests", "error_description": "..."}`. The client IP is the one the reverse proxy reports in `X-Forwarded-For`, so the proxy must overwrite that header.

Dynamically registered clients that were not used for 90 days and hold no live token are deleted by a daily cleanup. `oauth_clients.last_used_at` records each token issuance.

### Client ID Metadata Documents

Instead of registering, a client may use an `https` URL as its `client_id`. The URL points to a JSON document that describes the client (draft-ietf-oauth-client-id-metadata-document; Claude Code uses `https://claude.ai/oauth/claude-code-client-metadata`). KINA accepts this only for hosts in `kina.oauth.trusted-client-metadata-hosts` (default `claude.ai`, `claude.com`, `*.anthropic.com`).

- KINA fetches the document with a 5 second timeout, a 1 MB cap and no redirects, and caches it for 1 hour (`kina.oauth.client-metadata-cache`). The client is stored in `oauth_clients` with `metadata_url`.
- The document's `client_id` must equal the URL. `redirect_uris` must be valid. `token_endpoint_auth_method` must be absent or `none`.
- `redirect_uri` must match an entry of the document exactly. A loopback `http` entry (`http://localhost/callback`) accepts any port.
- An unknown host or an invalid document gives an error page at `/oauth/authorize` (never a redirect) and `invalid_client` at `/oauth/token`.
- Consent is skipped only for trusted documents with a non-loopback redirect URI (for example claude.ai). Loopback redirects (Claude Code) still show the Approve page.

### `GET /oauth/authorize`

Requires a signed-in user (`dev`: automatic; `prod`: OIDC login, then the request resumes). Parameters:

| Parameter | Meaning |
|---|---|
| `response_type` | Must be `code`. |
| `client_id` | A registered client, or an `https` URL of a Client ID Metadata Document on a trusted host. |
| `redirect_uri` | Exact match with a registered URI (or with an entry of the metadata document; loopback `http` entries accept any port). May be omitted when the client registered exactly one. |
| `code_challenge`, `code_challenge_method` | Required. Method must be `S256`. |
| `state` | Echoed back on the redirect. |
| `scope` | Accepted; the granted scope is always `kina`. |
| `resource` | Stored and echoed. A value outside the public origin is logged, not rejected. |

In `prod`, the user is sent to the OIDC provider first. With required groups, a refused user sees `/login-denied` and the request does not continue. It renders a consent page (Approve and Deny buttons, `POST /oauth/authorize`); trusted metadata-document clients with a non-loopback redirect URI skip it. Approve redirects to `redirect_uri?code=...&state=...` with a single-use code valid for 10 minutes. Deny redirects with `error=access_denied`. An unknown client, an untrusted or invalid metadata document, or an unregistered `redirect_uri` shows an error page with status 400 and never redirects. Other request problems redirect to the client with `error` and `error_description`.

### `POST /oauth/token`

Form-encoded (`application/x-www-form-urlencoded`). Clients registered with a secret authenticate with HTTP Basic or `client_secret` in the form; public clients (and metadata-document clients) send only `client_id`, which may be the `https` URL.

Authorization code grant:

```bash
curl -s -X POST https://kina.example.com/oauth/token \
  -d grant_type=authorization_code -d client_id=<client_id> -d code=<code> \
  -d redirect_uri=<redirect_uri> -d code_verifier=<verifier>
```

Refresh token grant (rotation: the old refresh token and its access token are revoked, a new pair is issued):

```bash
curl -s -X POST https://kina.example.com/oauth/token \
  -d grant_type=refresh_token -d client_id=<client_id> -d refresh_token=<refresh_token>
```

Response:

```json
{"access_token": "kina_...", "token_type": "Bearer", "expires_in": 3600, "refresh_token": "kina_rt_...", "scope": "kina"}
```

The code is single use: the first attempt consumes it, even if PKCE verification then fails. **Refresh semantics.** In `prod` with required groups, a refresh grant first re-checks the user's group membership at the identity provider when the last check is older than `KINA_MEMBERSHIP_RECHECK_INTERVAL` (1 hour). The check is synchronous:

- Still a member: the old pair is rotated and a new pair is issued.
- No longer a member, or the provider reports the grant as invalid: `invalid_grant`. The user is blocked and all their tokens are revoked.
- Provider unreachable: access continues for `KINA_MEMBERSHIP_GRACE` (4 hours) after the last successful check. After that the answer is `invalid_grant` ("identity provider unreachable"). Nothing is revoked, and the same refresh token works again when the provider is back.
- No stored provider token (no `KINA_TOKEN_ENCRYPTION_KEY`): refresh works for 24 hours after the user's last interactive login, then `invalid_grant` ("sign in again").

Claude reacts to `invalid_grant` by asking the user to reconnect.

Errors are `{"error": "...", "error_description": "..."}`: `invalid_request`, `invalid_client` (401), `invalid_grant`, `unauthorized_client`, `unsupported_grant_type`, `invalid_scope`. `expires_in` is the real lifetime of the access token (`kina.oauth.access-token-validity`, default 1 hour). Refresh tokens last 30 days (`kina.oauth.refresh-token-validity`) and rotate on every use. Revoking an access token, in the web UI or at `/oauth/revoke`, also revokes the refresh tokens issued with it.

### `POST /oauth/revoke`

RFC 7009. Form-encoded `token=<access or refresh token>`, with the same client authentication as the token endpoint. A refresh token is revoked together with its access token, and an access token together with its refresh tokens. Unknown tokens and tokens of other clients are ignored. The answer is always 200 once the client is authenticated.

## Web UI endpoints

These need a signed-in user (`prod`: OIDC session, `dev`: automatic). The POST forms are CSRF-protected; the search form is a plain GET.

| Endpoint | Purpose |
|---|---|
| `GET /` | The Search tab (also `/search`): a form that runs a part search (`q`, `distributors`, `max_results`, `quantity`, `detail`, `bypass_cache`, `allow_below_spec`, as in `GET /api/v1/parts/search`) and shows the results as a page. |
| `GET /connect` | The MCP tab: how to connect Claude, and your tokens. When `kina.tokens.ui-enabled` is `false` it shows only the explanation. |
| `GET /status` | The Status tab: version, distributors, ranking, cache (also by component type), counters and users. |
| `POST /tokens` | Create a static token (`name`, up to 100 characters). The plaintext is shown once. Returns `404` when `kina.tokens.ui-enabled` (`KINA_TOKENS_UI_ENABLED`) is `false`; existing tokens keep working until they expire or are revoked. |
| `POST /tokens/{id}/revoke` | Revoke one of your tokens. For an OAuth token this also revokes its refresh tokens. |
| `GET`/`POST /oauth/authorize` | Consent page and decision. |
| `GET /login-denied` | Shown after a login that the group or e-mail policy refused. Status `403`. Query `reason`: `email_missing` (the provider sent no e-mail address), `email_unverified`, `email_domain` or `group` (`email` is the older, general form). The page has one sentence for the reason and names the required groups or the allowed domains. The refused domain, and the note that earlier tokens no longer work, come only from the browser session of that login and are shown once. The user gets no session. Public. |
