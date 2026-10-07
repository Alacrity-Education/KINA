# Review: second declarative pass (2026-10-07)

This review reads the seven candidates of `declarative-opportunities.md` against the code at `50ff303`: after the
`ConstraintKind` refactor (`@Relax`, `@Match`, `@Overshoot`), the split of `PartSearchService` into retrieval, ranking,
stock refresh and assembly stages, and the GaN fixes (the `gate driver` family, `PartNumbers`, `RequestedLookup`).
Each item is weighed by what a reader gains against the churn and the risk. Every change must keep
`ConstraintGoldenTest` unchanged.

## Summary

| Item | Decision | Why, in one line |
|---|---|---|
| 1. Component family traits | implement | Nine scattered sets and a switch become one table. |
| 2. Attribute aliases and units on `ConstraintKind` | defer | The alias lists are not a per-kind table; they encode precedence. |
| 3. Distributor dialect | defer | The retriever split already removed the branches a dialect would remove. |
| 4. `@Detail(FULL)` and a projector | drop | Five plain ternaries read better than a reflective projector. |
| 5. `Metric` enum | implement | Names, help texts and tag keys in one place; the docs table can be checked. |
| 6. Recognisers per family | drop (trait part done in 1) | The patterns are vocabulary, not family traits. |
| 7. Derive attributes at read time | implement | Fixes stale `detail=full` values of cached parts; old rows stay readable. |
| New: shared accessors in `ConstraintKind` | implement | Removes repeated null-guard lambdas and three copies of one override. |

## 1. Component family traits: implement

Families are strings, and their traits live in nine places: `PassiveDetails.ARRAY_FAMILIES` and `PASSIVE_FAMILIES`,
`ParametricExtractor.CHIP_FAMILIES`, `FREQUENCY_FAMILIES` and `LARGEST_VOLTAGE_FAMILIES`, `FormFactor.FAMILIES`,
`Recognizers.FAMILY_PARENT`, `Recognizers.inductive` and `frequencyFamily`, `ComponentTypes.polarised`,
`ConstraintKind.DIODE_KINDS`, the frequency test in `ConstraintKind.primaryKind`, the `LOW_RDS_ON` family test, and
the switch in `PolicyFamily.of`. Three of the sets are the same set under three names. A reader who adds a family
today must find all of them.

A `ComponentFamily` enum in `domain` declares, per family, its wire name, its parent, its policy family and its
traits (passive, arrays, inductive, frequency valued, largest voltage, polarised). The sets and the switch become
calls on the enum. `MatchContext.parentFamily` and `arrayFamily` go away, because `domain` can now answer them
itself. `PolicyFamily` becomes an enum too, so `@Relax(families = ...)` and `@Overshoot(families = ...)` are
type-checked; the wildcard `Relax.ALL` becomes `allFamilies = true`. The wire and configuration names stay the same
strings (`ConstraintPolicy.table()`, `DEFAULT_HARD`, `policyFamily(query)` keep their string keys), so the golden
dump does not change.

The family words stay in `Recognizers`. They carry a priority and a keyword flag per word group, and one family has
several groups (`mlcc` is a capacitor word kept as a keyword). Moving that table onto the enum would make the enum
harder to read, not easier. A test now checks that the families `Recognizers` can yield are exactly the enum's.

Found on the way: DESIGN.md 3.7 lists the values of the metrics `type` tag without `gate driver`, which the parser
can yield since the GaN fixes. The list is corrected in the same commit.

## 2. Attribute aliases and units on `ConstraintKind`: defer

The note pictured one alias list per kind, consumed by one generic loop. The code does not have that shape. The
`ConstraintKind` constants and the extracted attributes do not map one to one: `VOLTAGE_RATING` and `EXACT_VOLTAGE`
share the voltage attribute; `RATED_CURRENT`, `SATURATION_CURRENT` and the DCR lists only apply to inductive
families; the frequency has no kind of its own (it is the primary `VALUE`). About half of the 40 lists feed custom
logic, not `attributeValue`: connector strings, `firstInt`, the impedance test frequency, the lifetime with its
temperature, the temperature range. The order of the calls in `ParametricExtractor.features` is the precedence (the
output voltage before the voltage rating for regulators, the Zener voltage for Zeners, the fallback scans for any
`voltage` or `current` key). An annotation would hide that order in a table and add distributor vocabulary to
`domain`. The unit switch in `Recognizers` is six lines, with RKM and family rules next to it.

Item 7 does not need this item: deriving at read time calls the existing extractor.

## 3. Distributor dialect: defer

The note counted 21 branches. After the split, the caching rule is a strategy already (`LcscRetriever` against
`CachedDistributorRetriever`). What is left is in `DistributorPhraser` (the per-distributor wording of connector,
USB and fallback phrases, and the TME 40-character limit), `TechnologyVocabulary` (one spelling map per distributor)
and two three-line switches over configuration in `DistributorRetriever`. The phraser branches are the vocabulary
itself; three dialect classes would spread one 800-line module over four files without removing any rule. A dialect
is worth it when a fourth distributor is added.

