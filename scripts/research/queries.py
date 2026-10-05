"""Query specifications for the ranking evaluation set: query text, category, written rubric, candidate sources
and the labelling function implementing the rubric. Rubrics were written before any method was scored.

Candidate sources per query:
  stack   : raw stack response file (docs/research/data/raw/stack/<slug>.json), LCSC + TME + Mouser parts
  native  : KINA's own LCSC retrieval for the query text (JlcpcbSqliteSearch via ResearchRunner), top N
  mined   : KINA's LCSC retrieval for perturbed queries (hard negatives / near misses / unrelated), top N each
"""
import re

from rubric_lib import (Judge, attr, capacitance, cat, close, desc, dielectric, ge, has_package, inductance, le,
                        mpn, resistance, text, tolerance, values, voltage_rating)

# ----------------------------------------------------------------------------- family tests


def is_mlcc(c):
    t = (cat(c) + " " + desc(c)).lower()
    return ("ceramic" in t or "mlcc" in t) and "network" not in t and "array" not in t


def is_capacitor(c):
    return "capacitor" in (cat(c) + " " + desc(c)).lower() and "development" not in cat(c).lower()


def is_chip_resistor(c):
    t = (cat(c) + " " + desc(c)).lower()
    return "resistor" in t and not any(w in t for w in ("network", "array", "thermistor", "varistor", "potentiometer",
                                                         "trimmer", "current sense amp"))


def is_resistor_any(c):
    return "resistor" in (cat(c) + " " + desc(c)).lower()


def is_inductor(c):
    t = (cat(c) + " " + desc(c)).lower()
    return "inductor" in t and "ferrite" not in t and "bead" not in t


def is_ferrite(c):
    t = (cat(c) + " " + desc(c)).lower()
    return "ferrite" in t or "bead" in t


def passive(c, family_ok, near_family, primary_value, primary_name, checks):
    if not family_ok(c):
        return (1, "near family") if near_family and near_family(c) else (0, "different component type")
    j = Judge()
    for name, fn in checks:
        j.check(name, fn(c))
    return j.label(primary_value(c), primary_name)


def cap_query(C, pkg, diels, vmin=None, tolmax=None):
    def f(c):
        checks = [("package", lambda c: has_package(c, pkg))]
        if diels:
            checks.append(("dielectric", lambda c: None if dielectric(c) is None else dielectric(c) in diels))
        if vmin:
            checks.append(("voltage>=%g" % vmin, lambda c: ge(voltage_rating(c), vmin)))
        if tolmax:
            checks.append(("tolerance<=%g%%" % tolmax, lambda c: le(tolerance(c), tolmax)))
        return passive(c, is_mlcc, is_capacitor,
                       lambda c: None if capacitance(c) is None else close(capacitance(c), C), "capacitance", checks)
    return f


def res_query(R, pkg, tolmax=None):
    def f(c):
        checks = [("package", lambda c: has_package(c, pkg))]
        if tolmax:
            checks.append(("tolerance<=%g%%" % tolmax, lambda c: le(tolerance(c), tolmax)))
        return passive(c, is_chip_resistor, is_resistor_any,
                       lambda c: None if resistance(c) is None else close(resistance(c), R), "resistance", checks)
    return f


# ----------------------------------------------------------------------------- per-query labellers

def lab_inductor_10uh_2a(c):
    def current(c):
        a = attr(c, "Current Rating", "Rated current", "Current", "Saturation Current (Isat)")
        v = values(a, "A") if a else []
        v = v or values(desc(c), "A")
        return max(v) if v else None
    return passive(c, is_inductor, is_ferrite, lambda c: None if inductance(c) is None else close(inductance(c), 10e-6),
                   "inductance", [("current>=2A", lambda c: ge(current(c), 2.0))])


def lab_ferrite_600(c):
    def imp(c):
        a = attr(c, "Impedance @ 100MHz", "Impedance", "Impedance at frequency")
        v = values(a, "ohm") if a else []
        v = v or values(desc(c), "ohm")
        # DC resistance is often listed too (< 10 ohm); impedance is the largest figure of at least 10 ohm
        v = [x for x in v if x >= 10]
        return max(v) if v else None
    return passive(c, is_ferrite, is_inductor, lambda c: None if imp(c) is None else close(imp(c), 600.0, 0.001),
                   "impedance", [("package", lambda c: has_package(c, ["0603", "1608"]))])


def lab_electrolytic_47u_16v(c):
    t = (cat(c) + " " + desc(c)).lower()
    if not is_capacitor(c):
        return 0, "different component type"
    if "aluminum" not in t and "aluminium" not in t and "electrolytic" not in t:
        return 1, "near family (not aluminium electrolytic)"
    j = Judge().check("voltage>=16", ge(voltage_rating(c), 16))
    return j.label(None if capacitance(c) is None else close(capacitance(c), 47e-6), "capacitance")


def is_schottky(c):
    return "schottky" in text(c, False).lower()


def is_diode_any(c):
    t = text(c, False).lower()
    return ("diode" in t or "rectifier" in t or "tvs" in t or "zener" in t) and "led" not in leaf(c)


def max_or_none(v):
    return max(v) if v else None


def lab_schottky_40v_3a(c):
    if not is_schottky(c):
        return (1, "near family (non-Schottky diode)") if is_diode_any(c) else (0, "different component type")
    vr = attr(c, "Vr - Reverse Voltage", "Reverse Voltage (Vr)", "Voltage - DC Reverse (Vr) (Max)")
    vr = values(vr, "V")[0] if vr and values(vr, "V") else None
    if vr is None:
        vs = [v for v in values(desc(c), "V") if v >= 5]
        vr = max_or_none(vs)
    io = attr(c, "If - Forward Current", "Io - Average Rectified Current", "Average Rectified Current (Io)")
    io = values(io, "A")[0] if io and values(io, "A") else None
    if io is None:
        io = max_or_none([v for v in values(desc(c), "A") if v < 100])
    j = (Judge().check("Vr>=40V", ge(vr, 40)).check("If>=3A", ge(io, 3))
         .check("package SMA", has_package(c, ["SMA", "DO-214AC", "SMA(DO-214AC)"])))
    return j.label(True)


def is_tvs(c):
    t = text(c, False).lower()
    return "tvs" in t or "transient" in t or "esd" in t or "suppressor" in t


