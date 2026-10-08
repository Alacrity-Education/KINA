# Extraction coverage and speed for a field-based search index (2026-10-08)

Question: if KINA searched its cache by extracted fields instead of by cached query keys, how complete would the fields be, and how fast can they be built? This note measures the real extractor (`ParametricExtractor`, 0.14.0, `extract` and `enrich`) on 194 274 sampled LCSC rows and on all 6 660 parts of the live Mouser and TME cache. No source under `src/main` was changed.

## Summary

- Coverage is high where the distributor states a value. On LCSC, the primary value is present for 91 to 96 percent of chip capacitors, resistors and inductors in their own categories. The package is present for 99 percent or more of LCSC parts.
- LCSC has one large hole that no parser change fixes: **10.8 percent of the stratified sample (14.7 percent of a plain random in-stock sample) has a blank description**. For these parts the value is only in the manufacturer part number. They explain 74 to 98 percent of the missing primary values of LCSC capacitors, resistors and inductors.
- Family detection is wrong for whole categories. LCSC "Isolated Power Modules" resolve to `capacitor` (72 percent) and "Common Mode Filters" resolve to `resistor` (73 percent). "DC-DC Converters" and "Varistors" have no family (99 percent and 93 percent). A field index keyed on `Family` would carry these errors.
- Mouser and TME are thinner. Mouser has a package for 28 percent of parts and any key electrical value for 64 percent. TME has a package for 45 percent and a key value for 88 percent.
- The extracted numeric value is a `double` with floating point noise (470 nH is `4.6999999999999995E-7` or `4.7000000000000005E-7` depending on the spelling). An index must round or store a decimal.
- Speed: 2 900 rows/s on one thread, 20 000 rows/s on 8 threads and 33 000 rows/s on 16 threads. The full 7 146 764 row table takes about 3.6 minutes on 16 threads (about 41 minutes on one). Only 723 865 rows are in stock: that is 22 seconds on 16 threads.
- Determinism holds: identical output hash across two JVM runs, repeated runs and 1 versus 16 threads. One cached TME part depends on the order of the distributor's attribute list.

## Method

- LCSC: the JLCPCB SQLite file `/var/tmp/kina-bench/parts-fts5.db` (7 146 764 rows, 723 865 in stock). Sample (`scripts/research/extraction_coverage/sample.sql`): up to 5 000 in-stock rows per Second Category for the 40 largest categories (192 274 rows, which hold 73.5 percent of all in-stock rows) plus 2 000 uniformly random in-stock rows. Selection is deterministic (a rowid hash, not `random()`). Total 194 274 rows, 193 755 distinct part numbers (519 rows are in both strata).
- Each row goes through `LcscPartMapper.map` and then the extractor, exactly as the search path does.
- Mouser and TME: `select distributor, part_number, payload from cached_parts` (3 064 Mouser, 3 596 TME), deserialised with Jackson 3 `JsonMapper` into `Part`, and extracted from `Part.asStored()` as `PartCacheRepository` plus `enrich` would.
- "Typed value present" means the key is in `ParametricExtractor.extract(part)` (the canonical attributes). A column that groups several keys counts a part once: `Curr` is `Current` or `RatedCurrent` (inductors and ferrite beads use `RatedCurrent`; capacitors report `RippleCurrent` instead, so their `Curr` is 0 by design).
- Family is the extractor's `Family` attribute. Percentages are within the sample, not weighted back to the population (see caveats). The first set of tables below is the stratified sample. A second, population-like view comes from the 2 000 random rows alone.

## Headline numbers (LCSC, stratified sample, percent of parts with a typed value)

| Group | Parts | Primary value | Voltage | Tolerance | Package | Mounting | Other |
|---|---:|---:|---:|---:|---:|---:|---|
| Capacitors, all with family `capacitor` | 23 906 | 93.7 | 93.7 | 87.8 | 99.6 | 85.7 | dielectric 19.4, technology 83.5 |
| MLCC category only | 5 296 | 91.5 | 91.3 | 78.1 | 99.7 | 100.0 | dielectric 87.6 |
| Aluminium electrolytic leaded category | 5 024 | 90.2 | 90.1 | 89.6 | 99.9 | 100.0 | |
| Resistors, all with family `resistor` | 18 724 | 94.0 | 54.0 | 75.5 | 99.4 | 85.0 | power 74.3 |
| Chip resistor category only | 5 263 | 93.2 | 81.2 | 93.0 | 100.0 | 100.0 | power 93.0 |
| Inductors, all with family `inductor` | 15 247 | 91.8 | n/a (0.1) | 84.3 | 99.9 | 98.2 | rated current 90.4 |
| Connectors, all with family `connector` | 44 183 | n/a | 40.3 | n/a | 98.1 | 64.6 | type 90.9, positions 71.1, pitch 77.9, gender 43.2, orientation 46.6 |

Notes: the all-family capacitor row includes about 3 700 isolated power modules (family wrongly `capacitor`), which is why its Dielectric and Mounting differ from the pure categories. Tolerance for MLCC is lower (78 percent) because many JLCPCB MLCC descriptions are blank or list several tolerances.

Mouser and TME overall (all categories in the cache):

| Distributor | Parts | Family unknown % | Mean typed attributes per part | With a package % | With any of Voltage, Resistance, Capacitance, Inductance, Current % |
|---|---:|---:|---:|---:|---:|
| LCSC (stratified) | 194 274 | 5.6 | 8.26 | 99.1 | 81.3 |
| LCSC (random in-stock only, 2 000 rows) | 2 000 | 12.1 | 7.42 | 93.8 | 79.8 |
| TME | 3 596 | 9.1 | 9.04 | 44.5 | 88.2 |
| MOUSER | 3 064 | 2.7 | 5.45 | 27.9 | 64.1 |

Per family, Mouser and TME (from the coverage table below): capacitor primary value 96.1 percent (Mouser) and 100 percent (TME); resistor 95.1 and 99.6; inductor 85.8 and 100. Mouser's weak spots are package (51.7 percent for capacitors, 8.0 for inductors, 2.5 for connectors), voltage of resistors (11.8) and connector pitch (5.4). TME's weak spot is package (0.8 percent for connectors, 36.8 for inductors, 37.2 for resistors).

## Three largest extraction gaps

1. **Blank LCSC descriptions.** 10.8 percent of the stratified sample and 14.7 percent of the random in-stock sample have no description. Of the LCSC family parts without a primary value, 1 112 of 1 498 capacitors, 1 000 of 1 132 resistors and 1 219 of 1 250 inductors have a blank description. The value exists only in the MPN (`GCM155D70G475ME36D` is 4.7 uF, `CSA0402X7R393K100GT` is 39 nF). The extractor does not decode MPNs. The second cause for leaded electrolytics is a description that holds only the package (`插件,D5xL11mm`).
2. **Family errors by category.** `Power Modules / Isolated Power Modules`: 3 670 of 5 077 become `capacitor`, 1 407 have no family. `Filters / Common Mode Filters`: 3 416 of 4 650 become `resistor`. `Power Management / DC-DC Converters`: 4 951 of 5 017 have no family. `Circuit Protection / Varistors`: 3 231 of 3 478 have no family. `Logic / Logic Gates` (11 of 11) become `capacitor`. Values extracted for these (for example capacitor tolerance of 77 to 90 percent, 3 665 parts) are noise. See "Suspicious extracted values".
3. **Thin Mouser and TME packages and attributes, plus leakage current read as current.** Package: Mouser 27.9 percent, TME 44.5 percent. Mouser connector pitch 5.4 percent, Mouser MOSFET voltage 58.2 percent and resistance 13.5 percent. In the other direction, 2 801 LCSC transistors and 3 407 comparators and op-amps report a `Current` in microamps (the first current token is a leakage or bias current such as `100nA`), so `Current` is not a reliable key for those families.

Smaller gaps, fully listed below: multi-value JLCPCB descriptions joined with `、` (35 parts in the sample, deliberately not read), and Mouser descriptions with the value glued to a tolerance (`22uF+/-10%`, `2.2uH+/-20%`; 17 parts).

## Throughput and extrapolation

Mapping (`LcscPartMapper.map`, includes price parsing) plus `ParametricExtractor.enrich`, in memory, from rows already loaded. JIT warm-up pass first, then three timed passes per setting, JDK 27 on a 24-core host.

| Threads | Stratified 194 274 rows, rows/s (3 runs) | Random 100 000 rows, rows/s (3 runs) | Speed-up | 7 146 764 rows | 723 865 in-stock rows |
|---:|---|---|---:|---:|---:|
| 1 | 2 835, 2 906, 2 912 | 3 033, 3 026, 3 039 | 1.0 | about 41 min (2 450 s) | about 4.2 min |
| 8 | 20 034, 19 839, 20 634 | 21 878, 21 778, 21 931 | 6.9 to 7.2 | about 6.0 min (360 s) | about 36 s |
| 16 | 35 047, 32 431, 33 909 | 33 937, 33 454, 34 968 | 11.3 to 11.7 | **about 3.6 min (215 s)** | **about 22 s** |

The extrapolation uses 33 000 rows/s at 16 threads (both samples agree within 3 percent). The cost per row is about 350 microseconds on one thread, so it is CPU bound and scales until memory bandwidth and the 16 thread limit.

What the figures include and exclude: they exclude reading the SQLite file (a sequential scan of 5.3 GB), JSON serialisation, and the database writes of the index itself, which will probably dominate. Extraction alone is not the bottleneck of a 5 day refresh.

Memory: the runner kept all 194 274 `JlcpcbRow` objects resident (about 376 MiB live after a full GC). It ran at `-Xmx512m` and `-Xmx1g`, and failed with `OutOfMemoryError` at `-Xmx320m`. Streaming rows needs far less than that, because extraction itself only allocates short-lived garbage. Timings at 512 MiB (single 2 860 to 2 888, 8 threads 18 400 to 19 200, 16 threads 30 000 to 30 900 rows/s) were within 10 percent of 6 GiB.

Host condition during the runs: about 4 to 10 GiB free memory, 25 GiB of 31 GiB swap in use and other sessions running (load average 5 to 6 on 24 cores). The runner itself stayed under 6 GiB heap as requested and was not swapping, but the numbers are not from a quiet machine. Expect the 16 thread figure to be a conservative estimate.

## Determinism

Three hashes over `key | attributes (as rendered) | sorted derivedAttributes` for all 200 934 parts:

| Run | SHA-256 |
|---|---|
| single thread, attributes in map iteration order | `3470e2a1c38d5324fe434d6e9d899f8aaaef6a0df4511ecd4549c33752e41a41` |
| same, second pass in the same JVM | identical |
| 16 thread parallel stream | identical |
| a second JVM process, all of the above | identical |
| keys sorted (TreeMap), single and 16 threads | `8bd9d284efd849d0ecbbf6fc772241eb79990a22ca5664ece506ff6e47014238` (identical across both) |

- The attribute map the extractor returns is a `LinkedHashMap` in a fixed order (declaration order of `PartAttribute.VALUES`, then the word attributes). No attribute value changed with thread count or JVM.
- `enrich` is idempotent for all 200 934 parts (`enrich(enrich(p))` has the same attributes).
- Order dependence in the input: I reversed the order of each cached part's own attributes and compared the extracted values. One part changes: `TME:1N4148W-YGO` (category `SMD universal diodes`) reads `Current=150mA` with the original order and `Current=300mA` reversed. The first matching distributor attribute wins, so the result depends on the order the distributor delivered. The stored JSON keeps that order, so it is stable for a given cache row.
- `Part.derivedAttributes()` is an immutable `Set` (`Set.copyOf`), whose iteration order is randomised per JVM. Do not hash or index it unsorted. The runner sorts it.

## Cardinality and index design

From the tables below (all distributors, stratified sample):

