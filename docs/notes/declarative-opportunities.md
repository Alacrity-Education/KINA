# Note: where behaviour could be declared once (backlog, 2026-10-07)

Status: approved for a second pass. The first step, the `ConstraintKind` enum with `@Relax` and `@Match` (DESIGN.md
3.2 and 3.4), is being implemented, followed by the `PartSearchService` split. After both land, the applicability of
the items below is reviewed again against the new code and the ones that improve readability are implemented
(decision of 2026-10-07). Every step must stay behaviour-preserving and be checked with the golden test.

## The golden test (keep it)

`src/test/java/ro/alacrity/kina/search/ConstraintGoldenTest.java` scores a fixed set of (query, part) pairs taken from
the existing fixtures and compares scores, match grades, unverified lists, hard-constraint conflicts and relaxation
ladder steps with values captured before the declarative refactor (JSON resources next to the test). Run it after
every refactor of the search package:

```bash
./mvnw test -Dtest=ConstraintGoldenTest
```

When a behaviour change is intended, re-capture the goldens in the same commit and say so in the commit message. Never
re-capture to make a refactor pass.

## Candidates

1. **Component family traits.** Families are strings and their traits are scattered as sets: `ARRAY_FAMILIES` and
   `PASSIVE_FAMILIES` (`PassiveDetails`), `CHIP_FAMILIES`, `FREQUENCY_FAMILIES`, `LARGEST_VOLTAGE_FAMILIES`
   (`ParametricExtractor`), `FormFactor.FAMILIES`, the parent map and the words table (`Recognizers`), the policy-family
   switch (`ConstraintPolicy`), the exact-voltage and polarity rules (`ComponentTypes`). Replace them with a
   `ComponentFamily` enum that declares words, parent, primary value kind, has arrays, chip packages, frequency
   valued, largest-voltage rule, exact voltage and the policy family. `@Relax(families = ...)` should then reference
   the enum instead of strings.

2. **Attribute extraction and units on `ConstraintKind`.** About 40 alias lists (`POWER_NAMES`, `VOLTAGE_NAMES`,
   `ESR_NAMES`...) in `ParametricExtractor` and `PassiveDetails`, each consumed by a near-identical call, and the
   unit-suffix switch in `Recognizers` (`w`, `watt`, `watts` to power). Declare `@Source(names, distributor)` and
   `@Unit(symbols, base, display)` on the kinds with one generic extraction loop and one display formatter. Family
   traits (item 1) give the precedence rules (output voltage before voltage rating for regulators).

3. **Distributor dialect (strategy pattern).** 21 `distributor == TME/MOUSER/LCSC` branches in the search package
   (`PartSearchService`, `DistributorPhraser`), the per-distributor phrase maps in `TechnologyVocabulary`, the TME
   40-character limit, the two fallback phrase builders, the "uses the Postgres cache" rule and the lifecycle mapping
   in `Availability.lifecycleOf`. One `DistributorDialect` per distributor (phrase limit, synonyms, fallback builder,
   caching mode, paging semantics, lifecycle mapping), reachable from the `DistributorClient`.

4. **Response detail levels.** `PartResponse.of` decides compact versus full with eight `full ? x : null` branches and
   API.md repeats the field lists. A `@Detail(FULL)` annotation on the record components with a generic projector,
   plus a drift test against API.md like the constraint-table test.

5. **Metrics.** `MetricNames` keeps 35 constants and a parallel 35-entry description map; the tag keys of each series
   exist only in DESIGN.md. A `Metric` enum with help text and declared tag keys removes the parallel list and makes
   tag migrations such as V9 derivable.

6. **Recognisers per family.** The polarity, diode-subtype and regulator-subtype patterns in `ComponentTypes` and the
   array detection in `PassiveDetails` are per-family recognisers; declare them on the family enum (item 1).

Already strategies, not worth touching: `RateLimitRetry`, the per-distributor cache retention, `OidcAccessPolicy`.