def lab_tvs_5v_uni_smb(c):
    if not is_tvs(c):
        return (1, "near family (other diode)") if is_diode_any(c) else (0, "different component type")
    t = text(c).lower()
    m = mpn(c)
    vrwm = attr(c, "Vrwm - Reverse Standoff Voltage", "Reverse Stand-Off Voltage (Vrwm)", "Reverse stand-off voltage",
                "Voltage")
    vrwm = values(vrwm, "V")[0] if vrwm and values(vrwm, "V") else None
    if vrwm is None:
        mm = re.search(r"SM[ABC]J(\d+(?:\.\d+)?)", m)
        if mm:
            vrwm = float(mm.group(1))
        else:
            vs = values(desc(c), "V")
            vrwm = vs[0] if vs else None
    if "bidirectional" in t or re.search(r"\bbi\b", t) or re.search(r"\d(CA|C)(-|$|\b)", m):
        uni = False
    elif "unidirectional" in t or re.search(r"\buni\b", t) or re.search(r"SM[ABC]J\d+(\.\d+)?A", m):
        uni = True
    else:
        uni = None
    j = (Judge().check("unidirectional", uni)
         .check("package SMB", has_package(c, ["SMB", "DO-214AA", "SMB(DO-214AA)"])))
    return j.label(None if vrwm is None else close(vrwm, 5.0, 0.02), "Vrwm=5V")


def is_mosfet(c):
    t = text(c, False).lower()
    return "mosfet" in t or "n channel transistor" in t or "p channel transistor" in t or "fet" in cat(c).lower()


def channel(c):
    t = text(c).lower()
    n = bool(re.search(r"\bn[- ]?(?:ch\b|channel|mosfet|fet)|\bnmos|\bnfet", t))
    p = bool(re.search(r"\bp[- ]?(?:ch\b|channel|mosfet|fet)|\bpmos|\bpfet", t))
    if n and not p:
        return "N"
    if p and not n:
        return "P"
    if n and p:
        return "NP"
    return None


def vds(c):
    a = attr(c, "Vds - Drain-Source Breakdown Voltage", "Drain Source Voltage (Vdss)", "Drain-source voltage",
             "Drain to Source Voltage (Vdss)")
    if a and values(a, "V"):
        return abs(values(a, "V")[0])
    vs = [abs(v) for v in values(desc(c).replace("-", " "), "V") if abs(v) >= 8]
    return max_or_none(vs)


def lab_nmos_30v_sot23(c):
    if not is_mosfet(c):
        t = text(c, False).lower()
        return (1, "near family (other transistor)") if "transistor" in t else (0, "different component type")
    ch = channel(c)
    j = Judge().check("Vds>=30V", ge(vds(c), 30)).check(
        "package SOT-23", has_package(c, ["SOT-23", "SOT-23-3", "SOT-23(TO-236)", "TO-236", "SOT23", "SOT-23-3L"]))
    if ch == "NP":
        return 1, "dual N+P part"
    return j.label(None if ch is None else ch == "N", "N-channel")


def lab_1n4148w(c):
    m = mpn(c)
    t = text(c).lower()
    if not is_diode_any(c):
        return 0, "different component type"
    sod123 = has_package(c, ["SOD-123"]) and not has_package(c, ["SOD-123F", "SOD-123FL"])
    if re.search(r"1N4148W(?!S|T)", m) or (re.match(r"^1N4148W", m) and not m.startswith("1N4148WS") and not m.startswith("1N4148WT")):
        return (3, "1N4148W in SOD-123") if sod123 else (2, "1N4148W, package not SOD-123")
    if "4148" in m:
        return 2, "1N4148 variant in other package"
    if ("switching" in t or "small signal" in t) and sod123:
        return 1, "other switching diode in SOD-123"
    return 0, "other diode"


def lab_mmbt3904(c):
    m = mpn(c)
    t = text(c).lower()
    if "transistor" not in t and "bjt" not in t and "npn" not in t and "pnp" not in t:
        return 0, "different component type"
    if "mosfet" in t or "n-channel" in t or "p-channel" in t:
        return 0, "MOSFET, not BJT"
    sot23 = has_package(c, ["SOT-23", "SOT-23-3", "SOT23", "TO-236"])
    npn = ("npn" in t and "pnp" not in t) or bool(re.search(r"(MMBT2222|BC84[6-8]|BC81[78]|S?S8050|S901[34]|2N2222|MMBT4401|MMBT5551|BC546|BC547|2SC)", m))
    if "3904" in m:
        return (3, "MMBT3904 / 3904 in SOT-23") if sot23 else (2, "3904 in other package")
    if npn and sot23 and "darlington" not in t and "digital" not in t and "pre-biased" not in t and "built-in" not in t:
        return 2, "other general-purpose NPN in SOT-23"
    if "pnp" in t or npn:
        return 1, "BJT, wrong polarity, package or type"
    return 1, "other transistor"


def lab_zener_5v1_sod323(c):
    t = text(c, False).lower()
    if "zener" not in t:
        return (1, "near family (other diode)") if is_diode_any(c) else (0, "different component type")
    a = attr(c, "Vz - Zener Voltage", "Voltage - Zener (Nom) (Vz)", "Zener voltage")
    vz = values(a, "V")[0] if a and values(a, "V") else None
    if vz is None:
        d = re.sub(r"\d+(?:\.\d+)?\s*V\s*~\s*\d+(?:\.\d+)?\s*V", " ", desc(c))   # drop min~max ranges
        d = re.sub(r"\d+(?:\.\d+)?\s*[mu]?A@\d+(?:\.\d+)?\s*V", " ", d)            # drop leakage test voltages
        vs = values(d, "V")
        vz = vs[0] if vs else None
    j = Judge().check("package SOD-323", has_package(c, ["SOD-323", "SC-76", "SOD323"]))
    return j.label(None if vz is None else close(vz, 5.1, 0.02), "Vz=5.1V")


def is_ldo(c):
    t = text(c, False).lower()
    return ("ldo" in t or "linear" in t or "low drop" in t or "voltage regulator" in t) and "switching" not in t and "dc-dc" not in t