- Enum or `smallint` candidates (a handful of distinct values): `Mounting` (2), `Gender` (2), `Orientation` (3), `Rows` (6), `Subtype` (3), `Polarity` (5), `Dielectric` (11), `Technology` (22), `ConnectorType` (15), `Family` (24 values seen, `ComponentFamily` has more), `UsbType` (7).
- Small numeric domains, a btree or even a bitmap works: `Tolerance` (108 distinct, 20 percent is 22 911 parts and 1 percent 9 466), `Pitch` (109), `Positions` (125), `Voltage` (1 083, top 50 V 9 701 and 250 V 6 191), `Power` (817), `Frequency` (1 040).
- Wide domains, need an ordered numeric index (store the SI value rounded, not the display string): `Capacitance` (1 397), `Resistance` (1 485), `Inductance` (356), `Current` (1 404).
- Free text, high cardinality: `Package` (5 869 distinct, 635 for capacitors alone, because it carries the distributor's raw string: `Plugin,P=2.54mm`, `D6.3 x 7.7mm`, `SMD-4P,6x6mm`). Chip packages (0603, 0805...) dominate the top but the tail is long. For a hard `PACKAGE` filter, a normalised key (chip size or package family) would be needed; the extracted `Package` string is not that.
- A partial index per family (for example `Capacitance` only where `Family = capacitor`) is natural. Primary values are only meaningful per family: a `capacitor` row has `Resistance` in 936 parts (ESR) and `Power` in 3 484 (mostly the misclassified power modules), and `resistor` has `Inductance` in 115 parts.

## Normalisation of values

Raw spellings of the same quantity do collapse to one SI value: 10 uF, 10.0UF, 10UF, 10uF; 0.1 uF, 100nF, 100000pF; 4.7K, 4K7, 4.7kOhms, 4.7kΩ all give a single double (only descriptions with exactly one matching token are counted, so the first token is the extracted one). Mean spellings per distinct SI value is 1.07 to 1.11, with a maximum of 9 for a resistor (10 Ω as `10R`, `10Ohm`, `10 OHMS`, `10ohm`...). No case where two different quantities collapse was seen.

Problem: the SI value is a plain double, so equal quantities can differ in the last bits. 470 nH from `0.47uH` is `4.6999999999999995E-7` and from `470nH` is `4.7000000000000005E-7`. Distinct doubles versus distinct values after rounding to 9 significant digits:

| Family | Parts with value | Distinct doubles | After rounding to 9 digits |
|---|---:|---:|---:|
| capacitor | 23 783 | 373 | 372 |
| resistor | 18 440 | 1 118 | 1 117 |
| inductor | 14 630 | 332 | 328 |

The index must round (or use `BigDecimal` or integer pico-units) before it compares or hashes values. Exact equality on the raw double will miss rows.

## Full tables (stratified sample, all distributors)

The tables below are generated by the runner (`coverage` mode) and pasted as written. The first column set is described in "Method". Cells for attributes that do not apply to a family (for example `Cap` for a connector) are expected to be near 0.

### Population and unknown family

| Distributor | Parts | Family unknown | Family unknown % | Blank description % |
|---|---:|---:|---:|---:|
| LCSC | 194274 | 10835 | 5.6 | 10.8 |
| TME | 3596 | 327 | 9.1 | 0.0 |
| MOUSER | 3064 | 84 | 2.7 | 0.1 |

### Unknown family, top categories: LCSC

| Category | Unknown | In sample | Unknown % |
|---|---:|---:|---:|
| Power Management (PMIC) / DC-DC Converters | 4951 | 5017 | 98.7 |
| Circuit Protection / Varistors | 3231 | 3478 | 92.9 |
| Power Modules / Isolated Power Modules | 1407 | 5077 | 27.7 |
| Filters / Common Mode Filters | 1065 | 4650 | 22.9 |
| Wires and cables / Dupont wire / terminal block wire / electronic wire | 59 | 59 | 100.0 |
| Terminal / PCB Welding Terminal | 13 | 13 | 100.0 |
| Power Management (PMIC) / Supervisor and Reset ICs | 11 | 11 | 100.0 |
| Terminal / SMD Quick Terminal | 10 | 10 | 100.0 |
| Sensors / Pressure Sensors | 6 | 6 | 100.0 |
| Circuit Protection / Gas Discharge Tube Arresters (GDT) | 5 | 11 | 45.5 |
| Clock/Timing / Real Time Clocks | 5 | 5 | 100.0 |
| Memory / EEPROM | 5 | 5 | 100.0 |
| Power Management (PMIC) / Battery Management | 5 | 5 | 100.0 |
| Interface / CAN Transceivers | 4 | 4 | 100.0 |
| Power Management (PMIC) / Voltage Reference | 4 | 4 | 100.0 |

### Unknown family, top categories: TME

| Category | Unknown | In sample | Unknown % |
|---|---:|---:|---:|
| USB cables and adapters | 161 | 173 | 93.1 |
| Computer Adapters | 31 | 48 | 64.6 |
| Plug-in Power Supplies | 24 | 24 | 100.0 |
| Test Probes | 14 | 60 | 23.3 |
| Car Power Accessories | 13 | 13 | 100.0 |
| Computer Power Supply Units and UPSs | 11 | 19 | 57.9 |
| Raspberry Pi - Minicomputers | 10 | 10 | 100.0 |
| Add-on boards | 7 | 7 | 100.0 |
| HDD/SSD Accessories | 7 | 21 | 33.3 |
| Rechargeable Batteries | 6 | 6 | 100.0 |
| Panel Mount Accessories | 5 | 6 | 83.3 |
| Arduino Solutions | 4 | 4 | 100.0 |
| Extension Power Cords | 4 | 4 | 100.0 |
| HDMI, DVI, DisplayPort cables and adapt. | 4 | 7 | 57.1 |
| Soldering-desoldering stations | 4 | 6 | 66.7 |

### Unknown family, top categories: MOUSER

| Category | Unknown | In sample | Unknown % |
|---|---:|---:|---:|
| Digital Isolators | 15 | 16 | 93.8 |
| Audio Amplifiers | 12 | 22 | 54.5 |
| Power Management IC Development Tools | 9 | 16 | 56.3 |
| EEPROM | 8 | 17 | 47.1 |
| Capacitive Touch Sensors | 7 | 7 | 100.0 |
| High Speed Operational Amplifiers | 3 | 4 | 75.0 |
| USB Cables / IEEE 1394 Cables | 3 | 4 | 75.0 |
| null | 3 | 3 | 100.0 |
| Display Modules | 2 | 2 | 100.0 |
| Solder | 2 | 2 | 100.0 |
| Touch Sensor Development Tools | 2 | 2 | 100.0 |
| Video ICs | 2 | 3 | 66.7 |
| Wall Mount AC Adapters | 2 | 2 | 100.0 |
| Adafruit Accessories | 1 | 21 | 4.8 |
| Analog to Digital Converters - ADC | 1 | 1 | 100.0 |

### Family by category (LCSC sample): the families each category resolves to

| Category | Parts | Families (count) |
|---|---:|---|
| Capacitors / Multilayer Ceramic Capacitors MLCC - SMD/SMT | 5296 | capacitor (5296) |
| Resistors / Chip Resistor - Surface Mount | 5263 | resistor (5263) |
| Circuit Protection / ESD and Surge Protection (TVS/ESD) | 5132 | tvs (5132) |
| Diodes / Schottky Diodes | 5089 | schottky (5089) |
| Switches / Tactile Switches | 5087 | switch (5087) |
| Power Modules / Isolated Power Modules | 5077 | capacitor (3670), null (1407) |
| Transistors/Thyristors / MOSFETs | 5072 | mosfet (5072) |
| Embedded Processors & Controllers / Microcontrollers (MCU/MPU/SOC) | 5059 | mcu (5059) |
| Connectors / Wire To Board Connector | 5049 | connector (5049) |
| Inductors, Coils, Chokes / Molding Power Inductors | 5039 | inductor (5039) |
| Power Management (PMIC) / Voltage Regulators - Linear, Low Drop Out (LDO) Regulators | 5029 | regulator (5029) |
| Inductors, Coils, Chokes / Inductors (SMD) | 5027 | inductor (5027) |
| Amplifiers/Comparators / Operational Amplifier | 5024 | comparator (5024) |
| Capacitors / Aluminum Electrolytic Capacitors - Leaded | 5024 | capacitor (5024) |
| Diodes / Fast Recovery / High Efficiency Diodes | 5023 | diode (5023) |
| Connectors / FFC, FPC (Flat Flexible) Connector Assemblies | 5022 | connector (5022) |
| Power Management (PMIC) / DC-DC Converters | 5017 | null (4951), regulator (63), resistor (2), mosfet (1) |
| Connectors / Board-to-Board and Backplane Connector | 5016 | connector (5016) |
| Connectors / USB Connectors | 5016 | connector (5016) |
| Transistors/Thyristors / Bipolar (BJT) | 5016 | transistor (5016) |
| Diodes / Zener Diodes | 5014 | zener (5014) |
| Connectors / Housings (Wire To Board / Wire To Wire ) | 5011 | connector (5011) |
| Connectors / Pluggable System Terminal Block | 5011 | connector (5011) |
| Inductors, Coils, Chokes / Power Inductors | 5009 | inductor (5009) |
| Resistors / Current Sense Resistors / Shunt Resistors | 5007 | resistor (5007) |
| Optoelectronics / LED Indication - Discrete | 5006 | led (5006) |
| Circuit Protection / Resettable Fuses | 5005 | fuse (5005) |
| Resistors / Through Hole Resistors | 5005 | resistor (5005) |
| Crystals, Oscillators, Resonators / Crystals | 5002 | crystal (5002) |
| Capacitors / Aluminum Electrolytic Capacitors - SMD | 5000 | capacitor (5000) |
| Connectors / Female Headers | 5000 | connector (5000) |
| Connectors / Pin Headers | 5000 | connector (5000) |
| Filters / Common Mode Filters | 4650 | resistor (3416), null (1065), inductor (169) |
| Capacitors / Polymer Aluminum Capacitors | 4628 | capacitor (4628) |
| Filters / Ferrite Beads | 4282 | ferrite (4282) |
| Crystals, Oscillators, Resonators / Crystal Oscillators | 4258 | oscillator (4258) |
| Connectors / FFC Cable (Flexible Flat Cable) | 4002 | connector (4002) |
| Circuit Protection / Disposable fuses | 3620 | fuse (3620) |
| Circuit Protection / Varistors | 3478 | null (3231), capacitor (235), tvs (12) |
| Diodes / Bridge Rectifiers | 3447 | diode (3447) |
| Wires and cables / Dupont wire / terminal block wire / electronic wire | 59 | null (59) |
| Switches / Limit Switches | 21 | switch (21) |
| Switches / Switch Accessories / Caps | 19 | capacitor (19) |
| Resistors / Potentiometers, Variable Resistors | 18 | resistor (18) |
| Diodes / Switching Diodes | 17 | diode (17) |
| Switches / DIP Switches | 15 | switch (15) |
| Diodes / Diodes - General Purpose | 13 | diode (13) |
| Terminal / PCB Welding Terminal | 13 | null (13) |
| Power Management (PMIC) / AC-DC Controllers and Regulators | 12 | regulator (12) |
| Circuit Protection / Gas Discharge Tube Arresters (GDT) | 11 | capacitor (6), null (5) |
| Logic / Logic Gates | 11 | capacitor (11) |
| Power Management (PMIC) / Supervisor and Reset ICs | 11 | null (11) |
| Terminal / SMD Quick Terminal | 10 | null (10) |
| Switches / Rotary Coding Switch | 8 | switch (8) |
| Connectors / Connector Housings | 7 | connector (7) |
| Amplifiers/Comparators / Precision Op Amps | 6 | opamp (6) |
| Connectors / Circular Connectors & Cable Connectors | 6 | connector (6) |
| Connectors / Hard Disk Connector (SAS/SATA/M.2) | 6 | connector (6) |
| Power Management (PMIC) / Power Distribution Switches | 6 | resistor (6) |
| Sensors / Pressure Sensors | 6 | null (6) |

### Coverage: percent of parts with a typed value, per family and distributor

| Family | Dist | Parts | Cap | Res | Ind | Volt | Curr | Pwr | Tol | Pkg | Mount | Diel | Tech | ConnType | Pos | Pitch | Gender | Orient |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| connector | LCSC | 44183 | 0.0 | 0.0 | 0.0 | 40.3 | 51.6 | 0.0 | 0.0 | 98.1 | 64.6 | 0.0 | 0.0 | 90.9 | 71.1 | 77.9 | 43.2 | 46.6 |
| connector | TME | 370 | 0.3 | 1.6 | 0.0 | 33.2 | 55.9 | 0.0 | 0.0 | 0.8 | 81.6 | 0.0 | 0.0 | 94.1 | 80.0 | 35.7 | 92.2 | 85.1 |
| connector | MOUSER | 485 | 8.5 | 0.0 | 0.2 | 3.1 | 6.2 | 0.6 | 0.0 | 2.5 | 35.5 | 0.0 | 0.0 | 97.3 | 54.4 | 5.4 | 57.9 | 44.5 |
| capacitor | LCSC | 23906 | 93.7 | 3.3 | 0.0 | 93.7 | 0.0 | 14.6 | 87.8 | 99.6 | 85.7 | 19.4 | 83.5 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| capacitor | TME | 741 | 100.0 | 7.4 | 0.0 | 99.1 | 0.0 | 0.4 | 94.1 | 79.9 | 99.1 | 42.4 | 99.1 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| capacitor | MOUSER | 660 | 96.1 | 12.7 | 0.0 | 97.1 | 0.0 | 0.0 | 79.7 | 51.7 | 75.6 | 49.8 | 99.4 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| resistor | LCSC | 18724 | 0.0 | 94.0 | 0.6 | 54.0 | 19.0 | 74.3 | 75.5 | 99.4 | 85.0 | 0.0 | 76.4 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| resistor | TME | 559 | 0.0 | 99.6 | 0.0 | 56.0 | 8.2 | 91.4 | 90.7 | 37.2 | 78.5 | 0.0 | 91.1 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| resistor | MOUSER | 306 | 0.0 | 95.1 | 0.0 | 11.8 | 1.0 | 57.8 | 85.6 | 42.8 | 66.3 | 0.0 | 91.8 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| inductor | LCSC | 15247 | 0.0 | 0.0 | 91.8 | 0.1 | 90.4 | 0.0 | 84.3 | 99.9 | 98.2 | 0.0 | 21.5 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| inductor | TME | 342 | 0.0 | 0.0 | 100.0 | 2.3 | 93.6 | 2.3 | 82.7 | 36.8 | 92.7 | 0.0 | 79.2 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| inductor | MOUSER | 339 | 0.0 | 0.0 | 85.8 | 0.0 | 56.3 | 0.0 | 56.6 | 8.0 | 95.9 | 0.0 | 2.9 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| fuse | LCSC | 8625 | 0.0 | 48.5 | 0.0 | 81.8 | 84.0 | 46.3 | 0.0 | 97.9 | 88.7 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| diode | LCSC | 8504 | 0.0 | 0.0 | 0.0 | 91.6 | 91.5 | 2.3 | 0.0 | 99.7 | 54.9 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| switch | LCSC | 5138 | 0.0 | 0.0 | 0.0 | 79.6 | 79.7 | 0.0 | 0.0 | 99.7 | 91.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 59.4 |
| switch | TME | 237 | 0.0 | 8.9 | 0.0 | 94.9 | 96.2 | 0.0 | 0.0 | 0.0 | 60.8 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.8 |
| switch | MOUSER | 211 | 0.0 | 0.0 | 0.0 | 12.8 | 12.8 | 0.0 | 0.0 | 0.0 | 24.6 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 4.3 |
| led | LCSC | 5011 | 0.0 | 0.0 | 0.0 | 84.6 | 76.7 | 63.5 | 0.0 | 98.5 | 95.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 23.4 |
| led | TME | 237 | 0.0 | 0.0 | 0.0 | 91.1 | 80.6 | 35.9 | 0.0 | 98.7 | 98.7 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| led | MOUSER | 192 | 0.0 | 0.0 | 0.0 | 9.9 | 3.1 | 0.0 | 0.0 | 65.6 | 67.2 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 3.6 |
| mosfet | LCSC | 5074 | 81.7 | 77.0 | 0.0 | 86.8 | 85.1 | 82.5 | 0.0 | 99.9 | 78.4 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| mosfet | TME | 153 | 0.0 | 96.7 | 0.0 | 100.0 | 100.0 | 97.4 | 0.0 | 100.0 | 100.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| mosfet | MOUSER | 141 | 0.7 | 13.5 | 0.0 | 58.2 | 31.9 | 14.2 | 0.7 | 44.7 | 44.7 | 0.0 | 37.6 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| schottky | LCSC | 5089 | 0.0 | 0.0 | 0.0 | 88.4 | 88.2 | 0.0 | 0.0 | 99.9 | 89.1 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| schottky | TME | 69 | 33.3 | 0.0 | 0.0 | 100.0 | 100.0 | 2.9 | 0.0 | 100.0 | 100.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| schottky | MOUSER | 81 | 0.0 | 1.2 | 0.0 | 70.4 | 60.5 | 0.0 | 0.0 | 28.4 | 32.1 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| tvs | LCSC | 5144 | 30.5 | 0.0 | 0.0 | 91.0 | 90.4 | 23.4 | 0.0 | 99.9 | 94.2 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| tvs | MOUSER | 54 | 0.0 | 0.0 | 0.0 | 90.7 | 14.8 | 48.1 | 31.5 | 11.1 | 9.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| regulator | LCSC | 5104 | 0.0 | 0.0 | 0.0 | 89.9 | 89.0 | 0.1 | 0.0 | 99.8 | 88.9 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| regulator | TME | 34 | 0.0 | 0.0 | 0.0 | 100.0 | 100.0 | 0.0 | 91.2 | 100.0 | 100.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| regulator | MOUSER | 25 | 0.0 | 0.0 | 0.0 | 8.0 | 64.0 | 0.0 | 8.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| mcu | LCSC | 5059 | 0.0 | 0.0 | 0.0 | 78.7 | 0.1 | 0.0 | 0.0 | 99.4 | 82.4 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| transistor | LCSC | 5031 | 0.1 | 0.1 | 0.0 | 92.8 | 93.0 | 92.1 | 0.0 | 99.8 | 90.7 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| transistor | TME | 27 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| crystal | LCSC | 5002 | 84.3 | 62.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 99.9 | 97.8 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| crystal | MOUSER | 41 | 100.0 | 17.1 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 56.1 | 68.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| comparator | LCSC | 5028 | 0.0 | 0.0 | 0.0 | 94.6 | 94.4 | 0.0 | 0.1 | 99.9 | 87.6 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| zener | LCSC | 5014 | 0.0 | 88.3 | 0.0 | 92.4 | 23.3 | 92.0 | 13.6 | 99.9 | 91.7 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| ferrite | LCSC | 4282 | 0.0 | 0.0 | 0.0 | 0.0 | 85.2 | 0.0 | 83.4 | 99.6 | 98.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| ferrite | TME | 168 | 0.0 | 0.0 | 0.0 | 0.0 | 95.8 | 0.0 | 41.7 | 97.0 | 97.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| ferrite | MOUSER | 114 | 0.0 | 0.0 | 0.0 | 0.0 | 64.9 | 0.0 | 29.8 | 71.9 | 73.7 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| oscillator | LCSC | 4262 | 0.0 | 0.0 | 0.0 | 79.1 | 56.7 | 0.0 | 0.0 | 100.0 | 99.4 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| oscillator | MOUSER | 40 | 2.5 | 0.0 | 0.0 | 65.0 | 5.0 | 0.0 | 0.0 | 50.0 | 60.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| fan | TME | 296 | 0.0 | 0.0 | 0.0 | 100.0 | 75.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| fan | MOUSER | 183 | 0.0 | 0.5 | 0.0 | 97.3 | 8.7 | 18.6 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |

### Coverage per category (percent of the category's parts; LCSC categories with at least 3000 sampled parts, others at least 150)

| Distributor | Category | Parts | Cap | Res | Ind | Volt | Curr | Pwr | Tol | Pkg | Mount | Diel | Tech | ConnType | Pos | Pitch | Gender | Orient |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| LCSC | Capacitors / Multilayer Ceramic Capacitors MLCC - SMD/SMT | 5296 | 91.5 | 0.0 | 0.0 | 91.3 | 0.0 | 0.0 | 78.1 | 99.7 | 100.0 | 87.6 | 100.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Resistors / Chip Resistor - Surface Mount | 5263 | 0.0 | 93.2 | 0.0 | 81.2 | 0.0 | 93.0 | 93.0 | 100.0 | 100.0 | 0.0 | 91.1 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Circuit Protection / ESD and Surge Protection (TVS/ESD) | 5132 | 30.3 | 0.0 | 0.0 | 91.0 | 90.4 | 23.4 | 0.0 | 99.9 | 94.2 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Diodes / Schottky Diodes | 5089 | 0.0 | 0.0 | 0.0 | 88.4 | 88.2 | 0.0 | 0.0 | 99.9 | 89.1 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Switches / Tactile Switches | 5087 | 0.0 | 0.0 | 0.0 | 79.6 | 79.7 | 0.0 | 0.0 | 99.7 | 91.4 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 59.8 |
| LCSC | Power Modules / Isolated Power Modules | 5077 | 72.3 | 0.0 | 0.0 | 85.6 | 11.1 | 77.2 | 85.1 | 99.0 | 93.5 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Transistors/Thyristors / MOSFETs | 5072 | 81.7 | 77.0 | 0.0 | 86.8 | 85.1 | 82.5 | 0.0 | 99.9 | 78.4 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Embedded Processors & Controllers / Microcontrollers (MCU/MPU/SOC) | 5059 | 0.0 | 0.0 | 0.0 | 78.7 | 0.1 | 0.0 | 0.0 | 99.4 | 82.4 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Connectors / Wire To Board Connector | 5049 | 0.0 | 0.0 | 0.0 | 74.3 | 75.7 | 0.0 | 0.0 | 99.5 | 77.2 | 0.0 | 0.0 | 100.0 | 83.1 | 97.6 | 0.0 | 62.9 |
| LCSC | Inductors, Coils, Chokes / Molding Power Inductors | 5039 | 0.0 | 0.0 | 88.1 | 0.0 | 87.6 | 0.0 | 87.9 | 100.0 | 99.3 | 0.0 | 0.1 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Power Management (PMIC) / Voltage Regulators - Linear, Low Drop Out (LDO) Regulators | 5029 | 0.0 | 0.0 | 0.0 | 89.8 | 89.1 | 0.0 | 0.0 | 99.8 | 89.1 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Inductors, Coils, Chokes / Inductors (SMD) | 5027 | 0.0 | 0.0 | 91.9 | 0.0 | 90.3 | 0.0 | 73.7 | 99.8 | 100.0 | 0.0 | 58.8 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Amplifiers/Comparators / Operational Amplifier | 5024 | 0.0 | 0.0 | 0.0 | 94.6 | 94.4 | 0.0 | 0.1 | 99.9 | 87.6 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Capacitors / Aluminum Electrolytic Capacitors - Leaded | 5024 | 90.2 | 4.0 | 0.0 | 90.1 | 0.0 | 0.0 | 89.6 | 99.9 | 100.0 | 0.0 | 100.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Diodes / Fast Recovery / High Efficiency Diodes | 5023 | 0.0 | 0.0 | 0.0 | 91.2 | 91.2 | 3.6 | 0.0 | 99.9 | 90.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Connectors / FFC, FPC (Flat Flexible) Connector Assemblies | 5022 | 0.0 | 0.0 | 0.0 | 47.0 | 34.1 | 0.0 | 0.0 | 99.4 | 97.9 | 0.0 | 0.0 | 100.0 | 75.2 | 99.1 | 0.0 | 90.5 |
| LCSC | Power Management (PMIC) / DC-DC Converters | 5017 | 0.0 | 0.0 | 0.0 | 92.5 | 83.4 | 0.0 | 0.4 | 99.7 | 76.9 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Connectors / Board-to-Board and Backplane Connector | 5016 | 0.0 | 0.0 | 0.0 | 38.9 | 44.8 | 0.0 | 0.0 | 97.7 | 95.4 | 0.0 | 0.0 | 100.0 | 63.2 | 96.4 | 20.7 | 63.3 |
| LCSC | Connectors / USB Connectors | 5016 | 0.0 | 0.0 | 0.0 | 49.8 | 64.4 | 0.0 | 0.0 | 97.2 | 94.3 | 0.0 | 0.0 | 100.0 | 69.9 | 0.0 | 70.4 | 48.8 |
| LCSC | Transistors/Thyristors / Bipolar (BJT) | 5016 | 0.0 | 0.0 | 0.0 | 92.8 | 93.0 | 92.2 | 0.0 | 99.8 | 90.7 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Diodes / Zener Diodes | 5014 | 0.0 | 88.3 | 0.0 | 92.4 | 23.3 | 92.0 | 13.6 | 99.9 | 91.7 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Connectors / Housings (Wire To Board / Wire To Wire ) | 5011 | 0.0 | 0.0 | 0.0 | 0.1 | 0.1 | 0.0 | 0.0 | 93.9 | 0.2 | 0.0 | 0.0 | 100.0 | 81.4 | 95.0 | 2.1 | 0.9 |
| LCSC | Connectors / Pluggable System Terminal Block | 5011 | 0.0 | 0.0 | 0.0 | 88.1 | 88.6 | 0.0 | 0.0 | 97.2 | 35.7 | 0.0 | 0.0 | 100.0 | 89.9 | 99.2 | 87.5 | 47.4 |
| LCSC | Inductors, Coils, Chokes / Power Inductors | 5009 | 0.0 | 0.0 | 95.2 | 0.0 | 93.2 | 0.0 | 94.3 | 99.9 | 95.7 | 0.0 | 6.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Resistors / Current Sense Resistors / Shunt Resistors | 5007 | 0.0 | 87.8 | 0.0 | 6.3 | 5.1 | 85.9 | 87.9 | 99.0 | 94.4 | 0.0 | 100.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Optoelectronics / LED Indication - Discrete | 5006 | 0.0 | 0.0 | 0.0 | 84.7 | 76.7 | 63.5 | 0.0 | 98.5 | 95.2 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 23.4 |
| LCSC | Circuit Protection / Resettable Fuses | 5005 | 0.0 | 83.6 | 0.0 | 83.1 | 82.7 | 79.8 | 0.0 | 99.4 | 95.5 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Resistors / Through Hole Resistors | 5005 | 0.0 | 96.7 | 0.0 | 46.2 | 0.0 | 94.1 | 96.4 | 99.8 | 100.0 | 0.0 | 89.8 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Crystals, Oscillators, Resonators / Crystals | 5002 | 84.3 | 62.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 99.9 | 97.8 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Capacitors / Aluminum Electrolytic Capacitors - SMD | 5000 | 95.7 | 4.3 | 0.0 | 95.7 | 0.0 | 0.0 | 95.7 | 99.7 | 100.0 | 0.0 | 100.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Connectors / Female Headers | 5000 | 0.0 | 0.0 | 0.0 | 26.1 | 76.5 | 0.0 | 0.0 | 99.6 | 88.3 | 0.0 | 0.0 | 100.0 | 81.1 | 99.4 | 100.0 | 36.8 |
| LCSC | Connectors / Pin Headers | 5000 | 0.0 | 0.0 | 0.0 | 30.4 | 70.1 | 0.0 | 0.0 | 99.7 | 79.1 | 0.0 | 0.0 | 100.0 | 82.4 | 99.8 | 100.0 | 59.8 |
| LCSC | Filters / Common Mode Filters | 4650 | 0.0 | 73.5 | 6.1 | 70.9 | 79.8 | 0.0 | 0.0 | 98.7 | 29.7 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Capacitors / Polymer Aluminum Capacitors | 4628 | 93.1 | 8.3 | 0.0 | 93.0 | 0.0 | 0.0 | 84.1 | 99.8 | 34.1 | 0.0 | 100.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Filters / Ferrite Beads | 4282 | 0.0 | 0.0 | 0.0 | 0.0 | 85.2 | 0.0 | 83.4 | 99.6 | 98.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Crystals, Oscillators, Resonators / Crystal Oscillators | 4258 | 0.0 | 0.0 | 0.0 | 79.1 | 56.7 | 0.0 | 0.0 | 100.0 | 99.4 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Connectors / FFC Cable (Flexible Flat Cable) | 4002 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 98.9 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Circuit Protection / Disposable fuses | 3620 | 0.0 | 0.0 | 0.0 | 80.1 | 85.6 | 0.0 | 0.0 | 95.8 | 79.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Circuit Protection / Varistors | 3478 | 7.1 | 0.0 | 0.0 | 91.7 | 80.7 | 52.0 | 0.0 | 98.3 | 81.1 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LCSC | Diodes / Bridge Rectifiers | 3447 | 0.0 | 0.0 | 0.0 | 92.2 | 91.9 | 0.0 | 0.0 | 99.5 | 3.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| TME | Inductors | 334 | 0.0 | 0.0 | 100.0 | 0.0 | 95.8 | 0.0 | 84.7 | 37.7 | 94.9 | 0.0 | 81.1 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| TME | MLCC SMD capacitors | 312 | 100.0 | 0.0 | 0.0 | 100.0 | 0.0 | 0.0 | 99.0 | 100.0 | 100.0 | 100.0 | 100.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| TME | USB & IEEE1394 connectors | 214 | 0.0 | 0.0 | 0.0 | 30.8 | 54.2 | 0.0 | 0.0 | 1.4 | 85.5 | 0.0 | 0.0 | 92.1 | 76.6 | 0.0 | 92.1 | 87.9 |
| TME | DC12V Fans | 212 | 0.0 | 0.0 | 0.0 | 100.0 | 76.4 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| TME | SMD resistors | 207 | 0.0 | 99.5 | 0.0 | 67.1 | 0.0 | 100.0 | 98.1 | 100.0 | 100.0 | 0.0 | 100.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| TME | USB cables and adapters | 173 | 2.9 | 4.0 | 0.0 | 0.0 | 13.9 | 25.4 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| TME | Ferrite - beads | 163 | 0.0 | 0.0 | 0.0 | 0.0 | 98.8 | 0.0 | 42.9 | 100.0 | 100.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| MOUSER | Multilayer Ceramic Capacitors MLCC - SMD/SMT | 367 | 96.5 | 0.5 | 0.0 | 98.9 | 0.0 | 0.0 | 85.8 | 89.4 | 100.0 | 89.1 | 100.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| MOUSER | Power Inductors - SMD | 284 | 0.0 | 0.0 | 87.0 | 0.0 | 59.5 | 0.0 | 52.8 | 7.7 | 100.0 | 0.0 | 1.8 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| MOUSER | USB Connectors | 282 | 14.5 | 0.0 | 0.4 | 5.0 | 6.0 | 0.4 | 0.0 | 4.3 | 58.9 | 0.0 | 0.0 | 100.0 | 55.7 | 0.0 | 64.5 | 63.1 |
| MOUSER | DC Fans | 162 | 0.0 | 0.6 | 0.0 | 97.5 | 9.9 | 16.7 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |

### Coverage per policy family (all distributors)

| Policy family | Parts | Cap | Res | Ind | Volt | Curr | Pwr | Tol | Pkg | Mount | Diel | Tech | ConnType | Pos | Pitch | Gender | Orient |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| CAPACITOR | 25307 | 94.0 | 3.7 | 0.0 | 93.9 | 0.0 | 13.8 | 87.8 | 97.8 | 85.8 | 20.9 | 84.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| CONNECTOR | 45038 | 0.1 | 0.0 | 0.0 | 39.8 | 51.1 | 0.0 | 0.0 | 96.2 | 64.4 | 0.0 | 0.0 | 91.0 | 71.0 | 76.8 | 43.7 | 46.9 |
| CRYSTAL | 5046 | 84.5 | 61.9 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 99.5 | 97.6 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| DEFAULT | 18841 | 0.0 | 22.2 | 0.0 | 84.2 | 63.8 | 21.3 | 0.0 | 98.2 | 86.2 | 0.0 | 0.1 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| DIODE | 23976 | 6.6 | 18.5 | 0.0 | 90.9 | 76.0 | 25.3 | 3.0 | 99.4 | 78.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| FAN | 479 | 0.0 | 0.2 | 0.0 | 99.0 | 49.9 | 7.1 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| FERRITE | 4564 | 0.0 | 0.0 | 0.0 | 0.0 | 85.1 | 0.0 | 80.6 | 98.9 | 97.7 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| INDUCTOR | 15928 | 0.0 | 0.0 | 91.9 | 0.2 | 89.8 | 0.1 | 83.7 | 96.6 | 98.1 | 0.0 | 22.3 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| LED | 5440 | 0.0 | 0.0 | 0.0 | 82.3 | 74.3 | 60.1 | 0.0 | 97.4 | 94.4 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 21.7 |
| OSCILLATOR | 4304 | 0.1 | 0.0 | 0.0 | 78.9 | 56.2 | 0.0 | 0.0 | 99.4 | 99.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| REGULATOR | 5163 | 0.0 | 0.0 | 0.0 | 89.5 | 89.0 | 0.1 | 0.7 | 99.3 | 88.5 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| RESISTOR | 19589 | 0.0 | 94.1 | 0.6 | 53.4 | 18.4 | 74.6 | 76.1 | 96.8 | 84.5 | 0.0 | 77.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| SWITCH | 5586 | 0.0 | 0.4 | 0.0 | 77.7 | 77.9 | 0.0 | 0.0 | 91.7 | 87.5 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 54.8 |
| TRANSISTOR | 10427 | 39.8 | 39.1 | 0.0 | 89.3 | 88.2 | 86.2 | 0.0 | 98.8 | 84.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |

### Typed fields per part

| Distributor | Mean attrs/part | Parts with >=3 attrs % | With Package % | With any of Voltage/Resistance/Capacitance/Inductance/Current % |
|---|---:|---:|---:|---:|
| LCSC | 8.26 | 96.1 | 99.1 | 81.3 |
| TME | 9.04 | 90.6 | 44.5 | 88.2 |
| MOUSER | 5.45 | 87.6 | 27.9 | 64.1 |

### Cardinality per attribute (all distributors)

| Attribute | Parts with value | Distinct | Top 10 values (count) |
|---|---:|---:|---|
| Package | 195075 | 5869 | 0603 (7637), 0805 (5900), 1206 (5806), SMD (4645), 0402 (4478), Plugin (3921), SOT-23 (3886), 3225 (3358), Plugin,P=2.54mm (3348), 2512 (2611) |
| Family | 189688 | 24 | connector (45038), capacitor (25307), resistor (19589), inductor (15928), fuse (8625), diode (8506), switch (5586), led (5440), mosfet (5368), schottky (5239) |
| Mounting | 163124 | 2 | SMD (123413), THT (39711) |
| Voltage | 124887 | 1083 | 50V (9701), 250V (6191), 12V (5486), 30V (4554), 16V (3932), 300V (3923), 100V (3913), 25V (3870), 200V (3557), 1kV (3226) |
| MaxTemperature | 122598 | 60 | 85°C (35678), 105°C (25969), 125°C (21440), 150°C (20691), 155°C (7270), 70°C (2487), 175°C (2302), 80°C (1671), 0°C (1093), 100°C (822) |
| OperatingTemperature | 120905 | 187 | -40...85°C (23891), -40...105°C (17074), -55...150°C (16251), -40...125°C (11559), -55...125°C (9184), -25...85°C (7950), -55...105°C (7292), -55...155°C (7199), -65...150°C (2805), -55...175°C (1738) |
| Current | 89623 | 1404 | 3A (7512), 1A (7160), 50mA (4147), 2A (4145), 1.5A (3161), 500mA (2981), 20mA (2949), 0.1uA (2612), 5A (2475), 1uA (2289) |
| Tolerance | 55567 | 108 | 20% (22911), 1% (9466), 5% (8261), 10% (4375), 25% (3624), 0.1% (740), 2% (674), 30% (543), 86% (473), 80% (414) |
| Power | 42801 | 817 | 1W (4987), 0.5W (3698), 2W (3110), 0.25W (3099), 3W (2011), 0.2W (1818), 0.1W (1520), 0.125W (1371), 0.3W (1192), 0.6W (1183) |
| ConnectorType | 41001 | 15 | connector (10172), female header (5131), wire-to-board (5049), terminal block (5039), fpc (5023), pin header (5010), usb (2606), usb-c (2332), micro usb (559), header (53) |
| Technology | 40069 | 22 | aluminium electrolytic (10213), ceramic (5983), aluminium polymer (4734), current sense (4444), thick film (4406), metal film (3259), wirewound (2752), multilayer (1477), thin film (963), carbon film (928) |
| Frequency | 36379 | 1040 | 100MHz (1598), 100kHz (1500), 1kHz (1465), 1MHz (1074), 120Hz (1053), 300kHz (1001), 16MHz (789), 32MHz (786), 25MHz (733), 24MHz (715) |
| Resistance | 35219 | 1485 | 10Mohm (1860), 100ohm (787), 60ohm (739), 40ohm (570), 10ohm (552), 50ohm (550), 30ohm (532), 100mohm (525), 10mohm (513), 80ohm (485) |
| Pitch | 34590 | 109 | 2.54mm (6085), 0.5mm (5098), 2mm (4557), 1mm (2522), 1.27mm (2331), 2.5mm (1841), 5.08mm (1695), 0.8mm (1615), 3.81mm (1353), 1.25mm (1290) |
| Dimensions | 34102 | 1792 | D6.3 x 7.7mm (928), D8 x 12mm (548), 4 x 4mm (530), 25.4 x 25.4mm (523), D2.4 x 6.3mm (512), 3 x 3mm (465), D6.3 x 8mm (464), D5 x 11mm (444), D6.3 x 5.4mm (415), D8 x 10.5mm (413) |
| Capacitance | 33837 | 1397 | 100uF (2509), 220uF (2060), 470uF (1571), 20pF (1413), 10uF (1345), 1mF (1197), 47uF (1164), 330uF (1104), 22uF (997), 12pF (714) |
| Positions | 31989 | 125 | 4 (3111), 6 (2575), 5 (1972), 8 (1966), 10 (1958), 16 (1949), 2 (1846), 3 (1626), 12 (1479), 24 (1281) |
| Orientation | 25380 | 3 | right angle (13391), vertical (11901), reverse mount (88) |
| Rows | 19861 | 6 | 1 (13256), 2 (6456), 3 (110), 4 (37), 10 (1), 15 (1) |
| Gender | 19697 | 2 | female (11964), male (7733) |
| RatedCurrent | 18183 | 685 | 300mA (634), 500mA (561), 3A (513), 200mA (509), 1A (507), 2A (479), 600mA (457), 400mA (436), 6A (353), 4A (330) |
| DCR | 17463 | 1533 | 200mohm (483), 100mohm (460), 300mohm (363), 150mohm (327), 40mohm (303), 50mohm (301), 20mohm (272), 400mohm (270), 30mohm (267), 250mohm (262) |
| Inductance | 14746 | 356 | 2.2uH (1080), 10uH (1068), 4.7uH (864), 1uH (816), 3.3uH (773), 6.8uH (570), 1.5uH (566), 22uH (506), 15uH (451), 47uH (426) |
| Subtype | 12974 | 3 | standard (8503), fixed (3784), adjustable (687) |
| Lifetime | 11350 | 60 | 2000h @105°C (5632), 5000h @105°C (951), 10000h @105°C (725), 3000h @105°C (573), 1000h @105°C (571), 2000h (527), 2000h @125°C (450), 7000h @105°C (278), 8000h @105°C (271), 2000h @85°C (267) |
| Polarity | 9274 | 5 | N-channel (3344), NPN (2715), PNP (1841), P-channel (1107), complementary (267) |
| FormFactor | 8516 | 3 | through_hole (5129), chip (3245), chassis (142) |
| Impedance | 5616 | 463 | 120ohm @100MHz (450), 600ohm @100MHz (383), 220ohm @100MHz (267), 1kohm @100MHz (239), 60ohm @100MHz (204), 30ohm @100MHz (178), 100ohm @100MHz (158), 300ohm @100MHz (153), 80ohm @100MHz (123), 470ohm @100MHz (122) |
| SwitchType | 5613 | 13 | tactile (5141), DIP (121), slide (109), toggle (108), pushbutton (55), snap action (21), accessory (19), rotary (15), IC (8), rocker (8) |
| LedType | 5440 | 8 | indicator (5310), strip (62), addressable (32), blinking (22), high power (9), accessory (3), display (1), driver (1) |
| SwitchFunction | 5429 | 12 | momentary (5146), ON-ON (136), OFF-ON (42), ON-OFF (39), OFF-(ON) (31), ON-(ON) (13), ON-OFF-ON (9), (ON)-OFF-(ON) (5), ON-OFF-(ON) (3), (ON)-OFF (2) |
| Dielectric | 5284 | 11 | X7R (2486), C0G (1854), X5R (650), X6S (86), Y5V (86), X7S (63), X7T (22), X8R (17), X8L (13), X8G (6) |
| Termination | 5280 | 6 | PCB (5175), solder lug (71), quick connect (18), screw (10), panel (5), wire leads (1) |
| MountingStyle | 5095 | 5 | SMD (3105), THT (1434), mid-mount (458), top-mount (73), hybrid (25) |
| SwitchSize | 4752 | 375 | 6x6mm (1212), 12x12mm (566), 4.5x4.5mm (465), 5.2x5.2mm (239), 6.2x6.2mm (238), 6.1x6.1mm (146), 6x3.5mm (127), 8x8mm (56), 5.1x5.1mm (52), 7.5x6mm (48) |
| LedColour | 4678 | 17 | red (886), white (726), bi-colour (544), green (530), blue (470), yellow (423), yellow green (375), orange (212), RGB (127), tri-colour (69) |
| Contacts | 4614 | 5 | SPST (4101), SPST-NO (304), SPDT (206), SPST-NC (2), DPST (1) |
| ForwardVoltage | 4468 | 120 | 2.4V (582), 2V (515), 2.2V (396), 3.4V (339), 2.1V (236), 3.2V (221), 3.3V (181), 3V (178), 2.6V (163), 3.6V (148) |
| ViewingAngle | 4286 | 53 | 120° (2041), 130° (528), 140° (286), 30° (270), 60° (216), 110° (85), 50° (82), 45° (81), 40° (69), 20° (64) |
| UsbType | 4147 | 7 | Type-C (2332), Type-A (1066), Micro-B (543), Type-B (101), Mini-B (74), Micro-AB (16), Mini-AB (15) |
| Force | 3865 | 82 | 2.5 N (255 gf) (1451), 2.6 N (265 gf) (732), 1.6 N (163 gf) (651), 1.8 N (184 gf) (461), 1 N (102 gf) (87), 2 N (204 gf) (38), 2.8 N (286 gf) (37), 3.5 N (357 gf) (32), 3 N (306 gf) (30), 1.3 N (133 gf) (27) |
| LuminousIntensity | 3771 | 477 | 200mcd (134), 100mcd (111), 80mcd (99), 150mcd (97), 180mcd (93), 50mcd (88), 800mcd (68), 45mcd (67), 120mcd (66), 600mcd (66) |
| LensType | 3761 | 3 | clear (2489), diffused (752), tinted (520) |
| PinConfiguration | 3697 | 10 | 16 (923), 4 (796), 24 (667), 5 (568), 6 (412), 9 (152), 14 (86), 12 (42), 2 (41), 10 (10) |
| Wavelength | 3649 | 258 | 570nm (240), 590nm (206), 625nm (168), 465nm (164), 605nm (125), 565nm (117), 630nm (112), 470nm (105), 525nm (95), 522.5nm (92) |
| Features | 3639 | 96 | shielded (1421), unshielded (535), mid-mount (383), power only (367), auto restart, 2-wire (107), low ESR (88), straddle-mount (81), 2-wire (58), board lock (58), tacho, 3-wire (51) |
| UsbSpeedGbps | 3197 | 5 | 0.48 (2556), 5 (567), 40 (57), 10 (14), 20 (3) |
| UsbStandard | 3197 | 7 | USB 2.0 (2556), USB 3.x (350), USB 3.2 Gen 1 (217), USB4 (55), USB 3.2 Gen 2 (14), USB 3.2 Gen 2x2 (3), Thunderbolt 4 (2) |
| Life | 3060 | 30 | 100000 cycles (1889), 50000 cycles (263), 300000 cycles (175), 200000 cycles (135), 1000000 cycles (127), 30000 cycles (116), 500000 cycles (89), 10000 cycles (38), 2000 cycles (34), 20000 cycles (32) |
| ESR | 1657 | 119 | 16mohm @100kHz (164), 14mohm @100kHz (149), 20mohm @100kHz (113), 12mohm @100kHz (82), 30mohm @100kHz (80), 40mohm @100kHz (79), 35mohm @100kHz (76), 15mohm @100kHz (68), 18mohm @100kHz (68), 25mohm @100kHz (66) |
| Series | 1074 | 7 | XH (305), PH (270), ZH (161), VH (140), GH (99), SH (80), EH (19) |
| ColourTemperature | 631 | 100 | 3000 K (82), 6500 K (67), 4000 K (63), 5000 K (50), 2700 K (34), 6000 K (29), 5700 K (28), 6250 K (25), 2950 K (15), 13250 K (13) |
| Qualification | 528 | 3 | AEC-Q200 (520), AEC-Q101 (7), AEC-Q100 (1) |
| FanSupply | 479 | 1 | DC (479) |
| FanType | 479 | 2 | axial (445), radial (34) |
| FrameSize | 478 | 74 | 40x40x10mm (86), 120x120x25mm (68), 60x60x25mm (41), 120x120x38mm (40), 40x40x20mm (37), 92x92x25mm (26), 80x80x25mm (17), 60x60x15mm (13), 60x60x20mm (7), 17x17x8mm (6) |
| IpRating | 394 | 10 | IP67 (239), IP68 (46), IP40 (43), IPX7 (42), IP65 (10), IP54 (6), IP50 (3), IP57 (2), IP60 (2), IP47 (1) |
| Bearing | 381 | 4 | ball (206), vapo (95), sleeve (63), fluid dynamic (17) |
| Airflow | 343 | 209 | 10 m³/h (5.89 CFM) (8), 11.9 m³/h (7 CFM) (8), 102 m³/h (60 CFM) (6), 184 m³/h (108 CFM) (6), 9 m³/h (5.3 CFM) (6), 127 m³/h (75 CFM) (5), 13.6 m³/h (8 CFM) (5), 8 m³/h (4.71 CFM) (5), 10.2 m³/h (6 CFM) (4), 15 m³/h (8.85 CFM) (4) |
| Speed | 322 | 86 | 6000 rpm (23), 5000 rpm (16), 3000 rpm (13), 3100 rpm (12), 4500 rpm (12), 5400 rpm (11), 2000 rpm (10), 7000 rpm (10), 1200 rpm (9), 2700 rpm (8) |
| Noise | 307 | 113 | 26 dBA (12), 28 dBA (12), 25 dBA (11), 32 dBA (11), 34 dBA (10), 39 dBA (8), 48 dBA (8), 23 dBA (7), 21 dBA (6), 22.1 dBA (6) |
| SwitchPositions | 270 | 3 | 2 (173), 8 (93), 3 (4) |
| VoltageDC | 190 | 15 | 24V (56), 28V (45), 12V (26), 30V (19), 50V (12), 20V (9), 14V (8), 6V (6), 25V (2), 5V (2) |
| Illuminated | 188 | 2 | no (157), yes (31) |
| StaticPressure | 172 | 78 | 54.8 Pa (5.59 mmH2O) (10), 69.7 Pa (7.11 mmH2O) (9), 29.9 Pa (3.05 mmH2O) (8), 11.2 Pa (1.14 mmH2O) (6), 24.9 Pa (2.54 mmH2O) (6), 44.8 Pa (4.57 mmH2O) (6), 47.3 Pa (4.83 mmH2O) (6), 37.4 Pa (3.81 mmH2O) (5), 39.8 Pa (4.06 mmH2O) (5), 62.3 Pa (6.35 mmH2O) (5) |
| VoltageAC | 129 | 9 | 250V (88), 125V (17), 20V (9), 120V (7), 230V (2), 24V (2), 60V (2), 220V (1), 240V (1) |
| Waterproof | 126 | 9 | IP67 (43), yes (31), IPX8 (15), IP68 (12), IPX7 (11), IPX6 (6), IP65 (4), IP66 (2), IPX5 (2) |
| SaturationCurrent | 119 | 76 | 2.3A (5), 5A (5), 7A (5), 1.3A (4), 9A (4), 14A (3), 2.6A (3), 8A (3), 1.41A (2), 1.8A (2) |
| RippleCurrent | 84 | 49 | 300mA (6), 180mA (5), 280mA (5), 197mA (4), 240mA (4), 91mA (4), 1.8A (3), 400mA (3), 1.4A (2), 130mA (2) |
| LuminousFlux | 70 | 55 | 14lm (3), 34.5lm (3), 6lm (3), 8lm (3), 100lm (2), 12lm (2), 250lm (2), 25lm (2), 300lm (2), 52lm (2) |
| ShieldPinsCounted | 42 | 2 | 2 (30), 1 (12) |
| Case | 17 | 5 | D (5), D8 (4), C (3), E (3), G (2) |
| Elements | 5 | 2 | array (4), 4 (1) |
| HoleDiameter | 5 | 4 | 16mm (2), 12mm (1), 22.3mm (1), 5mm (1) |

### Cardinality of attributes per family (parts with a value, distinct values, top 10)

| Family | Attribute | Parts | Distinct | Top 10 (count) |
|---|---|---:|---:|---|
| connector | Voltage | 17941 | 70 | 250V (3524), 300V (3172), 50V (3114), 30V (1571), 125V (822), 100V (627), 500V (620), 5V (513), 20V (451), 60V (419) |
| connector | Current | 23034 | 108 | 3A (5230), 1A (3000), 500mA (2260), 8A (1775), 5A (1763), 1.5A (1582), 2A (1423), 15A (1319), 10A (619), 300mA (614) |
| connector | Package | 43340 | 312 | SMD (3477), Plugin,P=2.54mm (3280), SMD,P=0.5mm,Surface Mount，Right Angle (2578), P=0.5mm (2493), SMD,P=0.5mm (2151), Plugin (1947), P=1mm (1727), Plugin,P=2mm (1606), SMD,P=0.8mm (1286), Push-Pull,P=2.54mm (1275) |
| connector | Positions | 31989 | 125 | 4 (3111), 6 (2575), 5 (1972), 8 (1966), 10 (1958), 16 (1949), 2 (1846), 3 (1626), 12 (1479), 24 (1281) |
| connector | Pitch | 34590 | 109 | 2.54mm (6085), 0.5mm (5098), 2mm (4557), 1mm (2522), 1.27mm (2331), 2.5mm (1841), 5.08mm (1695), 0.8mm (1615), 3.81mm (1353), 1.25mm (1290) |
| connector | ConnectorType | 41001 | 15 | connector (10172), female header (5131), wire-to-board (5049), terminal block (5039), fpc (5023), pin header (5010), usb (2606), usb-c (2332), micro usb (559), header (53) |
| connector | Gender | 19697 | 2 | female (11964), male (7733) |
| connector | Orientation | 21140 | 2 | right angle (12267), vertical (8873) |
| connector | Mounting | 29000 | 2 | SMD (18085), THT (10915) |
| capacitor | Capacitance | 23783 | 372 | 100uF (2509), 220uF (2060), 470uF (1571), 10uF (1345), 1mF (1197), 47uF (1164), 330uF (1104), 22uF (997), 100nF (685), 680uF (641) |
| capacitor | Resistance | 936 | 166 | 20mohm (32), 30mohm (30), 35mohm (28), 15mohm (27), 40mohm (25), 160mohm (24), 12mohm (23), 50mohm (22), 10mohm (21), 25mohm (21) |
| capacitor | Voltage | 23764 | 140 | 50V (4097), 25V (3054), 16V (2844), 1.5kV (2116), 35V (2019), 10V (1455), 6.3V (1384), 100V (1132), 63V (797), 400V (778) |
| capacitor | Power | 3484 | 56 | 1W (1216), 2W (872), 3W (297), 6W (263), 10W (245), 20W (113), 15W (86), 30W (73), 0.25W (47), 0.4W (37) |
| capacitor | Tolerance | 22219 | 61 | 20% (14152), 10% (2813), 5% (1301), 86% (422), 88% (372), 80% (337), 83% (259), 82% (241), 87% (219), 90% (210) |
| capacitor | Package | 24749 | 635 | 0603 (1513), 0805 (1165), 0402 (1124), 1206 (1033), D6.3 x 7.7mm (928), 0201 (551), D8 x 12mm (544), D6.3 x 8mm (464), D5 x 11mm (436), D6.3 x 5.4mm (415) |
| capacitor | Dielectric | 5284 | 11 | X7R (2486), C0G (1854), X5R (650), X6S (86), Y5V (86), X7S (63), X7T (22), X8R (17), X8L (13), X8G (6) |
| capacitor | Technology | 21346 | 11 | aluminium electrolytic (10213), ceramic (5983), aluminium polymer (4734), tantalum (135), tantalum polymer (65), polypropylene (62), polyester (60), film (51), supercapacitor (35), polymer (6) |
| capacitor | Mounting | 21716 | 2 | SMD (12942), THT (8774) |
| resistor | Resistance | 18440 | 1117 | 10Mohm (1860), 100ohm (312), 10ohm (306), 10mohm (296), 100mohm (278), 20mohm (246), 5mohm (245), 1mohm (242), 100Mohm (237), 2mohm (237) |
| resistor | Inductance | 115 | 64 | 51uH (7), 5mH (7), 1mH (6), 10mH (5), 11uH (4), 20mH (4), 25mH (4), 2mH (4), 100uH (3), 22uH (3) |
| resistor | Voltage | 10451 | 107 | 200V (1711), 50V (1684), 150V (1027), 125V (890), 250V (831), 75V (804), 500V (734), 350V (605), 80V (331), 1.5kV (265) |
| resistor | Current | 3606 | 240 | 300mA (207), 200mA (172), 400mA (169), 5A (160), 1A (158), 3A (155), 2A (148), 4A (128), 100mA (113), 500mA (109) |
| resistor | Power | 14608 | 72 | 0.25W (2390), 1W (1991), 2W (1638), 3W (1389), 0.125W (1358), 0.1W (1203), 0.5W (1150), 0.0625W (686), 5W (518), 0.75W (298) |
| resistor | Tolerance | 14908 | 15 | 1% (9262), 5% (4413), 0.1% (740), 0.5% (286), 2% (121), 10% (27), 0.25% (14), 3% (11), 20% (10), 0.02% (8) |
| resistor | Package | 18954 | 680 | 2512 (2595), 1206 (1908), 0805 (1523), 0603 (1463), 0402 (942), Plugin (531), Plugin,D2.4xL6.3mm (495), SMD-4P,2x1.2mm (336), 2010 (312), SMD-4P,4.5x3.2mm (303) |
| resistor | Technology | 15087 | 9 | current sense (4444), thick film (4406), metal film (3259), carbon film (928), thin film (906), wirewound (731), metal oxide (405), metal foil (6), metal strip (2) |
| resistor | Mounting | 16550 | 2 | SMD (10633), THT (5917) |
| inductor | Inductance | 14630 | 328 | 2.2uH (1080), 10uH (1066), 4.7uH (864), 1uH (815), 3.3uH (773), 6.8uH (570), 1.5uH (566), 22uH (503), 15uH (450), 47uH (426) |
| inductor | RatedCurrent | 14299 | 684 | 300mA (402), 600mA (336), 400mA (330), 500mA (283), 1A (272), 700mA (249), 200mA (211), 2A (206), 4A (205), 3A (198) |
| inductor | Tolerance | 13331 | 14 | 20% (8740), 5% (1996), 10% (1522), 30% (492), 2% (306), 3% (223), 15% (29), 25% (10), 1% (8), 18% (1) |
| inductor | Package | 15384 | 709 | 0603 (1296), 0402 (1152), 0805 (1143), 1008 (970), 0201 (758), 1210 (650), SMD,4x4mm (511), SMD,3x3mm (465), 0806 (456), SMD,7x6.6mm (398) |
| inductor | Technology | 3555 | 3 | wirewound (2021), multilayer (1477), thin film (57) |
| inductor | Mounting | 15619 | 2 | SMD (15316), THT (303) |
| fuse | Resistance | 4184 | 221 | 150mohm (203), 100mohm (189), 20mohm (153), 10mohm (140), 250mohm (128), 15mohm (122), 120mohm (120), 1.5ohm (100), 1.4ohm (99), 1ohm (94) |
| fuse | Voltage | 7059 | 61 | 250V (1187), 6V (601), 60V (598), 32V (597), 16V (583), 30V (550), 125V (482), 24V (441), 33V (262), 12V (235) |
| fuse | Current | 7241 | 133 | 100A (811), 1.5A (573), 1A (469), 2A (367), 10A (350), 3A (286), 1.1A (275), 50A (253), 100mA (228), 200mA (193) |
| fuse | Power | 3995 | 108 | 0.5W (615), 0.8W (611), 0.6W (560), 1W (417), 1.5W (350), 1.2W (237), 2W (147), 2.5W (105), 0.9W (97), 0.7W (85) |
| fuse | Package | 8443 | 186 | 1206 (1640), 0603 (873), 1812 (851), 0805 (633), Plugin,P=5.1mm (632), 2920 (463), 1210 (428), 2410 (411), Plugin (273), Plugin,P=5.08mm (252) |
| fuse | Mounting | 7652 | 2 | SMD (6132), THT (1520) |
| diode | Voltage | 7790 | 55 | 1kV (2535), 600V (1866), 200V (885), 400V (864), 800V (591), 100V (254), 1.2kV (171), 50V (140), 300V (124), 1.6kV (73) |
| diode | Current | 7780 | 170 | 1A (1682), 2A (1085), 10uA (539), 3A (482), 100A (397), 150A (335), 10A (300), 30A (242), 15A (231), 25A (175) |
| diode | Power | 195 | 88 | 1.47W (8), 165W (7), 90W (6), 0.15W (5), 0.225W (5), 0.2W (5), 0.35W (5), 1.66W (5), 1.76W (5), 125W (5) |
| diode | Package | 8484 | 386 | SMA (758), SMB (580), GBU (491), SMC (362), SOD-123FL (341), SMAF (314), ABS (225), DO-41 (199), GBJ (192), KBP (180) |
| diode | Mounting | 4669 | 2 | SMD (3290), THT (1379) |
| switch | Voltage | 4342 | 28 | 12V (3865), 24V (147), 250V (83), 15V (39), 32V (31), 30V (30), 16V (22), 125V (21), 5V (17), 35V (14) |
| switch | Current | 4351 | 31 | 50mA (3949), 25mA (77), 100mA (61), 20mA (56), 3A (40), 2A (24), 300mA (18), 500mA (12), 10mA (11), 5A (11) |
| switch | Package | 5122 | 466 | SMD-4P,6x6mm (445), Plugin-4P,6x6mm (325), SMD (287), Plugin (265), SMD-4P,12x12mm (245), SMD-4P,5.2x5.2mm (200), SMD-4P,4.5x4.5mm (194), Plugin-4P,12x12mm (188), DIP-4P (175), SMD,6x6mm (114) |
| switch | Orientation | 3062 | 2 | vertical (2210), right angle (852) |
| switch | Mounting | 4887 | 2 | SMD (3114), THT (1773) |
| led | Voltage | 4475 | 124 | 2.4V (580), 2V (513), 2.2V (395), 3.4V (337), 2.1V (236), 3.2V (216), 3.3V (180), 3V (168), 2.6V (163), 3.6V (147) |
| led | Current | 4040 | 54 | 20mA (2509), 5mA (373), 10mA (180), 60mA (161), 150mA (120), 2mA (110), 25mA (91), 350mA (90), 30mA (81), 50mA (68) |
| led | Power | 3268 | 159 | 0.06W (464), 0.075W (393), 0.05W (212), 0.07W (210), 0.072W (203), 0.1W (105), 0.095W (89), 0.048W (87), 0.12W (85), 0.078W (76) |
| led | Package | 5297 | 320 | 0603 (845), 0805 (397), 5mm (385), 1206 (345), 3mm (294), 2835 (290), SMD (214), 0402 (184), 5050 (182), 3528 (177) |
| led | Orientation | 1178 | 3 | vertical (818), right angle (272), reverse mount (88) |
| led | Mounting | 5136 | 2 | SMD (4188), THT (948) |
| mosfet | Capacitance | 4148 | 1132 | 120pF (79), 100pF (76), 1.5nF (58), 110pF (53), 25pF (53), 13pF (51), 105pF (49), 10pF (49), 1.3nF (47), 17pF (47) |
| mosfet | Resistance | 4074 | 432 | 25mohm (74), 30mohm (72), 22mohm (70), 11mohm (64), 12mohm (62), 18mohm (62), 50mohm (62), 4mohm (60), 5mohm (60), 60mohm (58) |
| mosfet | Voltage | 4639 | 68 | 30V (1162), 60V (807), 20V (612), 100V (547), 40V (399), 650V (218), 600V (123), 200V (100), 80V (81), 150V (75) |
| mosfet | Current | 4515 | 425 | 50A (138), 4A (134), 80A (128), 20A (123), 6A (121), 100A (120), 60A (111), 3A (101), 30A (96), 10A (91) |
| mosfet | Power | 4354 | 564 | 2.5W (143), 2W (135), 0.35W (101), 1.25W (89), 1.5W (88), 1.4W (87), 1W (87), 60W (68), 100W (65), 136W (62) |
| mosfet | Package | 5286 | 483 | SOT-23 (797), TO-252 (605), SOP-8 (266), SO-8 (217), TO-220F (184), TO-263 (179), DFN-8 (155), TO-220AB (146), TO-220 (141), TO-252-2L (99) |
| mosfet | Technology | 54 | 1 | GaN (54) |
| mosfet | Mounting | 4195 | 2 | SMD (3435), THT (760) |
| schottky | Voltage | 4625 | 39 | 40V (1193), 100V (848), 60V (608), 30V (601), 200V (379), 150V (250), 20V (219), 45V (208), 70V (81), 80V (63) |
| schottky | Current | 4607 | 136 | 1A (784), 3A (552), 2A (548), 200mA (367), 10A (331), 150A (285), 100A (190), 100mA (158), 120A (115), 200A (111) |
| schottky | Package | 5174 | 270 | SMA (597), SMB (395), SOD-123FL (353), SOD-323 (326), SOD-123 (262), SMC (259), SOT-23 (217), SMAF (202), SOD-523 (160), TO-220F (158) |
| schottky | Mounting | 4628 | 2 | SMD (4017), THT (611) |
| tvs | Capacitance | 1567 | 227 | 390pF (112), 15pF (95), 1pF (72), 0.3pF (62), 0.5pF (55), 30pF (39), 0.8pF (37), 10pF (37), 0.35pF (35), 60pF (32) |
| tvs | Voltage | 4750 | 231 | 12V (302), 15V (289), 24V (244), 5V (234), 10V (159), 20V (151), 36V (151), 18V (129), 16V (116), 33V (113) |
| tvs | Current | 4670 | 409 | 1uA (1806), 0.1uA (319), 5uA (273), 2uA (167), 0.5uA (161), 0.2uA (131), 10uA (95), 0.08uA (66), 0.01uA (65), 0.001uA (58) |
| tvs | Power | 1247 | 91 | 600W (324), 400W (213), 1.5kW (162), 200W (124), 3kW (63), 5kW (55), 350W (23), 150W (22), 60W (18), 500W (17) |
| tvs | Package | 5161 | 245 | SMB (519), SMC (516), SMA (441), SOD-123FL (414), SOD-323 (321), DO-214AA (314), DO-214AB (286), DO-214AC (235), SOT-23 (219), SOD-523 (154) |
| tvs | Mounting | 4867 | 2 | SMD (4668), THT (199) |
| regulator | Voltage | 4622 | 182 | 3.3V (634), 1.8V (334), 1.2V (300), 15V (206), 3V (202), 2.5V (174), 12V (167), 2.8V (156), 24V (137), 18V (135) |
| regulator | Current | 4593 | 192 | 100mA (496), 1A (476), 300mA (459), 150mA (430), 200mA (308), 500mA (247), 1.5A (236), 250mA (158), 1.5uA (155), 800mA (104) |
| regulator | Package | 5128 | 409 | SOT-23-5 (802), SOT-89-3 (345), SOT-89 (262), SOT-23 (226), SOT-223 (210), SOT-23-5L (162), SOT-23-3 (141), DFN-4 (121), TO-252 (107), SOT-23-3L (96) |
| regulator | Mounting | 4569 | 2 | SMD (4284), THT (285) |
| mcu | Voltage | 3980 | 64 | 1.8V (1048), 2V (424), 1.71V (408), 2.7V (364), 2.3V (251), 3V (193), 1.62V (149), 1.7V (127), 2.4V (111), 1.65V (88) |
| mcu | Package | 5027 | 619 | LQFP-64 (409), LQFP-48 (328), LQFP-100 (327), LQFP-32 (150), TQFP-64 (147), TSSOP-20 (131), LQFP-144 (127), TQFP-44 (98), SSOP-28-208MIL (86), TQFP-100 (81) |
| mcu | Mounting | 4170 | 2 | SMD (4053), THT (117) |
| transistor | Voltage | 4669 | 67 | 45V (685), 40V (622), 50V (518), 25V (375), 60V (353), 80V (317), 30V (265), 100V (180), 65V (179), 160V (165) |
| transistor | Current | 4679 | 95 | 0.1uA (1822), 100mA (813), 1.5A (252), 200mA (240), 1uA (209), 1A (146), 0.05uA (144), 0.01uA (142), 10uA (138), 100uA (94) |
| transistor | Power | 4632 | 160 | 0.2W (862), 0.3W (629), 0.5W (400), 0.25W (236), 0.15W (227), 0.625W (225), 1W (205), 0.35W (198), 0.225W (183), 2W (127) |
| transistor | Package | 5019 | 217 | SOT-23 (1866), SOT-89 (459), SOT-323 (277), TO-92 (258), SOT-223 (249), SOT-363 (212), SOT-89-3L (112), SOT-523 (111), TO-252 (95), TO-126 (59) |
| transistor | Mounting | 4565 | 2 | SMD (3998), THT (567) |
| crystal | Capacitance | 4263 | 44 | 20pF (1309), 12pF (615), 10pF (551), 18pF (365), 8pF (331), 9pF (302), 15pF (192), 12.5pF (150), 16pF (114), 7pF (102) |
| crystal | Resistance | 3121 | 47 | 60ohm (680), 50ohm (464), 40ohm (446), 80ohm (379), 30ohm (327), 100ohm (226), 150ohm (68), 70ohm (68), 120ohm (66), 70kohm (58) |
| crystal | Package | 5021 | 63 | 3225 (1809), 2016 (519), HC-49S (502), HC-49S-SMD (441), 5032 (438), 2520 (415), 1612 (226), 3215 (95), HC-49S-SMD-2P-Mini (87), DT-26 (64) |
| crystal | Mounting | 4923 | 2 | SMD (3704), THT (1219) |
| comparator | Voltage | 4758 | 212 | 2.75V (799), 18V (394), 1.8V (266), 15V (230), 16V (213), 2.5V (160), 8V (153), 5V (127), 3V (124), 6V (122) |
| comparator | Current | 4747 | 339 | 0.000001uA (591), 0.00001uA (472), 0.0000025uA (197), 0.0000005uA (161), 0.0001uA (114), 0.01uA (103), 0.02uA (103), 100mA (103), 0.1uA (101), 20mA (97) |
| comparator | Package | 5024 | 251 | SOIC-8 (684), SOT-23-5 (670), SOP-8 (606), TSSOP-14 (395), MSOP-8 (352), SOIC-14 (264), SOP-14 (251), VSSOP-8 (171), SC-70-5 (168), TSSOP-8 (132) |
| comparator | Mounting | 4406 | 2 | SMD (4154), THT (252) |
| zener | Resistance | 4425 | 170 | 15ohm (239), 100ohm (227), 10ohm (223), 600ohm (211), 150ohm (170), 30ohm (149), 200ohm (140), 500ohm (121), 250ohm (120), 20ohm (111) |
| zener | Voltage | 4632 | 428 | 10V (153), 11.4V (149), 22.8V (114), 4.8V (103), 16.8V (92), 3.1V (84), 5.2V (81), 13.8V (80), 5.32V (70), 12.4V (67) |
| zener | Current | 1170 | 38 | 0.1uA (349), 1uA (158), 5uA (156), 0.5uA (116), 10uA (70), 3uA (63), 2uA (59), 0.05uA (40), 0.7uA (26), 0.2uA (25) |
| zener | Power | 4612 | 38 | 0.5W (1431), 0.2W (737), 1W (611), 0.3W (499), 0.35W (293), 0.25W (141), 5W (120), 3W (114), 0.15W (95), 1.5W (95) |
| zener | Tolerance | 682 | 5 | 5% (525), 2% (135), 10% (12), 1% (9), 20% (1) |
| zener | Package | 5008 | 109 | SOD-123 (1148), SOD-323 (858), SOT-23 (512), SMA (317), SOD-523 (317), SMB (218), LL-34 (165), DO-41 (135), DO-35 (114), DO-214AC (104) |
| zener | Mounting | 4599 | 2 | SMD (4290), THT (309) |
| ferrite | RatedCurrent | 3884 | 127 | 3A (315), 200mA (298), 500mA (278), 2A (273), 1A (235), 300mA (232), 6A (184), 100mA (146), 1.5A (134), 4A (125) |
| ferrite | Tolerance | 3677 | 7 | 25% (3613), 30% (49), 20% (5), 40% (5), 35% (3), 10% (1), 5% (1) |
| ferrite | Package | 4512 | 75 | 0603 (1495), 0805 (964), 0402 (843), 1206 (629), 0201 (159), 1210 (100), 1812 (75), 1806 (37), SMD (21), Plugin (16) |
| ferrite | Mounting | 4458 | 2 | SMD (4425), THT (33) |
| oscillator | Voltage | 3398 | 22 | 3.3V (1324), 1.8V (1024), 2.5V (404), 1.62V (362), 5V (80), 1.6V (79), 3V (37), 1.2V (36), 1.7V (11), 1.71V (10) |
| oscillator | Current | 2417 | 68 | 10mA (1161), 20mA (231), 5mA (222), 7mA (92), 25mA (61), 15mA (58), 40mA (56), 35mA (44), 4mA (34), 30mA (29) |
| oscillator | Package | 4280 | 24 | 3225 (1513), 7050 (1017), 5032 (703), 2520 (579), 2016 (297), 1612 (96), SMD5234-6P (12), VSON-4 (9), 3215 (8), DIP-14,20.5x13mm (6) |
| oscillator | Mounting | 4261 | 2 | SMD (4243), THT (18) |
| fan | Voltage | 474 | 4 | 12V (298), 5V (93), 24V (78), 48V (5) |
| fan | Current | 239 | 108 | 80mA (11), 100mA (9), 120mA (7), 130mA (7), 140mA (6), 50mA (6), 90mA (6), 170mA (5), 180mA (5), 200mA (5) |

### Normalisation of the primary value

| Family | Parts with value | Distinct SI doubles (all parts) | Distinct after rounding to 9 significant digits | Single-value descriptions: distinct SI doubles | Distinct raw spellings | Spellings per SI double (mean) | Max spellings for one double |
|---|---:|---:|---:|---:|---:|---:|---:|
| capacitor | 23783 | 373 | 372 | 371 | 407 | 1.10 | 6 |
| resistor | 18440 | 1118 | 1117 | 1073 | 1191 | 1.11 | 9 |
| inductor | 14630 | 332 | 328 | 332 | 356 | 1.07 | 7 |

capacitor: SI values with the most raw spellings

| SI value | Spellings |
|---:|---|
| 1.0000000000000001E-7 | 0.1 uF, 0.10uF, 0.1uF, 100 nF, 100000pF, 100nF |
| 9.999999999999999E-6 | 10 uF, 10.0UF, 10UF, 10uF |
| 3.3000000000000004E-8 | 0.033 uF, 0.033uF, 33nF |
| 2.2E-5 | 22 uF, 22.0uF, 22uF |
| 9.999999999999999E-5 | 100 uF, 100UF, 100uF |
| 1.0E-9 | 1000pF, 1nF |
| 1.5000000000000002E-9 | 1.5nF, 1500pF |
| 2.2000000000000003E-9 | 2.2nF, 2200pF |

resistor: SI values with the most raw spellings

| SI value | Spellings |
|---:|---|
| 10.0 | 10    OHM, 10 OHM, 10 OHMS, 10 Ohm, 10 Ohms, 10R, 10ohm, 10ohms, 10Ω |
| 100.0 | 100   OHM, 100 Ohm, 100 Ohms, 100 ohm, 100Ohms, 100R, 100ohm, 100ohms, 100Ω |
| 5360.0 | 5.36 kOhms, 5.36K, 5.36Kohm, 5.36Kohms, 5.36kOhm, 5.36kOhms, 5.36kΩ, 5K36 |
| 4700.0 | 4.7 kOhms, 4.7K, 4.7Kohms, 4.7kOhms, 4.7kΩ, 4K7, 4k7 |
| 4750.0 | 4.75 kOhms, 4.75K, 4.75Kohm, 4.75Kohms, 4.75kOhms, 4.75kΩ, 4K75 |
| 0.12 | 0.12Ω, 120mOhm, 120mOhms, 120mΩ |
| 0.26 | 0.26Ω, 260mOhms, 260mΩ |
| 0.45 | 450mOhm, 450mOhms, 450mΩ |

inductor: SI values with the most raw spellings

| SI value | Spellings |
|---:|---|
| 9.999999999999999E-6 | 10   UH, 10 UH, 10 uH, 10.00 uH, 10.0uH, 10uH, 10uh |
| 2.2E-6 | 2.2  UH, 2.20 uH, 2.20uH, 2.2UH, 2.2uH, 2.2uh |
| 3.2999999999999997E-6 | 3.3  UH, 3.3 uH, 3.3UH, 3.3uH |
| 1.0E-8 | 10.0nH, 10nH |
| 2.2E-7 | 0.22uH, 220nH |
| 3.3E-7 | 0.33uH, 330nH |
| 4.6999999999999995E-7 | 0.47uH, 470nH |
| 4.7000000000000005E-7 | 0.47uH, 470nH |

### Gaps: primary value not extracted though the description shows one

| Family / distributor | Parts with a visible value but none extracted | Parts of the family (or category) | Gap % |
|---|---:|---:|---:|
| capacitor/LCSC | 16 | 23906 | 0.1 |
| capacitor/MOUSER | 6 | 660 | 0.9 |
| inductor/LCSC | 20 | 15247 | 0.1 |
| inductor/MOUSER | 13 | 339 | 3.8 |

| Family/distributor : class | Parts |
|---|---:|
| capacitor/LCSC : multi-value list | 16 |
| capacitor/MOUSER : value glued to tolerance | 6 |
| inductor/LCSC : multi-value list | 19 |
| inductor/LCSC : other | 1 |
| inductor/MOUSER : other | 2 |
| inductor/MOUSER : value glued to tolerance | 11 |

| # | Dist | Family | Class | Part | Category | Package | Description |
|---:|---|---|---|---|---|---|---|
| 1 | LCSC | capacitor | multi-value list | C5254857 | Capacitors / Multilayer Ceramic Capacitors MLCC - SMD/SMT | 0805 | 25V、25V、25V、50V、50V 2pF、300pF、620pF、22nF、2.2uF X7R、C0G、X5R、C0G、C0G ±20%、±10%、±5%、±5%、±2% |
| 2 | MOUSER | capacitor | value glued to tolerance | 187-CL31B226KPHNFNE | Multilayer Ceramic Capacitors MLCC - SMD/SMT |  | Multilayer Ceramic Capacitors MLCC - SMD/SMT 22uF+/-10% 10V X7R 3 1206 |
| 3 | LCSC | inductor | other | C19268484 | Inductors, Coils, Chokes / Inductors (SMD) | SMD,3.2x2.5mm | -40℃~+125℃ 22uH@1mHz 620mΩ |
| 4 | MOUSER | inductor | value glued to tolerance | 652-SRP5030CC-2R2M | Power Inductors - SMD |  | Power Inductors - SMD Ind,5.7x5.2x2.8mm,2.2uH+/-20%,7A,shd |
| 5 | LCSC | capacitor | multi-value list | C5438472 | Capacitors / Polymer Aluminum Capacitors | Plugin,D5xL8mm | -55℃~+105℃、-40℃~+85℃、-40℃~+85℃ -、Molded Inductor 2000hrs 20pF、56pF、180uF 2mm 5mm 6.3V、50V、50V、50V 8mm ±20%、±20%、±5%、±1% 插件,D5xL8mm |
| 6 | MOUSER | capacitor | value glued to tolerance | 187-CL31B226KPHNNNE | Multilayer Ceramic Capacitors MLCC - SMD/SMT |  | Multilayer Ceramic Capacitors MLCC - SMD/SMT 22uF+/-10% 10V X7R 3 1206 |
| 7 | LCSC | inductor | multi-value list | C41413056 | Inductors, Coils, Chokes / Molding Power Inductors | SMD,4.4x4.2mm | 20mΩ、87mΩ 3.3A、8.6A 3.3uH、4.7uH 4A、12.5A Molded Inductor、Molded Inductor ±20%、±20% |
| 8 | MOUSER | inductor | value glued to tolerance | 652-SRP5030WA-3R3M | Power Inductors - SMD |  | Power Inductors - SMD Ind,5.5x5.25x2.8mm,3.3uH+/-20%,6A,shd |
| 9 | LCSC | capacitor | multi-value list | C41361076 | Capacitors / Multilayer Ceramic Capacitors MLCC - SMD/SMT | 0402 | 15pF、100pF 50V、50V C0G、C0G ±25%、±2%、±1% |
| 10 | MOUSER | capacitor | value glued to tolerance | 187-CL31B226KPHNNWE | Multilayer Ceramic Capacitors MLCC - SMD/SMT |  | Multilayer Ceramic Capacitors MLCC - SMD/SMT 22uF+/-10% 10V X7R 3 1206 |
| 11 | LCSC | inductor | multi-value list | C42428099 | Inductors, Coils, Chokes / Power Inductors | SMD,6x6mm | 1.8A、4.6A 14mΩ、89mΩ 2.05A、6.75A 2.2uH、22uH Magnetic Shielded Inductor、Magnetic Shielded Inductor ±20%、±20% |
| 12 | MOUSER | inductor | other | 187-CIGT201210UHR47M | RF Inductors - SMD |  | RF Inductors - SMD CIGT,Thin Film,0805,0.47uH,1.0?,7 embossed,-20 20% |
| 13 | LCSC | capacitor | multi-value list | C45359913 | Capacitors / Multilayer Ceramic Capacitors MLCC - SMD/SMT | 0402 | 25V、35V、50V 7pF、10uF、47uF C0G |
| 14 | MOUSER | capacitor | value glued to tolerance | 187-CL31B226KQHNNNE | Multilayer Ceramic Capacitors MLCC - SMD/SMT |  | Multilayer Ceramic Capacitors MLCC - SMD/SMT 22uF+/-10% 6.3V X7R 1206 |
| 15 | LCSC | inductor | multi-value list | C46956535 | Inductors, Coils, Chokes / Molding Power Inductors | SMD,4.4x4.2mm | 1.3A、1.9A、2A、2.2A、2.8A、2.8A、3A、3.2A、3.8A、4A、4A、4A、5A、6.4A、7A、8A、8.5A、11A、15A 1.6A、2A、2.4A、2.5A、2.8A、3.68A、4A、4A、4.2A、4.5A、5.2A、5.5A、5.6A、7.2A、8A、8A、9A、13A、18A 1uH、1uH、2.2uH、2.2uH、2.2uH、2.2uH、3.3uH、3.3uH、4.7uH、4.7uH、4.7uH... |
| 16 | MOUSER | inductor | other | 187-CIGT201210UHR24M | RF Inductors - SMD |  | RF Inductors - SMD CIGT,Thin Film,0805,0.24uH,1.0?,7 embossed,-20 20% |
| 17 | LCSC | capacitor | multi-value list | C46527511 | Capacitors / Aluminum Electrolytic Capacitors - SMD | SMD,D6.3xL5.4mm | -55℃~+105℃、-55℃~+105℃ -、- 16V、16V 2000hrs@105℃、2000hrs@105℃ 47uF、100uF 5.4mm、5.4mm 55mA@120Hz、70mA@120Hz 6.3mm、6.3mm ±20%、±20% |
| 18 | MOUSER | capacitor | value glued to tolerance | 187-CL31B226KQHNNWE | Multilayer Ceramic Capacitors MLCC - SMD/SMT |  | Multilayer Ceramic Capacitors MLCC - SMD/SMT 22uF+/-10% 6.3V X7R 1206 |
| 19 | LCSC | inductor | multi-value list | C48689556 | Inductors, Coils, Chokes / Molding Power Inductors | SMD,5.4x5.2mm | 1.05A、1.6A、1.6A、2A、2.3A、2.4A、2.5A、2.5A、2.5A、2.6A、3.4A、4.5A、4.5A、4.6A、5.5A、5.5A、6A 1.55A、1.7A、1.9A、2A、2.6A、2.8A、3A、3A、3.3A、3.7A、4.3A、4.6A、4.7A、5.3A、5.7A、6.2A、6.3A 19mΩ、22mΩ、25mΩ、25mΩ、31mΩ、35mΩ、45mΩ、62mΩ、62mΩ、86mΩ、105mΩ、12... |
| 20 | MOUSER | inductor | value glued to tolerance | 652-SRN2510BTA-3R3M | Power Inductors - SMD |  | Power Inductors - SMD Ind,2x2.5x0.9mm,3.3uH+/-20%,1.7A,shd |
| 21 | LCSC | capacitor | multi-value list | C46527537 | Capacitors / Aluminum Electrolytic Capacitors - SMD | SMD,D6.3xL7.7mm | -55℃~+105℃、-55℃~+105℃ -、- 100uF、470uF 2000hrs@105℃、2000hrs@105℃ 35V、35V 6.3mm、10mm 7.7mm、10.5mm 85mA@120Hz、310mA@120Hz ±20%、±20% |
| 22 | MOUSER | capacitor | value glued to tolerance | 187-CL31B226MPHNNNE | Multilayer Ceramic Capacitors MLCC - SMD/SMT |  | Multilayer Ceramic Capacitors MLCC - SMD/SMT 22uF+/-20% 10V X7R 3 1206 |
| 23 | LCSC | inductor | multi-value list | C48945764 | Inductors, Coils, Chokes / Molding Power Inductors | 1210 | 100nH、100nH、470nH、680nH、1uH、1uH、1.5uH、1.5uH、1.5uH、2.2uH、2.2uH、2.2uH、4.7uH、6.8uH、10uH、10uH、10uH 2.3A、2.4A、2.4A、2.4A、3.7A、4.4A、4.4A、4.9A、4.9A、5.5A、5.6A、6.7A、7.5A、7.7A、8.4A、12.2A、12.2A 2.5A、2.5A、2.5A、3A、4.2A、5.2A、5.2A、5.3A、... |
| 24 | MOUSER | inductor | value glued to tolerance | 652-SRN3010BTA-2R2M | Power Inductors - SMD |  | Power Inductors - SMD Ind,3x3x0.9mm,2.2uH+/-20%,2.6A,shd |
| 25 | LCSC | capacitor | multi-value list | C46527540 | Capacitors / Aluminum Electrolytic Capacitors - SMD | SMD,D8xL10.5mm | -55℃~+105℃、-55℃~+105℃ -、- 10uF、220uF 2000hrs@105℃、2000hrs@105℃ 32mA@120Hz、220mA@120Hz 35V、50V 5.4mm、10.5mm 6.3mm、8mm ±20%、±20% |
| 26 | LCSC | inductor | multi-value list | C51484129 | Inductors, Coils, Chokes / Power Inductors | SMD,8x7.1mm | 4uH、200uH 700mA、4.8A 90mΩ、800mΩ Unshielded Inductor ±10% |
| 27 | MOUSER | inductor | value glued to tolerance | 652-SRN3010BTA-3R3M | Power Inductors - SMD |  | Power Inductors - SMD Ind,3x3x0.9mm,3.3uH+/-20%,2.2A,shd |
| 28 | LCSC | capacitor | multi-value list | C46628689 | Capacitors / Aluminum Electrolytic Capacitors - SMD | SMD,D6.3xL5.7mm | -55℃~+105℃、-55℃~+105℃ -、- 100uF、470uF 150mA@120Hz、180mA@120Hz 2000hrs@105℃、2000hrs@105℃ 5.7mm、5.7mm 6.3V、25V 6.3mm、6.3mm ±20%、±20% |
| 29 | LCSC | inductor | multi-value list | C52766604 | Inductors, Coils, Chokes / Power Inductors | Plugin | 130mΩ、180mΩ 5mH、9mH Wire-wound inductor 插件 |
| 30 | MOUSER | inductor | value glued to tolerance | 652-SRN3015BTA-2R2M | Power Inductors - SMD |  | Power Inductors - SMD Ind,3x3x1.3mm,2.2uH+/-20%,2.5A,shd |

### Primary value missing (any reason): family parts without Capacitance / Resistance / Inductance

| Family | Dist | Parts | Missing | Missing % | Of which no value-like token in the description | Of which blank description |
|---|---|---:|---:|---:|---:|---:|
| capacitor | LCSC | 23906 | 1498 | 6.3 | 1482 | 1112 |
| capacitor | TME | 741 | 0 | 0.0 | 0 | 0 |
| capacitor | MOUSER | 660 | 26 | 3.9 | 20 | 0 |
| resistor | LCSC | 18724 | 1132 | 6.0 | 1132 | 1000 |
| resistor | TME | 559 | 2 | 0.4 | 2 | 0 |
| resistor | MOUSER | 306 | 15 | 4.9 | 15 | 0 |
| inductor | LCSC | 15247 | 1250 | 8.2 | 1230 | 1219 |
| inductor | TME | 342 | 0 | 0.0 | 0 | 0 |
| inductor | MOUSER | 339 | 48 | 14.2 | 35 | 0 |

Examples of family parts with no primary value and no value-like token (hash-picked):

| Family | Dist | Part | Category | Description |
|---|---|---|---|---|
| capacitor | LCSC | C178392 | Capacitors / Aluminum Electrolytic Capacitors - Leaded | 插件,D5xL11mm |
| capacitor | LCSC | C3002421 | Capacitors / Aluminum Electrolytic Capacitors - Leaded | 插件,D6.3xL7mm |
| capacitor | LCSC | C29902818 | Capacitors / Aluminum Electrolytic Capacitors - Leaded | 插件,D5xL11mm |
| capacitor | LCSC | C41430159 | Capacitors / Aluminum Electrolytic Capacitors - Leaded | 插件,D5xL11mm |
| capacitor | MOUSER | 77-GA603Y104JXJAC31G | Multilayer Ceramic Capacitors MLCC - SMD/SMT | Multilayer Ceramic Capacitors MLCC - SMD/SMT GA0603Y104JXJAC31G |
| capacitor | MOUSER | 77-VJ0603Y104KCQAT | Multilayer Ceramic Capacitors MLCC - SMD/SMT | Multilayer Ceramic Capacitors MLCC - SMD/SMT VJ0603Y104KCQAT00 |
| resistor | LCSC | C503288 | Resistors / Current Sense Resistors / Shunt Resistors | 3W 60mV 80A Shunt Through-hole ±1% 插件 |
| resistor | LCSC | C22450717 | Resistors / Current Sense Resistors / Shunt Resistors | 100A 75mV Screw Shunt ±0.5% ±25ppm/℃ |
| resistor | LCSC | C25168639 | Resistors / Current Sense Resistors / Shunt Resistors | 600A |
| resistor | LCSC | C25168640 | Resistors / Current Sense Resistors / Shunt Resistors | 600A |
| inductor | MOUSER | 963-LBR2012T100M | Power Inductors - SMD | Power Inductors - SMD PLEASE SEE SUGGESTED ALTERNATE LSQEA201212T100M |

### Suspicious extracted values

| Check | Parts | Examples (part: value <- description) |
|---|---:|---|
| capacitor Tolerance >= 50% | 3665 | C91706: 77% <- -40℃~+85℃ 1 1.5kV 10.8V~13.2V 100mVp-p 10mm 16mA 19.5mm 1W 200mA 47uF 5V 6mm 77% Short Circuit Protection、自动故障; C234067: 80% <- -40℃~+85℃ 1 1.5kV 10.1mm 100kHz 11.6mm 1W 21.6V~26.4V 220uF 24V 42mA 6mm 75mVp-p 80% Short Circuit Protection; C266374: 90.5% <- 40℃~+85℃ 1 1.5A 3300uF 500kHz 5V 7.5W 75mVpp 7V~36V 90.5% Short Circuit Protection |
| transistor Current in uA | 2801 | C2083: 0.05uA <- -55℃~+150℃ 1 NPN 160V 200 200mV 300MHz 50nA 600mA 625mW 6V NPN; C8531: 0.1uA <- -55℃~+150℃ 1 NPN 100nA 120MHz 160 200mW 30V 500mV 5V 800mA NPN; C8537: 0.1uA <- 1 NPN 100nA 120 150mA 150mW 250mV 50V 5V 80MHz NPN |
| comparator Current in uA | 3407 | C27392: 0.0015uA <- -22V~22V -40℃~+105℃ 1 1.5nA 1.8mA 10V/us 10mA 15uV/℃ 1MHz 25nV/√Hz@1kHz 2mV 75nA 96dB Decompensated stable; C43122: 0.005uA <- -40℃~+105℃ 1.8V~12V 10mV 1V/us 2 2.2MHz 20nV/√Hz@1kHz 2uV/℃ 5nA 80mA 80nA 900uA 90dB Rail-to-Rail Input, Rail-; C44371: 0.2uA <- -40℃~+85℃ -5.5V~5.5V 1.5mV 100MHz 2 200nA 22uV/℃ 3.2V~11V 560V/us 5uA 7.8mA 75mA 80dB 9.2nV/√Hz@1MHz Built-in  |
| resistor Inductance | 115 | C2941485: 6.5mH <- -55℃~+125℃ 1.05Ω 2 300mA 5kΩ 6.5mH; C3211444: 3mH <- -40℃~+125℃ 1.5kV 1.9A 2 3mH 97mΩ 插件; C5149050: 20mH <- 1.6A 100V、500V 20mH 246mΩ 插件 |
| capacitor Resistance (ESR read as resistance) | 936 | C215760: 90mohm <- -55℃~+105℃ 16V 22uF 53mA@120Hz 90mΩ Polarized Polymer ±20%; C518902: 1.5ohm <- -40℃~+125℃ 1.5Ω 100uF 165mA@120Hz 2000hrs@125℃ 50V Polarized ±20%; C561811: 9mohm <- -55℃~+125℃ 2.5V 2000hrs@125℃ 2mm 330uF 5.5A@100kHz 9mΩ Polarized Polymer ±20% |


## Population-like view: the 2 000 random in-stock LCSC rows

The stratified sample over-weights the 40 largest categories (and caps each at 5 000). The random rows are an unbiased sample of the 723 865 in-stock rows, but small (families with fewer than 20 rows are omitted from the per-family table). Mouser and TME rows are the same cache as above.

### Population and unknown family

| Distributor | Parts | Family unknown | Family unknown % | Blank description % |
|---|---:|---:|---:|---:|
| LCSC | 2000 | 241 | 12.1 | 14.7 |
| TME | 3596 | 327 | 9.1 | 0.0 |
| MOUSER | 3064 | 84 | 2.7 | 0.1 |

### Typed fields per part

| Distributor | Mean attrs/part | Parts with >=3 attrs % | With Package % | With any of Voltage/Resistance/Capacitance/Inductance/Current % |
|---|---:|---:|---:|---:|
| LCSC | 7.42 | 90.1 | 93.8 | 79.8 |
| TME | 9.04 | 90.6 | 44.5 | 88.2 |
| MOUSER | 5.45 | 87.6 | 27.9 | 64.1 |

### Coverage: percent of parts with a typed value, per family and distributor

| Family | Dist | Parts | Cap | Res | Ind | Volt | Curr | Pwr | Tol | Pkg | Mount | Diel | Tech | ConnType | Pos | Pitch | Gender | Orient |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| capacitor | LCSC | 441 | 95.5 | 0.7 | 0.0 | 95.5 | 0.0 | 15.0 | 80.3 | 95.5 | 93.0 | 66.9 | 74.8 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| resistor | LCSC | 306 | 0.0 | 99.7 | 0.0 | 69.9 | 3.3 | 94.1 | 95.8 | 100.0 | 93.1 | 0.0 | 88.6 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| connector | LCSC | 210 | 0.0 | 0.0 | 0.0 | 34.8 | 36.2 | 0.0 | 0.0 | 87.1 | 54.3 | 0.0 | 0.0 | 86.2 | 63.8 | 56.2 | 21.4 | 48.1 |
| inductor | LCSC | 78 | 0.0 | 0.0 | 97.4 | 0.0 | 96.2 | 0.0 | 83.3 | 100.0 | 100.0 | 0.0 | 17.9 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| mosfet | LCSC | 73 | 28.8 | 31.5 | 0.0 | 38.4 | 32.9 | 30.1 | 0.0 | 100.0 | 76.7 | 0.0 | 1.4 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |

### Primary value missing (any reason): family parts without Capacitance / Resistance / Inductance

| Family | Dist | Parts | Missing | Missing % | Of which no value-like token in the description | Of which blank description |
|---|---|---:|---:|---:|---:|---:|
| capacitor | LCSC | 441 | 20 | 4.5 | 20 | 6 |
| capacitor | TME | 741 | 0 | 0.0 | 0 | 0 |
| capacitor | MOUSER | 660 | 26 | 3.9 | 20 | 0 |
| resistor | LCSC | 306 | 1 | 0.3 | 1 | 1 |
| resistor | TME | 559 | 2 | 0.4 | 2 | 0 |
| resistor | MOUSER | 306 | 15 | 4.9 | 15 | 0 |
| inductor | LCSC | 78 | 2 | 2.6 | 2 | 1 |
| inductor | TME | 342 | 0 | 0.0 | 0 | 0 |
| inductor | MOUSER | 339 | 48 | 14.2 | 35 | 0 |

Examples of family parts with no primary value and no value-like token (hash-picked):

| Family | Dist | Part | Category | Description |
|---|---|---|---|---|
| capacitor | LCSC | C42435505 | Switches / Switch Accessories / Caps | Switch Cap silver color |
| capacitor | MOUSER | 77-GA603Y104JXJAC31G | Multilayer Ceramic Capacitors MLCC - SMD/SMT | Multilayer Ceramic Capacitors MLCC - SMD/SMT GA0603Y104JXJAC31G |
| capacitor | MOUSER | 77-VJ0603Y104KCQAT | Multilayer Ceramic Capacitors MLCC - SMD/SMT | Multilayer Ceramic Capacitors MLCC - SMD/SMT VJ0603Y104KCQAT00 |
| inductor | MOUSER | 963-LBR2012T100M | Power Inductors - SMD | Power Inductors - SMD PLEASE SEE SUGGESTED ALTERNATE LSQEA201212T100M |


## Rerun

All paths are relative to the repository root. `WORK` defaults to `/var/tmp/kina-bench/extract`.

```bash
./mvnw -q -DskipTests package                          # target/classes
mkdir -p /var/tmp/kina-bench/extract && rm -f /var/tmp/kina-bench/extract/sample.db
sqlite3 "file:/var/tmp/kina-bench/parts-fts5.db?mode=ro" ".read scripts/research/extraction_coverage/sample.sql"
docker compose -f /home/alex.lucaci/Projects/KINA/compose.yaml exec -T postgres psql -U kina -d kina -Atc \
  "select distributor || E'\t' || payload::text from cached_parts" > /var/tmp/kina-bench/extract/cache.tsv
scripts/research/extraction_coverage/run.sh coverage 4g       # writes $WORK/out/coverage.md
scripts/research/extraction_coverage/run.sh determinism 3g    # writes $WORK/out/determinism-<pid>.txt
scripts/research/extraction_coverage/run.sh throughput 6g     # stratified sample, 1/8/16 threads
JAVA_OPTS="-Dstrat=random -Drepeat=50" scripts/research/extraction_coverage/run.sh throughput 1g
JAVA_OPTS="-Dstrat=random" scripts/research/extraction_coverage/run.sh coverage 2g   # random-only view
```

The runner is `scripts/research/extraction_coverage/ro/alacrity/kina/search/ExtractionCoverage.java`. It is in package `ro.alacrity.kina.search` only to read the package-private `ParametricExtractor.Features` (SI values). `run.sh` compiles it against `target/classes` and the dependency classpath (`./mvnw dependency:build-classpath`) and is not part of the Maven build. The cache export of this run is not committed (it is the live cache and is 11 MB).

## Caveats

- **Sampling.** The stratified sample takes up to 5 000 per category for the 40 largest categories, so rare categories (about 26 percent of in-stock rows) are missing and large categories are capped. Per-family percentages are within the sample, not population estimates. Use the random view for rough population shares; with n = 2 000, a share of 10 percent has a margin of about plus or minus 1.3 points, and small families are very noisy.
- **Stock filter.** Only in-stock rows (`CAST(Stock AS INTEGER) > 0`) were sampled, because KINA only returns those (one exception: parts requested by part number). The 6.4 million out-of-stock rows were not measured. Their descriptions may differ.
- **Cache composition.** The Mouser and TME cache holds what earlier user searches fetched (3 064 and 3 596 parts). It is skewed to what was searched and tested (fans, USB connectors, MLCC, inductors, ferrite beads), not to the catalogue. Percentages for those two are not catalogue estimates. TME and Mouser parts were extracted from `asStored()` with their own distributor attributes.
- **"Typed value present" is not "correct".** Coverage counts presence. Correctness was only checked by the gap and suspicious-value tables, with small samples. Mapped values such as `Current` for transistors were not verified against datasheets.
- **Package.** Present does not mean normalised imperial. The `Package` attribute carries the distributor's package text; chip passives give 0603 and similar, other families give raw forms.
- **Raw spelling analysis** uses a regular expression on the description, not on the original distributor attribute (Mouser and TME values come mostly from attributes). It counts only descriptions with exactly one matching token.
- **Gap list.** "Visible value but not extracted" is detected with a simple regex for capacitance, resistance or inductance tokens. Gaps without such a token (blank descriptions, MPN-only) are counted in the "missing (any reason)" table instead. The 30 examples are a deterministic spread over family and distributor groups (a stride through each group).
- **Throughput.** Measured in memory on an already loaded row list, on a busy and memory-pressured host (see above). It excludes SQLite reads, serialisation and database writes. Mouser and TME extraction speed was not measured separately (the extractor is the same; their parts carry attribute maps and are somewhat heavier).
- **Duplicates.** 519 sampled rows appear in both strata, so a few part numbers count twice in the LCSC totals.