## 4. Response detail levels: drop

`PartResponse.of` has five fields that only `full` carries (`score`, `category`, `package`, `photo_url`, `extra`).
The other differences are not "null unless full": `attributes` switches between canonical and raw, and the order
pricing is shown for `full` or for a quantity above 1. A `@Detail(FULL)` projector would need reflection over the
record and special cases for those two. Five ternaries in one builder are easier to read.

## 5. Metrics: implement

`MetricNames` keeps 35 name constants and a parallel 35-entry help map, and the tag keys of each series exist only as
string literals at the call sites and in the DESIGN.md table. A `Metric` enum declares the name, the meter type, the
help text and the tag keys once. Call sites pass only the tag values (`DISTRIBUTOR_CALLS.key(distributor, outcome,
type)`), and a wrong number of values fails fast. A documentation test checks the DESIGN.md 3.7 table against the
enum (name, type, tags), as `ConstraintTableDocumentationTest` does for the constraint table. The stored names and
tags do not change, so `metrics_counters` rows continue.

## 6. Recognisers per family: drop

The polarity, diode subtype and regulator subtype patterns are distributor wording (JLCPCB, TME and Mouser phrases),
and `ComponentTypes` is already their single home. Putting regular expressions on a `domain` enum would mix vocabulary
into the model. The part of the item that is a trait (which families have a polarity or a subtype) moves to the enum
in item 1.

## 7. Derived attributes computed at read time: implement

`ParametricExtractor.enrich` adds derived attributes (`Family`, `Subtype`, `FormFactor`...) to `Part.attributes`, and
the cache stores that payload for good. `enrich` never overwrites an existing key, so a cached part keeps the value of
the extractor that first saw it. Seen live: IGI60L2727 cached with `Family: mosfet` after the gate-driver family was
added.

The change:

- `enrich` records which keys it added (`Part.derivedAttributes`, not serialised). The cache repository writes the
  part without them, so new rows hold the distributor's attributes only. Every read path already enriches, so derived
  values are always those of the running extractor.
- Rows written before the change mix raw and derived keys, and nothing says which is which. `enrich` drops the keys
  that only KINA writes (`Family`, `Subtype`, `FormFactor`, `Elements`, `RatedCurrent`, `SaturationCurrent`,
  `MaxTemperature`, `RippleCurrent`, `OperatingTemperature`, `ConnectorType` and the USB keys) before it derives, so
  those are fresh for old rows too. Keys a distributor may also send (`Capacitance`, `Tolerance`, `Mounting`,
  `Package`...) are kept as they are: dropping them could lose distributor data. Old rows are served as before; they
  become fully raw when they are fetched again.
- In-process results do not change: `enrich(enrich(raw))` equals `enrich(raw)` as before, and ranking still runs on
  the enriched part.

A test covers an old row (payload with a stale `Family`) served with `detail=full`.

## New: shared accessors in `ConstraintKind`: implement

The brief asked whether the anonymous-class overrides can be shortened. Most of them cannot without losing clarity:
each one is a real per-kind rule (USB type against another connector, pin configuration implied by a standard,
the generic connector type that scores but does not count). Grouping the connector and USB kinds into separate
enums would break the single check order and score order that the one enum gives. Two kinds of repetition can go:

- Nine constants spell `q -> other(q) == null ? null : other(q).x()` and
  `f -> f.connector() == null ? null : f.connector().x()`. Two helpers (`otherWanted`, `usbWanted`, `partConnector`)
  take a method reference instead.
- `WATERPROOF`, `BOARD_LOCK` and `POWER_ONLY` repeat the same `score` override. A constructor that takes the feature
  name removes the three bodies.

## Known oddities (reported, not changed)

These are behaviour questions for the user. The refactor keeps them as they are; the golden test pins them.

1. Hints never name `dielectric`, `tolerance`, `orientation` or `elements`, even when a configuration makes them hard
   (`namedInHint` returns false for those kinds). Recommendation: name them when they are hard; it is a one-line
   change per kind and a golden recapture.
2. `tcr`, `esr` and `dcr` are accepted as hard names, but nothing compares them (no `@Match`), so making them hard
   has no effect. Recommendation: warn at startup when a configuration lists them as hard, or match `esr` and `dcr`
   against the extracted ESR and DCR.
3. The ladder's dielectric step also drops the technology words from the phrase when the dielectric is hard and only
   a technology is stated (`DistributorPhraser.ladder`). The technology stays hard in the check, so only the phrase
   sent to the distributor widens. Recommendation: skip the step when the dielectric is hard; low priority.
4. A generic connector type (`header`, `connector`) adds to the score but not to the possible total
   (`Outcome.uncounted`), so the match grade can exceed what the stated constraints explain. Recommendation: count it
   with its weight, or score nothing for a generic type.
5. A connector request with a stated mounting counts mounting in the possible total even when the part has no
   connector details (`CONNECTOR_MOUNTING` returns `counted(0, weight)`). Such a part loses grade for an attribute it
   may state elsewhere. Recommendation: treat it as unverified.