def lab_ams1117_33(c):
    m = mpn(c)
    if not is_ldo(c):
        return 0, "not a linear regulator"
    t = text(c).lower()
    v33 = bool(re.search(r"3[.]?3", m)) or "3.3v" in t
    if "AMS1117" in m:
        return (3, "AMS1117-3.3") if re.search(r"AMS1117-?3[.]?3", m) else (1, "AMS1117 other voltage")
    if "1117" in m:
        return (2, "1117-family 3.3V") if v33 else (1, "1117-family other voltage")
    return (1, "other 3.3V LDO") if v33 else (1, "other LDO")


def lab_ldo_33_500ma_sot235(c):
    if not is_ldo(c):
        return 0, "not a linear regulator"
    t = text(c).lower()
    vo = attr(c, "Output Voltage", "Voltage - Output (Min/Fixed)", "Output voltage")
    adj = "adj" in t or "adjustable" in t
    vout = None
    if vo and values(vo, "V"):
        vout = values(vo, "V")[0]
    elif re.search(r"(?<![\d.])3\.3\s*v", t) or re.search(r"[-_]3\.?3|33[A-Z]?(?:-|$)", mpn(c)):
        vout = 3.3
    else:
        vs = [v for v in values(desc(c), "V")]
        vout = None if not vs else (3.3 if any(close(v, 3.3, 0.02) for v in vs) else -1)
    io = attr(c, "Output Current", "Current - Output", "Output current")
    io = values(io, "A")[0] if io and values(io, "A") else max_or_none([v for v in values(desc(c), "A") if v < 50])
    j = (Judge().check("fixed output", False if adj else True)
         .check("Iout>=500mA", ge(io, 0.5))
         .check("package SOT-23-5", has_package(c, ["SOT-23-5", "TSOT-23-5", "SOT-25", "SOT-753", "SOT23-5", "SC-74A"])))
    if adj:
        return j.label(True)
    return j.label(None if vout is None else close(vout, 3.3, 0.02), "Vout=3.3V")


def lab_stm32f103_lqfp48(c):
    m = mpn(c)
    t = text(c).lower()
    mcu = "microcontroller" in t or "mcu" in t or re.match(r"^(STM32|GD32|CS32|APM32|AT32|CH32|HK32|MM32)", m)
    if not mcu:
        return 0, "not an MCU"
    lq48 = has_package(c, ["LQFP-48", "LQFP48", "LQFP-48(7x7)"]) or bool(re.match(r"^STM32F103C", m))
    if m.startswith("STM32F103"):
        return (3, "STM32F103 in LQFP-48") if re.match(r"^STM32F103C", m) else (2, "STM32F103 other package")
    if re.match(r"^(GD32|CS32|APM32|AT32|HK32|MM32|CH32)F103C", m):
        return 2, "pin-compatible F103 clone in LQFP-48"
    return 1, "other MCU"


def leaf(c):
    return (cat(c).split("/")[-1]).strip().lower()


def is_opamp(c):
    t = (desc(c) + " " + leaf(c)).lower()
    return ("operational amplifier" in t or "op amp" in t or "opamp" in t or "op-amp" in t or "precision op" in t
            or "fet input amplifier" in t or "operational amplifiers" in t) and "comparator" not in t


SOIC8 = ["SOIC-8", "SOP-8", "SO-8", "SOIC8", "SOP8", "SOIC-8_150MIL", "SO8"]


def lab_lm358_soic8(c):
    m = mpn(c)
    if not is_opamp(c) and "358" not in m and "2904" not in m:
        return 0, "not an op amp"
    so8 = has_package(c, SOIC8)
    if re.search(r"(LM|LMV)?358", m) and "LMV358" not in m and "3580" not in m:
        return (3, "LM358 in SOIC-8") if so8 else (2, "LM358 other package")
    if "2904" in m:
        return (2, "LM2904 equivalent in SOIC-8") if so8 else (1, "LM2904 other package")
    return (1, "other op amp")


RR_FAMILIES = r"(LMV321|LMV331|MCP600[1-4]|MCP602[1-4]|MCP614|TLV900[1-4]|TLV600[1-4]|TLV934|OPA33[3-5]|OPA34[0-5]|OPA36[3-5]|AD854[1-8]|SGM321|SGM8521|SGM8541|TSV9[0-9]|TSV6[0-9]|LMV7[0-9]{2}|TLV237[0-9]|TLV27[0-9]|LPV321|LPV52[01]|MAX4[0-9]{3}|RS321|RS8551|TP160[0-9]|GS8551|AD8605|OPA376|OPA377|OPA314|OPA320|TLV316|TLV313|LMV601|TSZ121|NCS2001|SGM8051)"


def lab_rr_opamp_sot235(c):
    if not is_opamp(c):
        t = text(c, False).lower()
        return (1, "other amplifier / comparator") if ("amplifier" in t or "comparator" in t) else (0, "not an op amp")
    t = text(c).lower()
    rr = "rail-to-rail" in t or "rail to rail" in t or "rrio" in t or "rro" in t or bool(re.search(RR_FAMILIES, mpn(c)))
    j = Judge().check("rail-to-rail", True if rr else None).check(
        "package SOT-23-5", has_package(c, ["SOT-23-5", "SOT-25", "SOT-753", "SC-74A", "SOT23-5"]))
    return j.label(True)


def lab_rs485_soic8(c):
    t = text(c).lower()
    m = mpn(c)
    rs485 = "485" in t or "rs-485" in t or "rs422" in t or "rs-422" in t or re.search(r"(MAX|SP|SIT|THVD|SN65HVD|ISL|ADM)\d*48[5-9]", m)
    if not rs485:
        if "transceiver" in t or "interface" in t:
            return 1, "other interface transceiver"
        return 0, "different component type"
    if "isolat" in t:
        return 2, "isolated RS-485 (different class), package " + ("ok" if has_package(c, SOIC8) else "other")
    return (3, "RS-485 transceiver SOIC-8") if has_package(c, SOIC8) else (2, "RS-485 other package")


def lab_ch340c(c):
    m = mpn(c)
    t = text(c).lower()
    if m.startswith("CH340C"):
        return 3, "CH340C"
    if m.startswith("CH340") or m.startswith("CH341"):
        return 2, "CH340/CH341 variant"
    if ("usb" in t and ("uart" in t or "serial" in t or "bridge" in t)) or re.match(r"^(CP210|FT23|FT230|CH910|CH343|PL2303)", m):
        return 1, "other USB-UART bridge"
    return 0, "different component type"


