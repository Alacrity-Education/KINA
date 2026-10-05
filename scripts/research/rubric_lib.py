"""Labelling helpers for the ranking evaluation set.

Deliberately independent of the KINA Java recognisers (QueryParser / ParametricExtractor), so the labels do not inherit
their parsing errors. Every automatic label is reviewed by hand; corrections live in OVERRIDES in queries.py.

Relevance scale (shared by all queries, see docs/research/data/README.md):
  3  satisfies every requirement stated (or, for vague queries, implied by the written rubric)
  2  right component type and primary value/function, exactly one secondary requirement violated,
     or one or more secondary requirements not verifiable from the part data
  1  right broad family but wrong primary value, or two or more secondary violations, or a near family
     (e.g. tantalum for an MLCC request, resistor network for a chip resistor)
  0  different component type / unrelated
"""
import re

SI = {"p": 1e-12, "n": 1e-9, "u": 1e-6, "µ": 1e-6, "μ": 1e-6, "m": 1e-3, "": 1.0, "k": 1e3, "K": 1e3, "M": 1e6, "G": 1e9}


def norm(s):
    return (s or "").replace("µ", "u").replace("μ", "u").replace("Ω", "ohm").replace("Ω", "ohm").replace("±", "+-")


def text(c, with_attrs=True):
    p = c["part"]
    parts = [p.get("description") or "", p.get("category") or "", p.get("manufacturerPartNumber") or "",
             p.get("packageName") or ""]
    if with_attrs:
        parts += ["%s: %s" % (k, v) for k, v in (p.get("attributes") or {}).items()]
    return norm(" | ".join(parts))


def desc(c):
    return norm(c["part"].get("description") or "")


def cat(c):
    return norm(c["part"].get("category") or "")


def mpn(c):
    return (c["part"].get("manufacturerPartNumber") or "").upper()


def attr(c, *names):
    attrs = c["part"].get("attributes") or {}
    low = {k.lower(): v for k, v in attrs.items()}
    for n in names:
        v = low.get(n.lower())
        if v:
            return norm(str(v))
    return None


def _num(s):
    return float(s.replace(",", "."))


def values(s, unit):
    """All values with the given unit symbol ('F', 'H', 'V', 'A', 'Hz') in s, in base units."""
    out = []
    if unit == "ohm":
        for m in re.finditer(r"(?<![\w.])(\d+(?:[.,]\d+)?)\s*([kKmM]?)\s*(?:ohms?)", s, re.I):
            pre = {"K": "k"}.get(m.group(2), m.group(2))
            out.append(_num(m.group(1)) * SI[pre])
        return out
    pat = r"(?<![\w.])(\d+(?:[.,]\d+)?)\s*([pnumkKMG]?)%s(?![a-zA-Z])" % re.escape(unit)
    if unit == "A":
        pat = r"(?<![\w.])(\d+(?:[.,]\d+)?)\s*([pnumkKMG]?)(?:A|[Aa]mps?)(?![a-zA-Z])"
    if unit == "V":
        pat = r"(?<![\w.])(\d+(?:[.,]\d+)?)\s*([mkK]?)(?:V(?:DC|dc|AC)?|[Vv]olts?|VOLTS?)(?![a-zA-Z])"
    for m in re.finditer(pat, s):
        pre = m.group(2)
        pre = "k" if pre == "K" else pre
        out.append(_num(m.group(1)) * SI[pre])
    return out


def first(lst):
    return lst[0] if lst else None


def close(a, b, rel=0.005):
    return a is not None and b is not None and abs(a - b) <= rel * abs(b) + 1e-18


def capacitance(c):
    a = attr(c, "Capacitance")
    v = first(values(a, "F")) if a else None
    return v if v is not None else first(values(desc(c), "F"))


def inductance(c):
    a = attr(c, "Inductance")
    v = first(values(a, "H")) if a else None
    return v if v is not None else first(values(desc(c), "H"))


def resistance(c):
    a = attr(c, "Resistance")
    v = first(values(a, "ohm")) if a else None
    if v is None:
        v = first(values(desc(c), "ohm"))
    return v


