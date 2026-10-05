"""USB connector queries for the ranking evaluation set (ids u01-u09): written rubrics and labelling functions.

Rubrics were written before any method was scored, from the USB vocabulary mined on 2026-10-05 (JLCPCB database,
TME parameters, Mouser descriptions; see src/main/java/.../search/UsbVocabulary.java). The labelling code below is a
separate, deliberately simple implementation (it does not call KINA's Java recognisers), so the labels do not inherit
their parsing errors. Shared rules for every USB query:

  0  not a USB board connector (cable, adapter, power supply, hub, another connector family)
  1  USB connector of the wrong type (Micro-B for a Type-C request...) or wrong gender, or two or more violations
  2  right type and gender, exactly one violated requirement, or requirements not verifiable from the part data
  3  every stated requirement met

  * Pin counts compare signal configurations: a reported count N equals configuration C when N == C or N - C is 1 or 2
    and N is not itself a configuration of the type (Type-C 6/12/14/16/24, Micro-B 5/10, Type-A/B 4/9, Mini-B 5), so
    a 17P/18P Type-C is a 16-pin part with shell pins counted, 26P a 24-pin one, 8P a 6-pin one; 14 stays 14.
  * A Type-C part with 12-16 contacts has no SuperSpeed pairs (USB 2.0 whatever its "USB 3.1" label); with 2-6 it is
    power only. A standard requirement is met by the same or a higher speed class, violated by a lower one or by a
    power-only part; plain "USB 3.1"/"USB 3.2" is "USB 3.x" (any generation).
  * Waterproof "IP67" is met by a water digit >= 7 (IP67, IP68, IPX7, IPX8), violated by IPX4-IPX6/IP65/IP66,
    unknown for an unrated "waterproof"/"sealed".
"""
import re

from rubric_lib import Judge, attr, cat, desc, mpn

CANONICAL = {"C": [6, 12, 14, 16, 24], "MICRO": [5, 10], "MINI": [5], "A": [4, 9], "B": [4, 9]}
TYPE_NAMES = {"C": "Type-C", "MICRO": "Micro-B", "MINI": "Mini-B", "A": "Type-A", "B": "Type-B"}


def low(c):
    """Description, category, package and attributes (lower-case); not the part number, whose digits mislead
    ("USB4081-03-A" is no USB4 part, "DCP-USBCB" no Type-C)."""
    p = c["part"]
    parts = [p.get("description") or "", p.get("category") or "", p.get("packageName") or ""]
    parts += ["%s: %s" % (k, v) for k, v in (p.get("attributes") or {}).items()]
    return " | ".join(parts).lower()


def not_board_connector(c):
    category = cat(c).lower().split("/")[-1]
    d = desc(c).lower().strip()
    if "connector" not in (cat(c) + " " + d).lower():
        # board connectors say so in the category (LCSC "USB Connectors", TME "USB & IEEE1394 connectors", Mouser
        # "USB Connectors") or the description (TME "Connector: USB C; ..."); JLCPCB placeholder rows without a
        # category count when the part number names a USB type (TYPE-C-24)
        return bool(cat(c).strip()) or not re.search(r"type-?c|usb", mpn(c).lower())
    if re.search(r"cable|adapter|power suppl|hub|charger|card reader|circular|sensor|header|wire housing|kit"
                 r"|development|module|extension|coupler", category):
        return True
    return bool(re.match(r"(adapter|cable|hub|power supply|usb power supply|charger|dev\.? ?kit|coupler|extension lead"
                         r"|keyboard)\b", d))


def usb_type(c):
    t = low(c)
    if re.search(r"type[\s-]*c\b|usb[\s-]*c\b|\btypec\b|\busbc\b|\bc type\b", t) or re.search(r"type-?c", mpn(c).lower()):
        return "C"
    if re.search(r"micro", t):
        return "MICRO"
    if re.search(r"mini[\s-]*(usb|a?b)\b|usb b mini", t):
        return "MINI"
    if re.search(r"type[\s-]*a\b|usb[\s-]*a\b|typea", t):
        return "A"
    if re.search(r"type[\s-]*b\b|usb[\s-]*b\b|typeb", t):
        return "B"
    if re.search(r"usb\s*4|40\s*gbps|thunderbolt", t):
        return "C"
    return None