def is_crystal(c):
    t = text(c, False).lower()
    return "crystal" in t and "oscillator" not in cat(c).lower().split("/")[-1] and "xo" not in mpn(c).lower()[:0]


def freq(c):
    a = attr(c, "Frequency", "Nominal frequency")
    v = values(a, "Hz") if a else []
    return v[0] if v else (values(desc(c), "Hz") or [None])[0]


def lab_xtal_16m_3225(c):
    t = text(c, False).lower()
    is_osc = "oscillator" in cat(c).lower().split("/")[-1].lower() or "oscillator" in t and "crystal" not in cat(c).lower()
    if "crystal" not in t and "oscillator" not in t and "resonator" not in t:
        return 0, "different component type"
    f = freq(c)
    pkg = has_package(c, ["3225", "SMD3225", "3.2x2.5", "SMD-3225", "3.2X2.5MM", "3225-4P", "SMD3225-4P"])
    if is_osc or "resonator" in t and "crystal" not in t:
        return (2 if (f and close(f, 16e6)) and pkg else 1), "oscillator/resonator, not a crystal"
    j = Judge().check("package 3225", pkg)
    return j.label(None if f is None else close(f, 16e6), "frequency")


def lab_xtal_32k_12p5(c):
    t = text(c).lower()
    if "crystal" not in t and "oscillator" not in t and "resonator" not in t:
        return 0, "different component type"
    if "oscillator" in cat(c).lower().split("/")[-1]:
        return 1, "oscillator, not a crystal"
    f = freq(c)
    cl = attr(c, "Load Capacitance", "Load capacitance")
    clv = values(cl, "F")[0] if cl and values(cl, "F") else (values(desc(c), "F") or [None])[0]
    j = Judge().check("CL=12.5pF", None if clv is None else close(clv, 12.5e-12))
    return j.label(None if f is None else close(f, 32768.0, 0.001), "frequency")


def lab_usbc_16p(c):
    t = text(c).lower()
    usb = "usb" in t
    if not usb or "connector" not in t and "receptacle" not in t and "socket" not in t:
        return 0, "different component type"
    typec = "type-c" in t or "type c" in t or "usb-c" in t or "usb c" in t or "typec" in t
    if not typec:
        return 1, "other USB connector"
    plug = ("plug" in t or "male" in t) and "receptacle" not in t and "female" not in t
    if plug:
        return 1, "USB-C plug"
    m = re.search(r"(\d+)\s*(?:p\b|pin|position|contact|-pin)", t)
    pins = int(m.group(1)) if m else None
    smd = "smd" in t or "smt" in t or "surface mount" in t
    tht = ("through hole" in t or "dip" in t or "tht" in t) and not smd
    j = Judge().check("16 pins", None if pins is None else pins == 16).check("SMD", False if tht else (True if smd else None))
    return j.label(True)


def lab_header_1x4(c):
    t = text(c).lower()
    if "header" not in t and "pin" not in t:
        return 0, "different component type"
    if "connector" not in t and "header" not in t:
        return 0, "different component type"
    pitch = "2.54" in t or "0.1\"" in t or "100mil" in t
    m = re.search(r"(?<!\d)1\s*[x*×]\s*(\d+)(?!\d)", t) or re.search(r"(?<!\d)(\d+)\s*[x*×]\s*1(?!\d)", t)
    rows_pins = None
    if m:
        rows_pins = int(m.group(1))
    else:
        mm = re.search(r"(\d+)\s*p(?:in|ins|os|ositions)?\b", t)
        rows_pins = int(mm.group(1)) if mm and ("1 row" in t or "single row" in t or "1row" in t) else None
    if not pitch:
        return 1 if "header" in t else 0, "other pitch connector"
    if rows_pins is None or rows_pins != 4:
        return 1, "2.54mm header, other pin count"
    female = "female" in t or "socket" in t
    right = "right angle" in t or "弯插" in t or "bent" in t or "angled" in t
    j = Judge().check("male", not female).check("straight", not right)
    return j.label(True)


# vague queries --------------------------------------------------------------

LOW_NOISE = r"(5532|OPA207|OPA2207|LME49723|AD8620|OPA627|OPA2182|OPA182|OPA2350|OPA350|OPA2145|OPA145|NE5532|NE5534|SA5532|OPA1612|OPA1602|OPA1622|OPA1632|OPA1642|OPA1652|OPA1656|OPA1662|OPA1678|OPA1679|OPA1688|OPA2134|OPA134|OPA2604|LM4562|LME49720|LME49860|MC33078|MC33079|NJM2068|NJM4580|NJM4558|RC4558|JRC4558|4558|OPA2209|OPA209|OPA1611|OPA827|AD8066|AD8656|AD8597|AD8599|ADA4075|ADA4898|LT1028|LT1115|OPA2228|OPA228|OPA2227|OPA227|OPA1692|OPA2211|OPA211|OPA2140|SGM8262|TL972|LM833|NJM5532|RC5532|NJM2114|OPA2132)"
MID_NOISE = r"(TL07[1-4]|TL08[1-4]|LF353|OPA2376|OPA2192|OPA2172|OPA2171)"


def lab_lownoise_audio_soic8(c):
    if not is_opamp(c) and not re.search(LOW_NOISE, mpn(c)):
        t = text(c, False).lower()
        if "audio amplifier" in t or "amplifier" in t:
            return 1, "other amplifier (not an op amp)"
        return 0, "not an op amp"
    m = mpn(c)
    t = text(c).lower()
    so8 = has_package(c, SOIC8)
    ln = bool(re.search(LOW_NOISE, m)) or "low noise" in t or "low-noise" in t or "audio" in t
    mid = bool(re.search(MID_NOISE, m))
    if ln:
        return (3, "low-noise/audio op amp in SOIC-8") if so8 else (
            2, "low-noise op amp, package " + ("not SOIC-8" if so8 is False else "not stated"))
    if mid:
        return (2, "audio-grade JFET op amp (en 10-20 nV/rtHz) in SOIC-8") if so8 else (1, "audio-grade op amp, other package")
    return 1, "generic op amp"