def voltage_rating(c, *names):
    a = attr(c, *(names or ("Voltage Rated", "Voltage Rating DC", "Operating voltage", "Rated voltage", "Voltage")))
    v = first(values(a, "V")) if a else None
    return v if v is not None else first(values(desc(c), "V"))


def tolerance(c):
    a = attr(c, "Tolerance")
    for s in ([a] if a else []) + [desc(c)]:
        m = re.search(r"\+-\s*(\d+(?:\.\d+)?)\s*%", s) or re.search(r"(?<![\w.~-])(\d+(?:\.\d+)?)\s*%", s)
        if m:
            return float(m.group(1))
    return None


DIELECTRICS = ["X7R", "X5R", "C0G", "NP0", "COG", "NPO", "Y5V", "X7S", "X6S", "X8R", "X7T", "Z5U", "X5S", "X6T"]


def dielectric(c):
    s = text(c).upper()
    for d in DIELECTRICS:
        if re.search(r"(?<![A-Z0-9])%s(?![A-Z0-9])" % d, s):
            return {"COG": "C0G", "NPO": "C0G", "NP0": "C0G"}.get(d, d)
    return None


def has_package(c, accepted, rejected_hint=None):
    """True/False/None: package field (then description, attributes) contains one of the accepted spellings.
    Returns False when a different known package is stated."""
    p = c["part"]
    fields = [p.get("packageName") or "", desc(c)] + [str(v) for k, v in (p.get("attributes") or {}).items()
                                                      if "package" in k.lower() or "case" in k.lower()]
    for f in fields:
        fu = f.upper()
        for a in accepted:
            if re.search(r"(?<![A-Z0-9])%s(?![0-9])" % re.escape(a.upper()), fu):
                return True
    chip = r"(?<![A-Z0-9])(01005|0201|0402|0603|0805|1206|1210|1812|2010|2512)(?![0-9])"
    stated = {x for f in fields for x in re.findall(chip, f.upper())}
    if stated and not stated & {a.upper() for a in accepted}:
        return False
    m = (p.get("manufacturerPartNumber") or "").upper()
    fam = re.match(r"^(?:\d\.\d)?P?\d?(SM[ABC])J", m)
    if fam and fam.group(1) in {a.upper() for a in accepted}:
        return True   # SMAJ/SMBJ/SMCJ series name states the package
    for a in accepted:
        if re.fullmatch(r"\d{4}", a) and a in m:
            return True   # imperial chip code embedded in the MPN (CRCW0603..., CC0805...)
    if any(f.strip() for f in fields[:1]) or rejected_hint:
        return False
    return None


class Judge:
    """Accumulates secondary checks: True (met), False (violated), None (unknown)."""

    def __init__(self):
        self.ok, self.bad, self.unknown = [], [], []

    def check(self, name, result):
        (self.ok if result is True else self.bad if result is False else self.unknown).append(name)
        return self

    def label(self, primary_ok=True, primary_name="value"):
        if primary_ok is False:
            return 1, "wrong %s" % primary_name + self._why()
        v, u = len(self.bad), len(self.unknown)
        if primary_ok is None:
            u += 1
            self.unknown.append(primary_name)
        if v == 0 and u == 0:
            return 3, "all requirements met"
        if v == 0:
            return 2, "unverifiable: " + ", ".join(self.unknown)
        if v == 1:
            return 2, "one violation: " + ", ".join(self.bad) + self._why(skip_bad=True)
        return 1, "violations: " + ", ".join(self.bad)

    def _why(self, skip_bad=False):
        bits = []
        if self.bad and not skip_bad:
            bits.append("violations: " + ", ".join(self.bad))
        if self.unknown:
            bits.append("unknown: " + ", ".join(self.unknown))
        return ("; " + "; ".join(bits)) if bits else ""


def ge(v, minimum):
    return None if v is None else v >= minimum * (1 - 1e-9)


def le(v, maximum):
    return None if v is None else v <= maximum * (1 + 1e-9)