def gender(c):
    t = " " + low(c) + " "
    female = re.search(r"female|receptacle|socket|\bjack\b|\brec\b|rcpt|recpt|\bskt\b|\bfml\b", t)
    male = re.search(r"\bmale\b|\bplug\b", t)
    if female and not male:
        return "F"
    if male and not female:
        return "M"
    if female and male:
        return "F" if t.find(female.group()) < t.find(male.group()) else "M"
    return None


def pins(c):
    n = attr(c, "Number of pins")
    if n and re.match(r"^\d+$", n.strip()):
        return int(n)
    s = (desc(c) + " " + (c["part"].get("packageName") or "")).lower()
    if re.search(r"\d+\s*p\s*\+\s*\d+\s*p", s):     # 4P+4P / 8P+16P: largest port
        return max(int(x) for x in re.findall(r"(\d+)\s*p\b", s))
    m = re.search(r"pin:\s*(\d+)", s) or re.search(r"(?<![\d.])(\d{1,2})\s*(?:p\b|pins?\b|pos\b|pos\.|positions?\b|ckt|circuits?\b|contacts?\b)", s)
    return int(m.group(1)) if m else None


def configuration(kind, n):
    if n is None or kind is None:
        return None
    canonical = CANONICAL[kind]
    if n in canonical:
        return n
    for extra in (1, 2):
        if n - extra in canonical:
            return n - extra
    return n if kind == "C" and n in (2, 4) else None


def speed_rank(c, kind, config):
    """0 power only, 1 USB 2.0, 2 USB 3.x Gen 1 / unknown generation, 3 Gen 2, 4 Gen 2x2, 5 USB4/Thunderbolt."""
    if kind == "C" and config is not None and config <= 6:
        return 0
    if kind == "C" and config in (12, 14, 16):
        return 1
    t = low(c)
    if re.search(r"usb\s*4|40\s*gbps|thunderbolt", t):
        return 5
    if re.search(r"gen\.?\s*2\s*x\s*2|20\s*gbps", t):
        return 4
    if re.search(r"gen\.?\s*2|10\s*gbps", t):
        return 3
    if re.search(r"gen\.?\s*1|usb\s*3\.\d|usb3|5\s*gbps|\b5g\b|\b3\.[012]\b", t):
        return 2
    if re.search(r"usb\s*2\.0|480\s*mbps|0\.48\s*gbps|\b2\.0\b", t):
        return 1
    if kind in ("MICRO", "MINI") and config == 5 or kind in ("A", "B") and config == 4:
        return 1
    if kind == "MICRO" and config == 10 or kind in ("A", "B") and config == 9:
        return 2
    return None


def mounting(c):
    t = low(c)
    smd = bool(re.search(r"\bsmd\b|\bsmt\b|surface mount|\bmsmt\b", t))
    tht = bool(re.search(r"through hole|thru[\s-]*hole|\bt hole\b|\btht\b|plugin|插件|\bdip\b|t/h\b|\bth\b", t))
    if "hybrid" in t or smd and tht:
        return "hybrid"
    return "SMD" if smd else "THT" if tht else None


def orientation(c):
    t = low(c)
    if re.search(r"right angle|horizontal|\bhorz\b|\bhoriz\b|\bhz\b|r/a|\bra\b|angled|90°|side insertion", t):
        return "RA"
    if re.search(r"vertical|\bvert\b|straight|180|flag", t):
        return "V"
    return None


def mid_mount(c):
    t = low(c)
    if re.search(r"\bmid\b|mid[\s-]*(mount|mnt|mt|surface)|midmt|\bmsmt\b|sink|recessed|laminated board|middle board", t):
        return True
    if re.search(r"top board mount|top mount|topmnt|t\.mt", t):
        return False
    return None


def waterproof_digit(c):
    t = low(c) + " " + mpn(c).lower()
    m = re.search(r"\bip([x0-9])([0-9])\b", t) or re.search(r"ip([x0-9])([0-9])(?![0-9])", t)
    if m:
        return int(m.group(2))
    if re.search(r"waterproof|sealed|o-ring|gasket", t):
        return -1
    return None