def lab_decoupling_33v(c):
    if not is_capacitor(c):
        return 0, "not a capacitor"
    if not is_mlcc(c):
        return 1, "other capacitor technology"
    C = capacitance(c)
    d = dielectric(c)
    v = voltage_rating(c)
    chip = has_package(c, ["0402", "0603", "0805"])
    big = has_package(c, ["1206", "1210", "1812", "2220"])
    if C is None:
        return 1, "MLCC, value unknown"
    good_d = d in ("X7R", "X5R", "C0G", "X7S", "X6S", "X8R", "X7T")
    v_ok = ge(v, 6.3)
    if close(C, 100e-9) and good_d and v_ok is not False and chip:
        return 3 if v_ok else 2, "100nF X7R/X5R MLCC, small SMD" + ("" if v_ok else " (voltage unknown)")
    if 10e-9 * 0.99 <= C <= 1e-6 * 1.01 and (good_d or d is None) and v_ok is not False and (chip or big):
        return 2, "usable decoupling MLCC (10nF-1uF) but not the 100nF/small-size/dielectric ideal"
    if close(C, 100e-9):
        return 2, "100nF MLCC with weaker dielectric or size"
    return 1, "MLCC outside the decoupling range"


def lab_led_mosfet(c):
    t = text(c).lower()
    if not is_mosfet(c):
        return (1, "other transistor") if "transistor" in t else (0, "not a transistor")
    ch = channel(c)
    if ch != "N":
        return 1, "P-channel or dual N+P MOSFET" if ch else "MOSFET, channel unknown"
    v = vds(c)
    # logic-level evidence: Rds(on) specified at Vgs <= 4.5V, or Vgs(th) <= 2V
    logic = bool(re.search(r"@\s*(?:vgs\s*=?\s*)?(?:1\.8|2\.5|2\.7|3\.3|4\.5)\s*v", t)) or \
        bool(re.search(r"(?<![\d.])(0\.\d+|1(?:\.\d+)?|2(?:\.0)?)\s*v\s*@\s*\d+\s*u?a", t)) or "logic level" in t
    a = attr(c, "Id - Continuous Drain Current", "Continuous Drain Current (Id)", "Drain current")
    idv = values(a, "A")[0] if a and values(a, "A") else max_or_none([x for x in values(desc(c), "A") if x < 500])
    j = Judge().check("Vds>=20V", ge(v, 20)).check("logic-level gate", True if logic else None).check(
        "Id>=2A", ge(idv, 2.0))
    return j.label(True)


def lab_i2c_pullup_0603(c):
    if not is_chip_resistor(c):
        return (1, "resistor network / other resistor") if is_resistor_any(c) else (0, "not a resistor")
    R = resistance(c)
    if R is None:
        mm = re.search(r"[FJ](\d{3})(\d)$", mpn(c))   # E-series code in the MPN, e.g. 1RC0603F1002 = 10.0k
        if mm:
            R = int(mm.group(1)) * {8: 0.01, 9: 0.1}.get(int(mm.group(2)), 10 ** int(mm.group(2)))
    if R is None:
        return 1, "resistor, value unknown"
    p0603 = has_package(c, ["0603", "1608"])
    if 2.2e3 * 0.99 <= R <= 10e3 * 1.01:
        return (3, "2.2k-10k in 0603") if p0603 else (2, "2.2k-10k other package")
    if (1e3 * 0.99 <= R < 2.2e3 * 0.99 or 10e3 * 1.01 < R <= 47e3 * 1.01):
        return (2, "1k-47k fringe value in 0603") if p0603 else (1, "fringe value, other package")
    return 1, "resistor value outside pull-up range"


ESD_ARRAYS = r"^(USBLC6|SRV05|USRV05|TPD2E|TPD3E|TPD4E|TPD4S|TPD2EUSB|PRTR5V0U2|IP4220|RCLAMP05[0-9]4|RCLAMP03[0-9]4|ULC0524|SP0503|ESDA6V1|ESDAULC6|ESDALC6|PUSBM|USB6B1|CDSOT23|SR05|ULC6|LC05)"
LOW_CAP = r"^(USBLC6|SRV05|USRV05|TPD2E|TPD4E|TPD2EUSB|PRTR5V0U2|IP4220|RCLAMP05|ULC0524|ESDAULC6|ESDALC6|ESD9L|ESD7L|PESD5V0X|USB6B1|PUSBM|ULC6)"


def lab_usb_esd(c):
    t = text(c).lower()
    m = mpn(c)
    if not is_tvs(c) and not re.search(ESD_ARRAYS, m):
        return (1, "other diode") if is_diode_any(c) else (0, "not a protection device")
    vs = [v for v in values(desc(c), "V") if v >= 2.5]
    vrwm = min(vs) if vs else None          # LCSC lists the values sorted; the stand-off voltage is the lowest
    hint = re.search(r"(\d+)V(\d)?", m)   # MPN voltage code: 5V0, 3V3, 24V
    if vrwm is None and hint:
        vrwm = float(hint.group(1) + ("." + hint.group(2) if hint.group(2) else ""))
    caps = [v for v in values(desc(c), "F") if v < 1e-9]
    low_cap = bool(re.search(LOW_CAP, m)) or (bool(caps) and min(caps) <= 1.5e-12)
    multi = bool(re.search(ESD_ARRAYS, m)) or "array" in t or bool(re.search(r"\b([2-9])\s*(?:channel|ch\b|line)", t))
    power_tvs = bool(re.search(r"^(\d\.\d)?P?\d?SM[ABC]J|^P6KE|^1\.5KE|^SMF\d|^P4SMA", m)) or \
        bool(re.search(r"\b(400|600|1500|3000)\s*w\b", t))
    if power_tvs:
        return 1, "power TVS (high capacitance), not for data lines"
    if vrwm is not None and vrwm > 6.0:
        return 1, "ESD diode with stand-off voltage above 5.5V"
    if low_cap and multi:
        return 3, "multi-line low-capacitance ESD array"
    if low_cap:
        return 2, "single-line low-capacitance ESD diode"
    if multi:
        return 2, "ESD array without low-capacitance evidence"
    return 2, "ESD diode, capacitance unknown"


# ----------------------------------------------------------------------------- query list

