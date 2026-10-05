# KINA ranking evaluation set (2026-10-05)

`ranking-eval.jsonl` is a labelled set for evaluating how KINA ranks candidate parts against a free-text request.
It was built by `scripts/research/build_dataset.py` from the rubrics in `scripts/research/queries.py`; rerun that
script to rebuild it bit for bit from the raw sources in `raw/`.

## Contents

- 32 queries, 1259 candidates (20 to 40 per query).
- Categories: 10 passive (MLCC, chip resistors incl. RKM notation `4k7`, `10R`, `2R2`, power inductor, ferrite bead,
  electrolytic), 6 discrete (Schottky, TVS, N-MOSFET, 1N4148W, MMBT3904, Zener), 7 IC (AMS1117-3.3, LDO, STM32F103,
  LM358, rail-to-rail op amp, RS-485 transceiver, CH340C), 4 crystal/connector (16 MHz crystal, 32.768 kHz crystal,
  USB-C receptacle, 2.54 mm header) and 5 vague natural-language requests (low-noise audio op amp, decoupling cap for a
  3.3 V MCU, MOSFET for a 12 V LED strip from a 3.3 V GPIO, I2C pull-up, USB 2.0 ESD protection).
- Labels: 0 = 166, 1 = 342, 2 = 292, 3 = 459 candidates.
- Distributors: LCSC 1004, Mouser 170, TME 85 candidates.

## Candidate sources

| source | what | count |
|---|---|---|
| `stack:LCSC/TME/MOUSER` | the running KINA stack (`/api/v1/parts/search?max_results=50`), 15 stack queries (13 new, 2 already cached); raw responses in `raw/stack/` | 413 |
| `native:LCSC` | KINA's own LCSC retrieval for the query text (`JlcpcbSqliteSearch` via `ResearchRunner lcsc`, full 7.1 M part JLCPCB database) | 289 |
| `mined:LCSC` | KINA's LCSC retrieval for perturbed queries (other dielectric, package, value, rating, polarity; neighbouring families; unrelated parts), top 6 each | 557 |

`raw/lcsc.jsonl` caches every LCSC retrieval so the set can be rebuilt without the 5 GB database. Stack queries whose
text differs from the evaluation query (`p01`: candidates from the stack query `10uF X7R 0805`) are marked in
`source_query`. KINA scores in the raw stack responses are never read by the builder.

Sampling: when a query's pool exceeded 40 candidates, up to 10 per label level were kept, natives first (interleaved
across distributors in their own order), then mined ones (shuffled, seed 20261005), then filled to 40. Sampling uses the
labels only, never a method's score.

## Record schema (one line per query)

```json
{"id": "p01", "query": "10uF 25V X7R 0805 MLCC", "category": "passive", "rubric": "...",
 "candidates": [{"key": "LCSC:C326595", "label": 2, "label_reason": "one violation: voltage>=25",
                 "source": "stack:LCSC", "source_query": "10uF X7R 0805", "source_rank": 3, "dist_rank": 5,
                 "part": {"distributor": "LCSC", "distributorPartNumber": "C326595", "manufacturer": "YAGEO",
                          "manufacturerPartNumber": "CC0805KKX7R7BB106", "description": "10uF 16V X7R ±10%",
                          "category": "...", "packageName": "0805", "stock": 260943, "prices": [...],
                          "attributes": {...}, "extra": {...}}}]}
```

`part` is the `ro.alacrity.kina.domain.Part` JSON (camelCase) minus URLs, with at most the 3 smallest price breaks, so
it can be fed straight to Java code (`ResearchRunner` deserialises it into `Part`). `attributes` is as the distributor
(or KINA's enrichment for stack parts) delivered it. `source_rank` is the position in the source's own result list,
`dist_rank` the position in the "distributor order" baseline (natives by source rank, interleaved across distributors;
mined candidates appended).

## Relevance scale and rubric

The scale is shared by every query; each query has its own written rubric (`rubric` field) that instantiates it.

| label | meaning |
|---|---|
| 3 | satisfies every requirement stated in the request (vague requests: the written interpretation) |
| 2 | right component type and primary value/function; exactly one secondary requirement violated, or one or more requirements not verifiable from the part data |
| 1 | right broad family but wrong primary value, or two or more secondary violations, or a near family (tantalum for an MLCC request, resistor network for a chip resistor, another diode type, PNP for an NPN request, comparator for an op amp) |
| 0 | different component type, unrelated |

Objective rules used throughout:

- Values match within 0.5 % (so E96 neighbours such as 4.75 k for 4k7 are wrong values); ratings must be at least
  the requested value (voltage, current), tolerance at most the requested one; regulator output and Zener voltage
  must match within 2 %; C0G and NP0 are the same dielectric.
- A requirement counts as verifiable when it is stated in the description, the package field, a distributor attribute,
  an imperial chip code embedded in the MPN (`CRCW0603...`), or an SMAJ/SMBJ series name. Other MPN decoding was not
  used for automatic labels; the few hand corrections that rely on it say so.
- MPN-anchored queries (`1N4148W SOD-123`, `MMBT3904`, `AMS1117-3.3`, `STM32F103 LQFP-48`, `LM358 SOIC-8`, `CH340C`)
  use explicit tiers in their rubric (exact part, pin-compatible or equivalent part, same family other variant, other).
- Vague queries: the rubric was written before any method was scored and is stored verbatim in the record. Two of them
  rely on curated part lists stated in `queries.py` (low-noise op amps with input noise at or below 10 nV/rtHz from
  their datasheets; low-capacitance ESD arrays).

Labelling procedure: rubric functions in `queries.py` (deliberately independent of KINA's Java recognisers so the labels
do not inherit their parsing errors) produced a label and a reason for every candidate; every label was then reviewed by
hand and 10 corrections were added to `OVERRIDES` in `queries.py` (9 of them land in the sampled set; reasons start with
`override:`), mostly garbled distributor descriptions and parts whose type is only evident from the MPN.

## Known limitations

- 32 queries is enough to separate large effects (Laya zero-shot vs deterministic) but not small ones: paired bootstrap
  confidence intervals in the report are about +-0.02 to +-0.05 NDCG@10.
- LCSC dominates (80 % of candidates) because the JLCPCB database is free to mine and Mouser calls were capped at 15.
- Mouser descriptions often omit the package; such candidates are labelled 2 ("not verifiable") even when the MPN
  encodes the right package. This penalises nothing in particular, but it is a ceiling on how well any ranker can do.
- The rubric functions share their author with the deterministic ranker's design brief; the hand review and the
  independent implementation reduce but do not remove that bias toward parametric rules.
- No secrets: the raw stack responses contain only public catalogue data returned by KINA's API.