def usb_label(kind, want_gender=None, want_pins=None, want_rank=None, want_mount=None, want_orientation=None,
              want_mid=False, want_waterproof=None, want_power_only=False):
    def f(c):
        if not_board_connector(c) or "usb" not in low(c) and usb_type(c) is None:
            return 0, "not a USB board connector"
        actual = usb_type(c)
        if actual is None:
            return 2, "unverifiable: USB connector type"
        if actual != kind:
            return 1, "wrong USB type: %s" % TYPE_NAMES[actual]
        g = gender(c)
        if want_gender and g and g != want_gender:
            return 1, "wrong gender"
        config = configuration(actual, pins(c))
        rank = speed_rank(c, actual, config)
        j = Judge()
        if want_gender:
            j.check("gender", None if g is None else True)
        if want_pins is not None:
            j.check("%d-pin configuration" % want_pins, None if config is None else config == want_pins)
        if want_rank is not None:
            j.check("standard", None if rank is None else rank >= want_rank)
        if want_power_only:
            j.check("power only", None if config is None and rank is None else rank == 0)
        if want_mount:
            m = mounting(c)
            j.check(want_mount, None if m is None else (m == want_mount or m == "hybrid"))
        if want_orientation:
            o = orientation(c)
            j.check("orientation", None if o is None else o == want_orientation)
        if want_mid:
            j.check("mid-mount", mid_mount(c))
        if want_waterproof:
            w = waterproof_digit(c)
            j.check("waterproof", None if w is None or w == -1 else w >= want_waterproof)
        return j.label(True)
    return f


def lcsc_fts(*terms):
    """A JLCPCB FTS5 MATCH restricted to the USB Connectors category."""
    return " AND ".join(['"Second Category" : "USB Connectors"'] + ['"%s"' % t for t in terms])