QUERIES = [
    dict(id="p01", query="10uF 25V X7R 0805 MLCC", category="passive", stack="10uF X7R 0805",
         rubric="MLCC; C = 10uF (within 1%); package 0805 (2012 metric); dielectric X7R; rated voltage >= 25V.",
         label=cap_query(10e-6, ["0805", "2012"], ["X7R"], vmin=25),
         mined=["10uF X5R 0805", "10uF X7R 1206", "1uF X7R 0805 25V", "10uF Y5V 0805", "10uF tantalum 16V",
                "10k 0805 resistor", "10uH 0805 inductor", "10uF 16V X7R 0805"]),
    dict(id="p02", query="100nF 50V X7R 0603", category="passive", stack="100nF 50V X7R 0603",
         rubric="MLCC; C = 100nF; package 0603 (1608 metric); dielectric X7R; rated voltage >= 50V.",
         label=cap_query(100e-9, ["0603", "1608"], ["X7R"], vmin=50),
         mined=["100nF 16V X7R 0603", "100nF 50V X7R 0402", "10nF 50V X7R 0603", "100nF Y5V 0603",
                "100nF C0G 0603", "100k 0603 resistor"]),
    dict(id="p03", query="1nF C0G 0402 50V", category="passive",
         rubric="MLCC; C = 1nF; dielectric C0G (NP0 is the same class); package 0402 (1005 metric); rated voltage >= 50V.",
         label=cap_query(1e-9, ["0402", "1005"], ["C0G"], vmin=50),
         mined=["1nF NP0 0402", "1nF X7R 0402", "1nF C0G 0603", "100pF C0G 0402", "10nF X7R 0402", "1nF 25V 0402",
                "1k 0402 resistor"]),
    dict(id="p04", query="22pF NP0 0603", category="passive",
         rubric="MLCC; C = 22pF; dielectric C0G/NP0; package 0603.",
         label=cap_query(22e-12, ["0603", "1608"], ["C0G"]),
         mined=["22pF C0G 0402", "22pF X7R 0603", "27pF NP0 0603", "20pF NP0 0603", "220pF NP0 0603", "22uF 0603",
                "22 ohm 0603 resistor"]),
    dict(id="p05", query="4k7 1% 0603 resistor", category="passive", stack="4k7 1% 0603 resistor",
         rubric="Chip resistor (not a network/array); R = 4.7 kOhm (RKM 4k7); tolerance <= 1%; package 0603.",
         label=res_query(4.7e3, ["0603", "1608"], tolmax=1),
         mined=["4.7k 5% 0603 resistor", "4.7k 1% 0402 resistor", "47k 1% 0603 resistor", "470 ohm 0603 resistor",
                "4.7k resistor array", "4.7uF 0603 capacitor", "4.7k 1% 0805 resistor"]),
    dict(id="p06", query="10R 0805 resistor 1%", category="passive",
         rubric="Chip resistor; R = 10 Ohm (RKM 10R); tolerance <= 1%; package 0805.",
         label=res_query(10.0, ["0805", "2012"], tolmax=1),
         mined=["10 ohm 5% 0805", "10 ohm 1% 0603", "10k 1% 0805", "100 ohm 1% 0805", "1 ohm 1% 0805",
                "10uH 0805 inductor"]),
    dict(id="p07", query="2R2 1206 resistor", category="passive",
         rubric="Chip resistor; R = 2.2 Ohm (RKM 2R2); package 1206.",
         label=res_query(2.2, ["1206", "3216"]),
         mined=["2.2 ohm 0805 resistor", "22 ohm 1206 resistor", "2.2k 1206 resistor", "0.22 ohm 1206 resistor",
                "2.2uH 1206 inductor", "2.2uF 1206 capacitor"]),
    dict(id="p08", query="10uH power inductor 2A", category="passive",
         rubric="Inductor (not a ferrite bead); L = 10uH; rated (or saturation) current >= 2A.",
         label=lab_inductor_10uh_2a,
         mined=["10uH inductor 0805", "22uH power inductor", "4.7uH power inductor", "10uH power inductor 1A",
                "ferrite bead 0805", "10uF capacitor 25V"]),
    dict(id="p09", query="ferrite bead 600 ohm 0603", category="passive", stack="ferrite bead 600 ohm 0603",
         rubric="Ferrite bead; impedance 600 Ohm @100MHz; package 0603.",
         label=lab_ferrite_600,
         mined=["ferrite bead 600 ohm 0805", "ferrite bead 120 ohm 0603", "ferrite bead 1k 0603", "600 ohm 0603 resistor",
                "1uH 0603 inductor"]),
    dict(id="p10", query="47uF 16V aluminium electrolytic capacitor", category="passive",
         rubric="Aluminium electrolytic capacitor; C = 47uF; rated voltage >= 16V. Tantalum/MLCC 47uF = near family (1).",
         label=lab_electrolytic_47u_16v,
         mined=["47uF 10V electrolytic", "47uF 25V electrolytic", "100uF 16V electrolytic", "47uF 16V tantalum",
                "47uF 1210 X5R", "22uF 16V electrolytic"]),
    dict(id="d01", query="Schottky diode 40V 3A SMA", category="discrete", stack="Schottky diode 40V 3A SMA",
         rubric="Schottky diode; reverse voltage >= 40V; forward current >= 3A; package SMA (DO-214AC). "
                "Non-Schottky diode = near family (1).",
         label=lab_schottky_40v_3a,
         mined=["Schottky 20V 1A SOD-123", "Schottky 40V 3A SMB", "Schottky 100V 3A SMA", "rectifier diode 3A SMA",
                "SS14 Schottky", "TVS SMA 5V"]),
    dict(id="d02", query="TVS diode 5V unidirectional SMB", category="discrete", stack="TVS diode 5V unidirectional SMB",
         rubric="TVS/ESD suppressor; reverse stand-off voltage Vrwm = 5.0V (2%); unidirectional; package SMB (DO-214AA).",
         label=lab_tvs_5v_uni_smb,
         mined=["SMBJ5.0A", "SMBJ5.0CA", "SMAJ5.0A", "SMBJ12A", "TVS 5V SOD-323", "Zener 5.1V SOD-123"]),
    dict(id="d03", query="SOT-23 N-channel MOSFET 30V", category="discrete", stack="SOT-23 N-channel MOSFET 30V",
         rubric="MOSFET; N-channel (primary); Vds >= 30V; package SOT-23 (3-pin, TO-236).",
         label=lab_nmos_30v_sot23,
         mined=["P-channel MOSFET 30V SOT-23", "N-channel MOSFET 20V SOT-23", "N-channel MOSFET 30V SOT-23-6",
                "N-channel MOSFET 60V TO-252", "NPN transistor SOT-23"]),
    dict(id="d04", query="1N4148W SOD-123", category="discrete",
         rubric="3: 1N4148W (any maker) in SOD-123; 2: 1N4148 variant in another package (1N4148WS SOD-323, LL4148, DO-35); "
                "1: other switching diode in SOD-123; 0: anything else.",
         label=lab_1n4148w,
         mined=["1N4148WS", "1N4148 DO-35", "LL4148", "BAV16W", "1N5819W SOD-123", "1N4007"]),
    dict(id="d05", query="MMBT3904 NPN SOT-23", category="discrete",
         rubric="3: MMBT3904 / 3904 NPN in SOT-23; 2: other general-purpose NPN in SOT-23 (BC847, MMBT2222A...) or 3904 "
                "in another package; 1: PNP or other BJT; 0: not a BJT.",
         label=lab_mmbt3904,
         mined=["MMBT3906", "MMBT2222A", "BC847", "2N3904 TO-92", "S8050 SOT-23", "N-channel MOSFET SOT-23"]),
    dict(id="d06", query="Zener 5.1V SOD-323", category="discrete",
         rubric="Zener diode; Vz = 5.1V (2%); package SOD-323.",
         label=lab_zener_5v1_sod323,
         mined=["Zener 5.1V SOD-123", "Zener 3.3V SOD-323", "Zener 5.6V SOD-323", "Zener 4.7V SOD-323",
                "TVS 5V SOD-323", "Schottky SOD-323"]),
    dict(id="i01", query="AMS1117-3.3", category="ic", stack="AMS1117-3.3",
         rubric="3: AMS1117-3.3 (any suffix); 2: other 1117-family fixed 3.3V LDO (LM1117-3.3, AZ1117-3.3...); "
                "1: AMS1117 other voltage or other LDO; 0: not a linear regulator.",
         label=lab_ams1117_33,
         mined=["AMS1117-5.0", "AMS1117-ADJ", "LM1117-3.3", "XC6206 3.3V", "LM317", "AMS1117 capacitor"]),
    dict(id="i02", query="LDO 3.3V 500mA SOT-23-5", category="ic", stack="LDO 3.3V 500mA SOT-23-5",
         rubric="Linear/LDO regulator; fixed Vout = 3.3V (primary); output current >= 500mA; package SOT-23-5. "
                "Adjustable counts as a secondary violation.",
         label=lab_ldo_33_500ma_sot235,
         mined=["LDO 3.3V 300mA SOT-23-5", "LDO 1.8V SOT-23-5", "LDO 3.3V SOT-223", "LDO adjustable SOT-23-5",
                "DC-DC buck 3.3V SOT-23-6", "LDO 5V 500mA SOT-89"]),
    dict(id="i03", query="STM32F103 LQFP-48", category="ic", stack="STM32F103 LQFP-48",
         rubric="3: STM32F103 in LQFP-48 (C pin code: C6/C8/CB); 2: STM32F103 in another package, or pin-compatible "
                "F103 clone (GD32/CS32/APM32) in LQFP-48; 1: other MCU; 0: not an MCU.",
         label=lab_stm32f103_lqfp48,
         mined=["STM32F103RCT6", "STM32F103VET6", "GD32F103C8T6", "STM32F030 LQFP-48", "STM32F401", "LQFP-48 MCU"]),
    dict(id="i04", query="LM358 SOIC-8", category="ic",
         rubric="3: LM358 (any maker/suffix) in SOIC-8/SOP-8; 2: LM358 in another package or LM2904 in SOIC-8; "
                "1: other op amp; 0: not an op amp.",
         label=lab_lm358_soic8,
         mined=["LM358 DIP-8", "LM2904 SOIC-8", "LM324 SOIC-14", "LM393 SOIC-8", "TL072 SOIC-8", "NE555 SOIC-8"]),
    dict(id="i05", query="rail-to-rail op amp SOT-23-5", category="ic",
         rubric="Op amp (comparators and other amplifiers = 1); rail-to-rail input and/or output (stated, or a known RR "
                "family); package SOT-23-5.",
         label=lab_rr_opamp_sot235,
         mined=["LMV321 SOT-23-5", "MCP6001", "op amp SOIC-8 rail-to-rail", "comparator SOT-23-5", "LM321 SOT-23-5",
                "op amp MSOP-8"]),
    dict(id="i06", query="RS485 transceiver SOIC-8", category="ic",
         rubric="3: RS-485/RS-422 transceiver in SOIC-8/SOP-8; 2: RS-485 transceiver in another package or isolated; "
                "1: other interface transceiver (CAN, RS-232); 0: unrelated.",
         label=lab_rs485_soic8,
         mined=["MAX485 SOIC-8", "SP3485", "RS485 MSOP-8", "CAN transceiver SOIC-8", "RS232 transceiver", "MAX485 DIP-8"]),
    dict(id="i07", query="CH340C", category="ic",
         rubric="3: CH340C; 2: other CH340/CH341 variant; 1: other USB-UART bridge; 0: unrelated.",
         label=lab_ch340c,
         mined=["CH340G", "CH340N", "CP2102", "FT232RL", "CH9102", "USB connector"]),
    dict(id="c01", query="16MHz crystal 3225 SMD", category="crystal_connector", stack="16MHz crystal 3225 SMD",
         rubric="Crystal (passive resonator, not an oscillator); f = 16MHz; package 3225 (3.2x2.5mm). "
                "Oscillator/ceramic resonator: 2 if 16MHz and 3225, else 1.",
         label=lab_xtal_16m_3225,
         mined=["16MHz crystal HC-49", "16MHz crystal 2520", "8MHz crystal 3225", "12MHz crystal 3225",
                "16MHz oscillator", "32.768kHz crystal"]),
    dict(id="c02", query="32.768kHz crystal 12.5pF", category="crystal_connector",
         rubric="Crystal; f = 32.768kHz; load capacitance 12.5pF.",
         label=lab_xtal_32k_12p5,
         mined=["32.768kHz crystal 6pF", "32.768kHz crystal 7pF", "32.768kHz oscillator", "8MHz crystal 12pF",
                "12.5pF capacitor"]),
    dict(id="c03", query="USB-C receptacle 16 pin SMD", category="crystal_connector", stack="USB-C receptacle 16 pin SMD",
         rubric="USB Type-C receptacle (female); 16 pins; SMD mounting. Other pin count/through-hole only: one violation "
                "each. USB-C plug or other USB connector: 1.",
         label=lab_usbc_16p,
         mined=["USB Type-C 24P", "USB Type-C 6P", "micro USB receptacle", "USB-C plug", "USB Type-C 16P"]),
    dict(id="c04", query="2.54mm pin header 1x4 straight", category="crystal_connector",
         rubric="Pin header, 2.54mm pitch, 1 row x 4 pins, male, straight. Female or right-angle: one violation each. "
                "Other pin count: 1. Other pitch/connector: 0-1.",
         label=lab_header_1x4,
         mined=["2.54mm female header 1x4", "2.54mm pin header right angle 1x4", "2.54mm pin header 1x6",
                "2.54mm pin header 2x4", "1.27mm pin header 1x4", "JST PH 4 pin"]),
    dict(id="v01", query="low-noise op amp for audio, SOIC-8", category="vague", stack="low-noise op amp for audio, SOIC-8",
         rubric="Written before scoring. 3: op amp with input noise <= 10 nV/rtHz or marketed low-noise/audio "
                "(NE5532, OPA1612/1642/1656, OPA2134, LM4562, MC33078, NJM4558...) in SOIC-8/SOP-8; 2: such an op amp "
                "in another package, or an audio-grade JFET op amp (TL072 class) in SOIC-8; 1: generic op amp or other "
                "amplifier (audio power amplifiers); 0: not an amplifier.",
         label=lab_lownoise_audio_soic8,
         mined=["NE5532 SOIC-8", "OPA2134", "TL072 SOIC-8", "LM358 SOIC-8", "audio amplifier class D", "NE5532 DIP-8"]),
    dict(id="v02", query="decoupling cap for a 3.3V MCU", category="vague", stack="decoupling cap for a 3.3V MCU",
         rubric="Written before scoring. 3: 100nF MLCC, X7R/X5R (or better), rated >= 6.3V, 0402/0603/0805; "
                "2: MLCC 10nF-1uF suitable for decoupling, or 100nF with weaker dielectric/larger size; "
                "1: other capacitor (electrolytic, tantalum, MLCC outside 10nF-1uF); 0: not a capacitor.",
         label=lab_decoupling_33v,
         mined=["100nF 16V X7R 0402", "100nF 50V X7R 0603", "1uF 10V X5R 0402", "10uF tantalum", "100uF electrolytic",
                "3.3V LDO", "100pF C0G 0402"]),
    dict(id="v03", query="MOSFET to switch a 12V LED strip from a 3.3V GPIO", category="vague",
         stack="MOSFET to switch a 12V LED strip from a 3.3V GPIO",
         rubric="Written before scoring. N-channel MOSFET (P-channel/other transistor = 1); Vds >= 20V; logic-level gate "
                "(Rds(on) specified at Vgs <= 4.5V or Vgs(th) <= 2V); continuous drain current >= 2A.",
         label=lab_led_mosfet,
         mined=["N-channel MOSFET 30V SOT-23 logic level", "AO3400", "IRLML6344", "IRF540N", "P-channel MOSFET 20V",
                "LED driver 12V"]),
    dict(id="v04", query="pull-up resistor for I2C bus, 0603", category="vague",
         rubric="Written before scoring. 3: chip resistor 2.2k-10k in 0603; 2: 1k-2.2k or 10k-47k in 0603, or "
                "2.2k-10k in another chip size; 1: other resistor; 0: not a resistor.",
         label=lab_i2c_pullup_0603,
         mined=["4.7k 0603 resistor", "10k 0603 resistor", "2.2k 0603 resistor", "4.7k 0402 resistor",
                "100 ohm 0603 resistor", "1M 0603 resistor", "I2C level shifter"]),
    dict(id="v05", query="ESD protection for USB 2.0 data lines", category="vague",
         rubric="Written before scoring. 3: multi-line low-capacitance ESD array with Vrwm <= 5.5V (USBLC6-2, SRV05-4, "
                "TPD2E...); 2: single-line low-capacitance ESD diode <= 5.5V, or an ESD array without low-capacitance "
                "evidence; 1: power TVS (SMAJ/SMBJ) or ESD diode above 5.5V, or other diode; 0: not a protection device.",
         label=lab_usb_esd,
         mined=["USBLC6-2SC6", "SRV05-4", "ESD diode SOD-523 5V", "SMBJ5.0A", "TVS 24V SOD-323", "USB Type-C connector",
                "TPD2E001"]),
]