USB_QUERIES = [
    dict(id="u01", query="USB-C receptacle 16 pin SMD USB 2.0",
         rubric="Type-C receptacle; 16-pin configuration (16P, or 17P/18P counting shell pins); USB 2.0 or higher "
                "(a 12-16 contact Type-C is USB 2.0 whatever its label; 2-6 contacts = power only = violation); SMD "
                "(hybrid SMD+THT shell legs counts as SMD). Other USB type or a plug: 1.",
         label=usb_label("C", "F", 16, 1, "SMD"),
         mined=[lcsc_fts("Type-C", "24P"), lcsc_fts("Type-C", "6P"), lcsc_fts("Type-C", "14P"),
                lcsc_fts("Micro-B", "5P"), lcsc_fts("Type-C", "16P", "Male"), lcsc_fts("Type-C", "16P", "Through Hole")]),
    dict(id="u02", query="USB Type-C 24 pin USB 3.1 receptacle horizontal",
         rubric="Type-C receptacle; 24-pin configuration (24P, or 25P/26P); USB 3.x or higher (16P parts labelled "
                "USB 3.1 are USB 2.0: violation of pins and standard); horizontal / right angle.",
         label=usb_label("C", "F", 24, 2, None, "RA"),
         mined=[lcsc_fts("Type-C", "16P", "USB 3.1"), lcsc_fts("Type-C", "24P", "Vertical"),
                lcsc_fts("Type-C", "24P", "Male"), lcsc_fts("Type-A", "9P"), lcsc_fts("Type-C", "24P", "USB 2.0")],
         extra_lcsc=["C9900163433"]),
    dict(id="u03", query="micro USB B receptacle 5 pin SMD",
         rubric="Micro-B receptacle; 5-pin configuration (5P, or 6P/7P); SMD (hybrid counts). Micro-B 10P (USB 3.0) "
                "violates the pin count; Mini-B, Type-C: 1.",
         label=usb_label("MICRO", "F", 5, None, "SMD"),
         mined=[lcsc_fts("Micro-B", "5P", "Through Hole"), lcsc_fts("Micro-B", "10P"), lcsc_fts("Mini-B", "5P"),
                lcsc_fts("Type-C", "16P"), lcsc_fts("Micro-B", "5P", "Male")]),
    dict(id="u04", query="USB-C 6 pin power only",
         rubric="Type-C (either gender); 6-pin configuration (6P, or 7P/8P counting shell pins); power only (no data "
                "contacts: 2-6 contact Type-C, or stated 'power only'/'charging only').",
         label=usb_label("C", None, 6, None, None, None, False, None, True),
         mined=[lcsc_fts("Type-C", "16P"), lcsc_fts("Type-C", "2P"), lcsc_fts("Type-C", "24P"),
                lcsc_fts("Micro-B", "5P"), lcsc_fts("Type-C", "8P")],
         extra_stack=[("USB-C receptacle 18 pin", "MOUSER", "649-10178589-00011LF")]),
    dict(id="u05", query="USB 3.0 Type-A receptacle THT",
         rubric="Type-A receptacle; USB 3.0 (= USB 3.x Gen 1) or higher (9-contact Type-A); THT. A 4-pin USB 2.0 "
                "Type-A violates the standard; Type-B / Type-C: 1.",
         label=usb_label("A", "F", None, 2, "THT"),
         mined=[lcsc_fts("Type-A", "4P", "Through Hole"), lcsc_fts("Type-A", "9P", "Surface Mount"),
                lcsc_fts("Type-B", "9P"), lcsc_fts("Type-A", "9P", "Male"), lcsc_fts("Type-C", "24P")]),
    dict(id="u06", query="USB-C plug 24 pin",
         rubric="Type-C plug (male); 24-pin configuration (24P, or 25P/26P). A receptacle or another type: 1.",
         label=usb_label("C", "M", 24),
         mined=[lcsc_fts("Type-C", "24P", "Female"), lcsc_fts("Type-C", "16P", "Male"), lcsc_fts("Type-C", "6P"),
                lcsc_fts("Type-A", "Male")]),
    dict(id="u07", query="waterproof USB-C receptacle IP67",
         rubric="Type-C receptacle; waterproof at IP67 or better (water digit >= 7: IP67, IP68, IPX7, IPX8; IPX4-6 "
                "violate; an unrated 'waterproof'/'sealed'/O-ring is unverifiable).",
         label=usb_label("C", "F", None, None, None, None, False, 7),
         mined=[lcsc_fts("Type-C", "IPX7"), lcsc_fts("Type-C", "IPX8"), lcsc_fts("Type-C", "IPX6"),
                lcsc_fts("Type-C", "O-ring"), lcsc_fts("Type-C", "16P"), lcsc_fts("Micro-B", "IPX8")]),
    dict(id="u08", query="mid-mount USB-C 16P",
         rubric="Type-C (either gender); 16-pin configuration (16P/17P/18P); mid-mount (JLCPCB 'Recessed', 'Sink "
                "board', 'Laminated board'; TME 'middle board mount'; Mouser 'Mid Mount', 'MSMT'; 'top board mount' "
                "violates; not stated: unverifiable).",
         label=usb_label("C", None, 16, None, None, None, True),
         mined=[lcsc_fts("Type-C", "16P", "Laminated board"), lcsc_fts("Type-C", "16P", "Sink board"),
                lcsc_fts("Type-C", "16P", "Vertical"), lcsc_fts("Type-C", "24P", "Recessed"), lcsc_fts("Micro-B", "Recessed")]),
    dict(id="u09", query="USB-C receptacle 17 pin",
         rubric="Type-C receptacle; '17 pin' = 16 signal contacts + 1 shell pin, so the 16-pin configuration "
                "(16P, 17P or 18P) matches; 14P, 24P, 6P violate. No real 17P/18P Type-C was found in the JLCPCB "
                "database, TME or Mouser on 2026-10-05; the 26P JLCPCB part (TYPE-C-24) is a 24-pin configuration.",
         label=usb_label("C", "F", 16),
         mined=[lcsc_fts("Type-C", "16P"), lcsc_fts("Type-C", "14P"), lcsc_fts("Type-C", "24P"), lcsc_fts("Type-C", "6P")],
         extra_lcsc=["C9900163433"]),
]

USB_OVERRIDES = {
}