# Hand corrections after reviewing every automatic label: (query id, MPN prefix) -> (label, reason)
OVERRIDES = {
    ("p03", "GRM1552C1H102GA01D"): (3, "garbled LCSC description; Murata GRM155 2C 1H 102 = 0402 C0G 50V 1nF"),
    ("p08", "ZEYH0630-33UH"): (1, "garbled multi-value description; the part is 33uH (MPN)"),
    ("i02", "TLV77518"): (1, "1.8V fixed output (MPN code 18)"),
    ("c01", "TSX-3225 16.0000M"): (3, "16MHz crystal in 3225 (frequency written as 16.0000M)"),
    ("c01", "SG3225CAN 16.0000M"): (2, "16MHz oscillator in 3225, not a crystal"),
    ("c03", "MUSB-C111"): (1, "USB Type-A receptacle, not Type-C"),
    ("c03", "MLD-MICRO USB"): (1, "micro-USB receptacle, not Type-C"),
    ("v02", "NCD2100"): (0, "digitally programmable capacitor IC, not a capacitor component"),
    ("v03", "COM-23979"): (0, "development board, not a component"),
    ("v05", "TPD2E001"): (3, "2-line 1.5pF ESD array rated 5.5V (TPD2E001 family; 11V in the description is the clamp)"),
}
