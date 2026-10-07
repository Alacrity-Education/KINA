#!/usr/bin/env python3
"""End-to-end checks for a running KINA instance (Python 3.10+, standard library only).

Usage (stack started with `docker compose up -d --build`):

    python3 scripts/e2e/kina_e2e.py                       # all dev-mode suites against http://localhost:8080
    python3 scripts/e2e/kina_e2e.py ui mcp                # selected suites
    python3 scripts/e2e/kina_e2e.py --report out.json     # also write timings / responses summary as JSON
    python3 scripts/e2e/kina_e2e.py --base http://localhost:18080 --metrics http://localhost:19090 prod
                                                          # prod-mode smoke (see prod_smoke.sh)

Suites: ui, mcp, oauth, forwarded, rest, metrics (dev mode; "all" = these six) and prod (prod mode only).
Actuator (health, Prometheus) is on the separate management port: --metrics (default http://localhost:9090,
env KINA_METRICS_URL).
The mcp and rest suites need a static bearer token; it is created through the token UI (suite ui runs first
automatically) or taken from KINA_TOKEN. Tokens are never printed, only their 12-character prefix.

Distributor quota: on a cold cache the suites make 2 Mouser calls (one search, one new batch query); reruns within the
cache TTL make none. REST searches are restricted to LCSC and TME (the part-number and below-spec checks use LCSC only).
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import html
import http.cookiejar
import json
import os
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

MCP_ACCEPT = "application/json, text/event-stream"
SEARCH_QUERY = "10uF X7R 0805"
BATCH_QUERIES = [{"query": "10uF X7R 0805", "max_results": 3}, {"query": "100nF 50V X7R 0603", "max_results": 3}]
REST_QUERY = "4.7k 1% 0603 resistor"
RATED_QUERY = "22uF X7R 1206 25V MLCC"
NONSENSE_QUERY = "asdfqwerty zz9"
FALLBACK_QUERY = "SOT-23 N-channel MOSFET 30V"
# never-relax rules (DESIGN.md 3.4 "Hard constraints"), checked against LCSC (a local database, no quota)
IMPOSSIBLE_QUERY = "22uF X7R 0201 100V"
CRYSTAL_QUERY = "16MHz crystal 3225 SMD"
OSCILLATOR_QUERY = "16MHz oscillator 3225"
# power resistors (DESIGN.md 3.4 "Form factor", "Match grade"): free-text words never block an exact match, and a
# SOT-227 request never returns chip resistors
CHASSIS_QUERY = "25W 100 ohm aluminium housed chassis mount resistor"
SOT227_QUERY = "300W 10 ohm power resistor SOT-227 heatsink"
# part numbers in a query (DESIGN.md 3.4 "Requested part numbers"), checked against LCSC (no quota): the named part
# first and requested_part_found true; an unknown part number false with a hint naming it
MPN_QUERY = ("1N4148W SOD-123", "1N4148W")
LIFETIME_QUERY = "electrolytic capacitor 470uF 35V 105°C 5000h THT"
MISSING_MPN_QUERY = ("ZQX48213Q switching diode SOD-123", "ZQX48213Q")
REDIRECT_URI = "http://localhost:6274/callback"
# data notices every response lists for the distributors whose parts it returns (DESIGN.md 3.2 "Attributions")
ATTRIBUTIONS = {
    "LCSC": "LCSC parts from the JLCPCB parts database (kicad-jlcpcb-tools)",
    "TME": "Data powered by TME.eu Data \u2013 no guarantee of data accuracy",
    "MOUSER": "Product data provided by Mouser Electronics",
}
JLCPCB_MIN_PARTS = 7_000_000


# ---------------------------------------------------------------------------------------------------- HTTP plumbing

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401 - keep 3xx visible
        return None


class Response:
    def __init__(self, status: int, headers, body: bytes, millis: float):
        self.status = status
        self.headers = headers
        self.body = body
        self.millis = millis

    @property
    def text(self) -> str:
        return self.body.decode("utf-8", "replace")

    def json(self):
        return json.loads(self.body)

    def header(self, name: str) -> str:
        return self.headers.get(name) or ""


class Client:
    """A tiny HTTP client with an optional cookie jar; never follows redirects."""

    def __init__(self, base: str, cookies: bool = False):
        self.base = base.rstrip("/")
        handlers = [NoRedirect()]
        if cookies:
            self.jar = http.cookiejar.CookieJar()
            handlers.append(urllib.request.HTTPCookieProcessor(self.jar))
        self.opener = urllib.request.build_opener(*handlers)

    def request(self, method: str, path: str, *, headers: dict | None = None, form: dict | None = None,
                body: dict | list | None = None, timeout: float = 120) -> Response:
        url = path if path.startswith("http") else self.base + path
        data = None
        hdrs = dict(headers or {})
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            hdrs.setdefault("Content-Type", "application/x-www-form-urlencoded")
        elif body is not None:
            data = json.dumps(body).encode()
            hdrs.setdefault("Content-Type", "application/json")
        req = urllib.request.Request(url, data=data, method=method, headers=hdrs)
        started = time.perf_counter()
        try:
            with self.opener.open(req, timeout=timeout) as resp:
                payload = resp.read()
                return Response(resp.status, resp.headers, payload, (time.perf_counter() - started) * 1000)
        except urllib.error.HTTPError as e:
            payload = e.read()
            return Response(e.code, e.headers, payload, (time.perf_counter() - started) * 1000)

    def get(self, path, **kw):
        return self.request("GET", path, **kw)

    def post(self, path, **kw):
        return self.request("POST", path, **kw)


def bearer(token: str) -> dict:
    return {"Authorization": "Bearer " + token}


def prefix(token: str) -> str:
    return token[:12] + "..."


# ------------------------------------------------------------------------------------------------- result recording

class Recorder:
    def __init__(self):
        self.results: list[dict] = []
        self.data: dict = {}

    def check(self, name: str, ok: bool, detail: str = "", millis: float | None = None) -> bool:
        self.results.append({"check": name, "ok": bool(ok), "detail": detail,
                             "ms": None if millis is None else round(millis)})
        timing = f" ({millis:.0f} ms)" if millis is not None else ""
        print(f"{'PASS' if ok else 'FAIL'}  {name}{timing}{'  - ' + detail if detail else ''}", flush=True)
        return bool(ok)

    def fail(self, name: str, error: Exception):
        self.check(name, False, f"{type(error).__name__}: {error}")

    @property
    def failures(self) -> int:
        return sum(1 for r in self.results if not r["ok"])


# ------------------------------------------------------------------------------------------------------- MCP helpers

class Mcp:
    def __init__(self, client: Client, token: str | None):
        self.client = client
        self.token = token
        self.next_id = 1

    def call(self, method: str, params: dict | None = None, token: str | None = "default") -> tuple[Response, dict]:
        headers = {"Accept": MCP_ACCEPT, "MCP-Protocol-Version": "2025-06-18"}
        tok = self.token if token == "default" else token
        if tok:
            headers.update(bearer(tok))
        message = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        if params is not None:
            message["params"] = params
        self.next_id += 1
        resp = self.client.post("/mcp", headers=headers, body=message, timeout=180)
        return resp, parse_mcp(resp)

    def tool(self, name: str, arguments: dict | None = None) -> tuple[Response, dict]:
        resp, msg = self.call("tools/call", {"name": name, "arguments": arguments or {}})
        result = msg.get("result") or {}
        if result.get("isError"):
            raise AssertionError(f"tool {name} returned an error: {json.dumps(result)[:300]}")
        if "structuredContent" in result and result["structuredContent"]:
            return resp, result["structuredContent"]
        texts = [c.get("text", "") for c in result.get("content", []) if c.get("type") == "text"]
        if not texts:
            raise AssertionError(f"tool {name} returned no text content: {json.dumps(msg)[:300]}")
        return resp, json.loads(texts[0])


def parse_mcp(resp: Response) -> dict:
    if resp.status != 200 or not resp.body:
        return {}
    if "text/event-stream" in resp.header("Content-Type"):
        data = [line[5:].strip() for line in resp.text.splitlines() if line.startswith("data:")]
        return json.loads(data[-1]) if data else {}
    return resp.json()


def summarize_search(response: dict) -> dict:
    return {
        "ranking": response.get("ranking"),
        "ranking_note": response.get("ranking_note"),
        "query_understood": response.get("query_understood"),
        "currencies": response.get("currencies"),
        "distributors": {d["distributor"]: {k: d.get(k) for k in
                                            ("total_results", "fetched", "excluded_by_constraints",
                                             "excluded_below_spec", "returned", "cache", "error", "fallback_query",
                                             "constraints_relaxed", "query_terms_dropped", "exact_matches",
                                             "excluded_by_constraints_detail", "hint", "requested_part_found",
                                             "excluded_below_spec_detail")}
                         for d in response.get("distributors", [])},
    }


DISTRIBUTOR_FIELDS = ("fetched", "excluded_by_constraints", "excluded_below_spec", "returned", "query_terms_dropped",
                      "constraints_relaxed", "exact_matches", "out_of_stock_matches", "excluded_by_constraints_detail",
                      "requested_part_found", "excluded_below_spec_detail")


def requests_part(number: str, part: dict) -> bool:
    """A part is the requested one when its MPN or distributor number starts with it (letters and digits)."""
    norm = lambda v: re.sub(r"[^0-9A-Za-z]", "", v or "").upper()
    return any(norm(v).startswith(norm(number)) for v in (part.get("mpn"), part.get("part_number")))


def shape_problems(response: dict) -> list[str]:
    """Fields of the third audit round (DESIGN.md 3.2 "Counts", 4) and the arithmetic between the counts."""
    problems = []
    for key in ("query_understood", "currencies", "attributions"):
        if key not in response:
            problems.append(f"no {key}")
    with_parts = [d.get("distributor") for d in response.get("distributors", []) if d.get("parts")]
    wanted = [ATTRIBUTIONS[n] for n in ("LCSC", "TME", "MOUSER") if n in with_parts]
    if response.get("attributions") != wanted:
        problems.append(f"attributions {response.get('attributions')} instead of {wanted}")
    for d in response.get("distributors", []):
        name = d.get("distributor")
        problems += [f"{name}: no {k}" for k in DISTRIBUTOR_FIELDS if k not in d]
        if "relaxed" in d:
            problems.append(f"{name}: the removed field relaxed is still there")
        left = d.get("fetched", 0) - d.get("excluded_by_constraints", 0) - d.get("excluded_below_spec", 0)
        # a part named by part number and listed without stock (stock 0) is returned too, never counted in fetched
        listed = [p for p in d.get("parts", []) if p.get("stock", 1) <= 0]
        if d.get("returned", 0) - len(listed) > left or d.get("returned") != len(d.get("parts", [])) or left < 0:
            problems.append(f"{name}: counts do not add up {[d.get(k) for k in DISTRIBUTOR_FIELDS[:4]]}")
        problems += [f"{name}: {p.get('part_number')} has no stock_as_of" for p in d.get("parts", [])
                     if not p.get("stock_as_of")]
        # stale: only ever true, always with availability "stale", and never above a fresh part (DESIGN.md 3.2)
        flags = [p.get("stale") for p in d.get("parts", [])]
        problems += [f"{name}: {p.get('part_number')} stale {p.get('stale')!r} / availability "
                     f"{p.get('availability', {}).get('status')}" for p in d.get("parts", [])
                     if ("stale" in p and p.get("stale") is not True)
                     or (p.get("stale") is True) != (p.get("availability", {}).get("status") == "stale")]
        meets = [f for f, p in zip(flags, d.get("parts", [])) if not p.get("below_spec")]
        if True in meets and None in meets[meets.index(True):]:
            problems.append(f"{name}: a stale part ranks above a fresh one {flags}")
        detail = d.get("excluded_by_constraints_detail") or {}
        if sum(detail.values()) != d.get("excluded_by_constraints", 0):
            problems.append(f"{name}: excluded_by_constraints_detail {detail} does not add up to "
                            f"{d.get('excluded_by_constraints')}")
        # stock 0 only for a part the query names, out_of_stock, after every part in stock (DESIGN.md 2)
        numbers = response.get("parsed", {}).get("part_numbers") or []
        for p in listed:
            if not any(requests_part(n, p) for n in numbers) \
                    or p.get("availability", {}).get("status") != "out_of_stock":
                problems.append(f"{name}: {p.get('part_number')} has stock 0 but was not requested by part number")
        if listed and d["parts"][-len(listed):] != listed:
            problems.append(f"{name}: a part listed without stock ranks above a part in stock")
        found = d.get("requested_part_found")
        if (found is None) != (not numbers or (bool(d.get("error")) and not d.get("parts"))):
            problems.append(f"{name}: requested_part_found {found!r} for part numbers {numbers}")
        detail_below = d.get("excluded_below_spec_detail")
        if not isinstance(detail_below, list) or len(detail_below) > 5 or (d.get("excluded_below_spec", 0) == 0
                                                                           and detail_below):
            problems.append(f"{name}: excluded_below_spec_detail {detail_below!r} for "
                            f"{d.get('excluded_below_spec')} below spec")
        elif any(set(e) != {"part_number", "mpn", "rating", "part_value", "requested"} for e in detail_below):
            problems.append(f"{name}: excluded_below_spec_detail entries {detail_below!r}")
        # a hint exactly when an understood query found nothing at a distributor that answered, or a part number
        # the query names is not among the parts in stock
        wants_hint = (response.get("query_understood") is True and not d.get("parts") and not d.get("error")) \
            or found is False
        if wants_hint != bool(d.get("hint")):
            problems.append(f"{name}: hint {d.get('hint')!r} for {len(d.get('parts', []))} parts")
    return problems


# ----------------------------------------------------------------------------------------------------------- suites

def suite_ui(base: str, rec: Recorder) -> str | None:
    """Token UI in dev mode: render, create (CSRF + session), list, revoke a second token, 401 for it."""
    web = Client(base, cookies=True)
    resp = web.get("/")
    csrf = re.search(r'name="_csrf" value="([^"]+)"', resp.text)
    rec.check("ui: GET / renders the token page", resp.status == 200 and "Access tokens" in resp.text and csrf,
              f"status {resp.status}", resp.millis)
    rec.check("ui: the footer shows the TME data notice (TME terms 8.7)", ATTRIBUTIONS["TME"] in resp.text,
              "footer present" if "<footer" in resp.text else "no footer")
    if not csrf:
        return None

    def create(name: str) -> str | None:
        page = web.get("/")
        token_csrf = re.search(r'name="_csrf" value="([^"]+)"', page.text).group(1)
        created = web.post("/tokens", form={"name": name, "_csrf": token_csrf})
        match = re.search(r'id="token"[^>]*value="(kina_[A-Za-z0-9_-]{43})"', created.text, re.S)
        rec.check(f"ui: POST /tokens creates '{name}'", created.status == 200 and match is not None,
                  f"status {created.status}, Cache-Control {created.header('Cache-Control')!r}"
                  + (f", token {prefix(match.group(1))}" if match else ""), created.millis)
        return match.group(1) if match else None

    run = time.strftime("%H%M%S")
    main_token = create(f"e2e main {run}")
    second = create(f"e2e revoke-me {run}")
    if not main_token or not second:
        return main_token

    listing = web.get("/")
    rec.check("ui: tokens appear in the list", f"e2e main {run}" in listing.text and prefix(main_token)[:12]
              in listing.text, "listed by name and prefix")

    resp = web.get("/api/v1/distributors", headers=bearer(second))
    rec.check("ui: second token works before revocation", resp.status == 200, f"status {resp.status}", resp.millis)

    # find the revoke form of the second token (its row carries the name)
    row = re.search(r"e2e revoke-me " + run + r".*?action=\"(/tokens/[0-9a-f-]{36}/revoke)\".*?name=\"_csrf\" "
                    r"value=\"([^\"]+)\"", listing.text, re.S)
    if not row:
        rec.check("ui: revoke form found", False, "no revoke form for the second token")
        return main_token
    revoked = web.post(row.group(1), form={"_csrf": row.group(2)})
    rec.check("ui: POST /tokens/{id}/revoke redirects back", revoked.status == 302, f"status {revoked.status}",
              revoked.millis)
    after = web.get("/api/v1/distributors", headers=bearer(second))
    rec.check("ui: revoked token is rejected with 401", after.status == 401,
              f"status {after.status}, WWW-Authenticate {after.header('WWW-Authenticate')!r}")
    still = web.get("/api/v1/distributors", headers=bearer(main_token))
    rec.check("ui: other token still works", still.status == 200, f"status {still.status}")
    no_csrf = web.post("/tokens", form={"name": "no csrf"})
    rec.check("ui: POST /tokens without CSRF is refused", no_csrf.status == 403, f"status {no_csrf.status}")
    return main_token


def suite_mcp(base: str, token: str, rec: Recorder):
    mcp = Mcp(Client(base), token)
    resp, msg = mcp.call("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                        "clientInfo": {"name": "kina-e2e", "version": "1"}})
    info = (msg.get("result") or {}).get("serverInfo", {})
    rec.check("mcp: initialize", resp.status == 200 and info.get("name") == "kina",
              f"server {info.get('name')} {info.get('version')}, protocol {(msg.get('result') or {}).get('protocolVersion')}",
              resp.millis)

    resp, msg = mcp.call("tools/list")
    names = sorted(t["name"] for t in (msg.get("result") or {}).get("tools", []))
    rec.check("mcp: tools/list returns 5 tools", names == sorted(
        ["search_parts", "search_parts_batch", "get_part", "list_distributors", "ping"]), ", ".join(names), resp.millis)

    resp, ping = mcp.tool("ping")
    rec.check("mcp: ping", ping.get("status") == "ok", json.dumps(ping), resp.millis)

    # detail "full": photo_url and the raw distributor fields are only in the full detail level (DESIGN.md 4)
    resp, first = mcp.tool("search_parts", {"query": SEARCH_QUERY, "max_results": 5, "detail": "full"})
    s1 = summarize_search(first)
    rec.data["mcp_search_5"] = {**s1, "ms": round(resp.millis)}
    ok = all(d["returned"] <= 5 for d in s1["distributors"].values()) and set(s1["distributors"]) == {
        "LCSC", "TME", "MOUSER"} and s1["ranking"] == "blended"
    rec.check(f"mcp: search_parts '{SEARCH_QUERY}' max_results 5", ok,
              f"ranking {s1['ranking']}, " + ", ".join(f"{k}={v['cache']}/{v['returned']}of{v['fetched']}"
                                                       f"(total {v['total_results']}){' ERR ' + v['error'] if v['error'] else ''}"
                                                       for k, v in s1["distributors"].items()), resp.millis)
    problems = shape_problems(first)
    rec.check("mcp: response fields and counts (query_understood, currencies, excluded_below_spec, "
              "query_terms_dropped, constraints_relaxed, stock_as_of; returned <= fetched - exclusions)",
              not problems and first.get("query_understood") is True, "; ".join(problems) or
              f"currencies {first.get('currencies')}")
    first_tme = next((d for d in first["distributors"] if d["distributor"] == "TME" and d["parts"]), None)
    photo = any(p.get("photo_url") for d in first["distributors"] for p in d["parts"])
    prices_ok = all(len(p.get("prices", [])) <= 3 for d in first["distributors"] for p in d["parts"])
    rec.check("mcp: parts carry <= 3 price brackets and photo URLs (TME/Mouser)", prices_ok and photo,
              f"photo_url present: {photo}")

    resp, second = mcp.tool("search_parts", {"query": SEARCH_QUERY, "max_results": 20})
    s2 = summarize_search(second)
    rec.data["mcp_search_20"] = {**s2, "ms": round(resp.millis)}
    hits = {k: v["cache"] for k, v in s2["distributors"].items() if k in ("TME", "MOUSER")}
    rec.check("mcp: search_parts again with max_results 20 is a cache hit for Mouser/TME",
              all(c == "hit" for c in hits.values()) and len(hits) == 2,
              f"ranking {s2['ranking']}, cache {hits}, returned "
              + ", ".join(f"{k}={v['returned']}" for k, v in s2["distributors"].items()), resp.millis)

    resp, batch = mcp.tool("search_parts_batch", {"queries": BATCH_QUERIES})
    results = batch.get("results", [])
    rec.data["mcp_batch"] = {"ms": round(resp.millis), "results": [summarize_search(r) for r in results]}
    rec.check("mcp: search_parts_batch with two queries", len(results) == 2 and all(
        r.get("distributors") for r in results),
              "; ".join(f"'{r['query']}' {r['ranking']} " + ",".join(
                  f"{d['distributor']}={d['cache']}/{d['returned']}" for d in r["distributors"]) for r in results),
              resp.millis)

    if first_tme:
        symbol = first_tme["parts"][0]["part_number"]
        resp, part = mcp.tool("get_part", {"distributor": "tme", "part_number": symbol})
        rec.data["mcp_get_part"] = {"ms": round(resp.millis), "cache": part.get("cache"), "found": part.get("found")}
        rec.check(f"mcp: get_part TME {symbol}", part.get("found") is True and part.get("part", {}).get(
            "part_number") == symbol, f"cache {part.get('cache')}", resp.millis)
        got = part.get("part", {}) or {}
        rec.check("mcp: get_part defaults to the full attribute set (extra, raw attributes, stock_as_of)",
                  "extra" in got and bool(got.get("stock_as_of")) and len(got.get("attributes", {})) > 4,
                  f"{len(got.get('attributes', {}))} attributes, datasheet_source "
                  f"{(got.get('extra') or {}).get('datasheet_source')}")
        rec.check("mcp: get_part carries the TME attribution and no stale flag on fresh data",
                  part.get("attributions") == [ATTRIBUTIONS["TME"]] and got.get("stale") is None
                  and (got.get("availability") or {}).get("status") != "stale",
                  f"attributions {part.get('attributions')}, stale {got.get('stale')}")
        rec.data["tme_symbol"] = symbol
    else:
        rec.check("mcp: get_part TME", False, "no TME part in the search result")

    resp, unknown = mcp.tool("get_part", {"distributor": "LCSC", "part_number": "C999999999"})
    rec.check("mcp: get_part unknown LCSC part -> found false", unknown.get("found") is False,
              json.dumps({k: unknown.get(k) for k in ("found", "error", "cache")}), resp.millis)

    resp, status = mcp.tool("list_distributors")
    rec.data["list_distributors"] = status
    by = {d["distributor"]: d for d in status.get("distributors", [])}
    lcsc = by.get("LCSC", {}).get("jlcpcb", {}) or {}
    ok = (all(by.get(d, {}).get("available") for d in ("LCSC", "TME", "MOUSER"))
          and status.get("ranking", {}).get("ready") is True
          and (lcsc.get("part_count") or 0) >= JLCPCB_MIN_PARTS)
    ranking = status.get("ranking", {})
    rec.check("mcp: list_distributors (3 available, cross-encoder ready, JLCPCB >= 7M parts)", ok,
              f"available {[d for d, v in by.items() if v.get('available')]}, cross-encoder ready "
              f"{ranking.get('ready')} ({ranking.get('model_variant')}, revision {ranking.get('model_revision')}, "
              f"avg {ranking.get('avg_latency_ms')} ms), jlcpcb parts {lcsc.get('part_count')}", resp.millis)

    resp, _ = mcp.call("tools/list", token="kina_" + "x" * 43)
    rec.check("mcp: invalid bearer -> 401", resp.status == 401, f"status {resp.status}")


def pkce_pair() -> tuple[str, str]:
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    return verifier, challenge


def suite_oauth(base: str, rec: Recorder):
    """The flow Claude's remote MCP connector performs (dev mode: the dev admin consents)."""
    api = Client(base)
    resp = api.post("/mcp", headers={"Accept": MCP_ACCEPT},
                    body={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    www = resp.header("WWW-Authenticate")
    # dev mode lets anonymous requests through as the dev admin; the 401 challenge appears for a bad token
    if resp.status == 200:
        resp = api.post("/mcp", headers={"Accept": MCP_ACCEPT, **bearer("kina_" + "y" * 43)},
                        body={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        www = resp.header("WWW-Authenticate")
        label = "oauth: POST /mcp with an unknown token -> 401 + resource_metadata (dev mode admits anonymous)"
    else:
        label = "oauth: POST /mcp without a token -> 401 + resource_metadata"
    rec.check(label, resp.status == 401 and "resource_metadata=" in www, www, resp.millis)

    prm = api.get("/.well-known/oauth-protected-resource")
    asm = api.get("/.well-known/oauth-authorization-server")
    prm_j, asm_j = prm.json(), asm.json()
    rec.check("oauth: GET /.well-known/oauth-protected-resource", prm.status == 200 and prm_j.get(
        "resource", "").endswith("/mcp"), json.dumps(prm_j), prm.millis)
    rec.check("oauth: GET /.well-known/oauth-authorization-server", asm.status == 200 and asm_j.get(
        "code_challenge_methods_supported") == ["S256"],
              f"issuer {asm_j.get('issuer')}, registration {asm_j.get('registration_endpoint')}", asm.millis)

    reg = api.post("/oauth/register", body={"client_name": "kina-e2e", "redirect_uris": [REDIRECT_URI],
                                            "token_endpoint_auth_method": "none"})
    client_id = reg.json().get("client_id") if reg.status == 201 else None
    rec.check("oauth: POST /oauth/register (public client)", reg.status == 201 and client_id,
              f"status {reg.status}, client_id {client_id}", reg.millis)
    if not client_id:
        return

    verifier, challenge = pkce_pair()
    state = secrets.token_urlsafe(12)
    query = urllib.parse.urlencode({"response_type": "code", "client_id": client_id, "redirect_uri": REDIRECT_URI,
                                    "code_challenge": challenge, "code_challenge_method": "S256", "scope": "kina",
                                    "state": state, "resource": base.rstrip("/") + "/mcp"})
    browser = Client(base, cookies=True)
    consent = browser.get("/oauth/authorize?" + query)
    fields = dict(re.findall(r'<input type="hidden" name="([^"]+)" value="([^"]*)"', consent.text))
    fields = {k: html.unescape(v) for k, v in fields.items()}
    rec.check("oauth: GET /oauth/authorize renders the consent page", consent.status == 200 and "_csrf" in fields
              and "kina-e2e" in consent.text, f"status {consent.status}, hidden fields {sorted(fields)}",
              consent.millis)
    approve = browser.post("/oauth/authorize", form={**fields, "decision": "approve"})
    location = approve.header("Location")
    params = urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)
    code = (params.get("code") or [None])[0]
    rec.check("oauth: approve -> 302 to redirect_uri with code and state", approve.status == 302
              and location.startswith(REDIRECT_URI) and code and params.get("state") == [state],
              f"status {approve.status}, Location {location.split('?')[0]}?code=...&state=...", approve.millis)
    if not code:
        return

    tok = api.post("/oauth/token", form={"grant_type": "authorization_code", "code": code,
                                         "redirect_uri": REDIRECT_URI, "client_id": client_id,
                                         "code_verifier": verifier})
    tj = tok.json() if tok.status == 200 else {}
    access, refresh = tj.get("access_token"), tj.get("refresh_token")
    rec.check("oauth: POST /oauth/token (authorization_code + PKCE)", tok.status == 200 and access and refresh,
              f"status {tok.status}, token_type {tj.get('token_type')}, expires_in {tj.get('expires_in')}, "
              f"access {prefix(access) if access else None}", tok.millis)
    if not access:
        return
    replay = api.post("/oauth/token", form={"grant_type": "authorization_code", "code": code,
                                            "redirect_uri": REDIRECT_URI, "client_id": client_id,
                                            "code_verifier": verifier})
    rec.check("oauth: authorization code cannot be replayed", replay.status == 400 and
              replay.json().get("error") == "invalid_grant", f"status {replay.status}")

    mcp = Mcp(api, access)
    resp, msg = mcp.call("tools/list")
    rec.check("oauth: tools/list with the OAuth access token", resp.status == 200 and len(
        (msg.get("result") or {}).get("tools", [])) == 5, f"status {resp.status}", resp.millis)

    rot = api.post("/oauth/token", form={"grant_type": "refresh_token", "refresh_token": refresh,
                                         "client_id": client_id})
    rj = rot.json() if rot.status == 200 else {}
    access2, refresh2 = rj.get("access_token"), rj.get("refresh_token")
    rec.check("oauth: refresh_token grant rotates the pair", rot.status == 200 and access2 and refresh2
              and access2 != access and refresh2 != refresh, f"status {rot.status}", rot.millis)
    old = mcp.call("tools/list", token=access)[0]
    rec.check("oauth: access token of the rotated pair is revoked (401)", old.status == 401, f"status {old.status}")
    reuse = api.post("/oauth/token", form={"grant_type": "refresh_token", "refresh_token": refresh,
                                           "client_id": client_id})
    rec.check("oauth: rotated refresh token cannot be reused", reuse.status == 400, f"status {reuse.status}")
    new = mcp.call("tools/list", token=access2)[0]
    rec.check("oauth: new access token works", new.status == 200, f"status {new.status}")

    rv = api.post("/oauth/revoke", form={"token": access2, "client_id": client_id})
    rec.check("oauth: POST /oauth/revoke (access token) -> 200", rv.status == 200, f"status {rv.status}", rv.millis)
    gone = mcp.call("tools/list", token=access2)[0]
    rec.check("oauth: revoked access token -> 401", gone.status == 401,
              f"status {gone.status}, {gone.header('WWW-Authenticate')}")
    # a user revoking the OAuth-issued access token in the web UI also kills its refresh token
    verifier, challenge = pkce_pair()
    consent = browser.get("/oauth/authorize?" + urllib.parse.urlencode({
        "response_type": "code", "client_id": client_id, "redirect_uri": REDIRECT_URI, "code_challenge": challenge,
        "code_challenge_method": "S256", "scope": "kina"}))
    fields = {k: html.unescape(v) for k, v in
              re.findall(r'<input type="hidden" name="([^"]+)" value="([^"]*)"', consent.text)}
    loc = browser.post("/oauth/authorize", form={**fields, "decision": "approve"}).header("Location")
    code3 = urllib.parse.parse_qs(urllib.parse.urlsplit(loc).query)["code"][0]
    pair3 = api.post("/oauth/token", form={"grant_type": "authorization_code", "code": code3,
                                           "redirect_uri": REDIRECT_URI, "client_id": client_id,
                                           "code_verifier": verifier}).json()
    listing = browser.get("/")
    row = re.search(re.escape(pair3["access_token"][:12]) + r".*?action=\"(/tokens/[0-9a-f-]{36}/revoke)\".*?"
                    r"name=\"_csrf\" value=\"([^\"]+)\"", listing.text, re.S)
    ui_revoked = row is not None and browser.post(row.group(1), form={"_csrf": row.group(2)}).status == 302
    after_ui = api.post("/oauth/token", form={"grant_type": "refresh_token", "refresh_token": pair3["refresh_token"],
                                              "client_id": client_id})
    rec.check("oauth: web UI revocation of an OAuth access token also revokes its refresh token",
              ui_revoked and after_ui.status == 400 and after_ui.json().get("error") == "invalid_grant",
              f"ui revoke {ui_revoked}, refresh grant {after_ui.status}")

    rv2 = api.post("/oauth/revoke", form={"token": refresh2, "token_type_hint": "refresh_token",
                                          "client_id": client_id})
    after = api.post("/oauth/token", form={"grant_type": "refresh_token", "refresh_token": refresh2,
                                           "client_id": client_id})
    rec.check("oauth: revoked refresh token is refused", rv2.status == 200 and after.status == 400,
              f"revoke {rv2.status}, refresh {after.status} {after.json().get('error') if after.body else ''}")


def suite_forwarded(base: str, rec: Recorder):
    api = Client(base)
    fwd = {"X-Forwarded-Proto": "https", "X-Forwarded-Host": "kina.example.com"}
    origin = "https://kina.example.com"

    def urls(value) -> list[str]:
        out = []
        if isinstance(value, dict):
            for v in value.values():
                out += urls(v)
        elif isinstance(value, list):
            for v in value:
                out += urls(v)
        elif isinstance(value, str) and value.startswith("http"):
            out.append(value)
        return out

    for path in ("/.well-known/oauth-protected-resource", "/.well-known/oauth-authorization-server"):
        resp = api.get(path, headers=fwd)
        found = urls(resp.json())
        bad = [u for u in found if not u.startswith(origin)]
        rec.check(f"forwarded: {path} uses {origin}", resp.status == 200 and found and not bad,
                  f"{len(found)} URLs" + (f", wrong: {bad}" if bad else ""), resp.millis)
    resp = api.post("/mcp", headers={**fwd, "Accept": MCP_ACCEPT, **bearer("kina_" + "z" * 43)},
                    body={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    www = resp.header("WWW-Authenticate")
    rec.check("forwarded: resource_metadata header uses the forwarded origin", resp.status == 401 and
              f'resource_metadata="{origin}/.well-known/oauth-protected-resource' in www, www)


def suite_rest(base: str, token: str, rec: Recorder):
    api = Client(base)
    auth = bearer(token)
    q = urllib.parse.urlencode({"q": REST_QUERY, "max_results": 3, "distributors": "LCSC,TME"})
    resp = api.get("/api/v1/parts/search?" + q, headers=auth)
    body = resp.json() if resp.status == 200 else {}
    s = summarize_search(body)
    rec.data["rest_search"] = {**s, "ms": round(resp.millis)}
    rec.check(f"rest: GET /api/v1/parts/search '{REST_QUERY}'", resp.status == 200 and set(
        s["distributors"]) == {"LCSC", "TME"}, f"ranking {s['ranking']}, " + ", ".join(
        f"{k}={v['cache']}/{v['returned']}of{v['fetched']}" for k, v in s["distributors"].items()), resp.millis)

    resp = api.post("/api/v1/parts/search/batch", headers=auth, body={
        "queries": [{"query": "1N4148W SOD-123", "max_results": 2}, {"query": "AMS1117-3.3", "max_results": 2}],
        "distributors": ["lcsc", "TME"]})
    results = resp.json().get("results", []) if resp.status == 200 else []
    rec.data["rest_batch"] = {"ms": round(resp.millis), "results": [summarize_search(r) for r in results]}
    rec.check("rest: POST /api/v1/parts/search/batch", resp.status == 200 and len(results) == 2,
              "; ".join(f"'{r['query']}' {r['ranking']}" for r in results), resp.millis)

    # ratings are hard limits: LCSC only (a local database, no quota)
    q = urllib.parse.urlencode({"q": RATED_QUERY, "max_results": 10, "distributors": "LCSC"})
    resp = api.get("/api/v1/parts/search?" + q, headers=auth)
    body = resp.json() if resp.status == 200 else {}
    parts = [p for d in body.get("distributors", []) for p in d.get("parts", [])]
    low = [p["mpn"] for p in parts if any(m.startswith("voltage:") for m in p.get("mismatches", []))]
    rec.check(f"rest: '{RATED_QUERY}' returns nothing below 25 V by default", resp.status == 200 and parts
              and not low and not shape_problems(body), f"{len(parts)} parts, below spec {low}, "
              + "; ".join(shape_problems(body)), resp.millis)
    # the parts left out below spec are named with the failed rating (LCSC checks voltage, current and power in its
    # database, so a lifetime and a temperature are what it leaves to the ranker)
    q = urllib.parse.urlencode({"q": LIFETIME_QUERY, "max_results": 5, "distributors": "LCSC"})
    resp = api.get("/api/v1/parts/search?" + q, headers=auth)
    body = resp.json() if resp.status == 200 else {}
    lcsc = next(iter(body.get("distributors", [])), {})
    detail = lcsc.get("excluded_below_spec_detail") or []
    rec.check(f"rest: '{LIFETIME_QUERY}' names the parts left out below spec (excluded_below_spec_detail)",
              resp.status == 200 and lcsc.get("excluded_below_spec", 0) > 0
              and len(detail) == min(5, lcsc.get("excluded_below_spec", 0))
              and all(e.get("rating") in ("lifetime", "temperature", "voltage", "current") and e.get("part_value")
                      and e.get("requested") for e in detail) and not shape_problems(body),
              f"excluded_below_spec {lcsc.get('excluded_below_spec')}, detail "
              + ", ".join(f"{e.get('mpn')} {e.get('rating')} {e.get('part_value')} < {e.get('requested')}"
                          for e in detail[:3]), resp.millis)
    q = urllib.parse.urlencode({"q": RATED_QUERY, "max_results": 50, "distributors": "LCSC",
                                "allow_below_spec": "true"})
    resp = api.get("/api/v1/parts/search?" + q, headers=auth)
    parts = [p for d in resp.json().get("distributors", []) for p in d.get("parts", [])] if resp.status == 200 else []
    flags = [bool(p.get("below_spec")) for p in parts]
    rec.check("rest: allow_below_spec lists flagged parts after the compliant ones", resp.status == 200
              and flags == sorted(flags), f"{flags.count(True)} of {len(parts)} flagged below_spec", resp.millis)

    # a part number in the query: the named part first, requested_part_found; an unknown one: false and a hint
    query, number = MPN_QUERY
    q = urllib.parse.urlencode({"q": query, "max_results": 5, "distributors": "LCSC"})
    resp = api.get("/api/v1/parts/search?" + q, headers=auth)
    body = resp.json() if resp.status == 200 else {}
    lcsc = next(iter(body.get("distributors", [])), {})
    first = (lcsc.get("parts") or [{}])[0]
    rec.check(f"rest: '{query}' -> part_numbers [{number}], requested part first, requested_part_found true",
              resp.status == 200 and body.get("parsed", {}).get("part_numbers") == [number]
              and requests_part(number, first) and lcsc.get("requested_part_found") is True
              and not shape_problems(body), f"first {first.get('mpn')}, found {lcsc.get('requested_part_found')}; "
              + "; ".join(shape_problems(body)), resp.millis)
    query, number = MISSING_MPN_QUERY
    q = urllib.parse.urlencode({"q": query, "max_results": 5, "distributors": "LCSC"})
    resp = api.get("/api/v1/parts/search?" + q, headers=auth)
    body = resp.json() if resp.status == 200 else {}
    lcsc = next(iter(body.get("distributors", [])), {})
    rec.check(f"rest: '{query}' -> requested_part_found false, hint naming {number}",
              resp.status == 200 and lcsc.get("requested_part_found") is False
              and (lcsc.get("hint") or "").startswith(number + " is not listed in stock at LCSC")
              and not shape_problems(body), f"hint {lcsc.get('hint')!r}; " + "; ".join(shape_problems(body)),
              resp.millis)

    q = urllib.parse.urlencode({"q": NONSENSE_QUERY, "max_results": 3, "distributors": "LCSC"})
    resp = api.get("/api/v1/parts/search?" + q, headers=auth)
    body = resp.json() if resp.status == 200 else {}
    matches = [p.get("match") for d in body.get("distributors", []) for p in d.get("parts", [])]
    rec.check(f"rest: '{NONSENSE_QUERY}' -> query_understood false, hint, match null",
              resp.status == 200 and body.get("query_understood") is False and bool(body.get("hint"))
              and all(m is None for m in matches), f"{len(matches)} parts, matches {matches}", resp.millis)

    # hard constraints are never relaxed: an impossible request comes back empty with a hint, never with substitutes
    q = urllib.parse.urlencode({"q": IMPOSSIBLE_QUERY, "max_results": 5, "distributors": "LCSC"})
    resp = api.get("/api/v1/parts/search?" + q, headers=auth)
    body = resp.json() if resp.status == 200 else {}
    lcsc = next(iter(body.get("distributors", [])), {})
    rec.check(f"rest: '{IMPOSSIBLE_QUERY}' -> empty, exact_matches 0, hint naming the hard constraints",
              resp.status == 200 and not lcsc.get("parts") and lcsc.get("exact_matches") == 0
              and "never relaxed" in (lcsc.get("hint") or "") and bool(body.get("hint"))
              and not shape_problems(body),
              f"excluded {lcsc.get('excluded_by_constraints')} {lcsc.get('excluded_by_constraints_detail')}, "
              f"hint {lcsc.get('hint')!r}", resp.millis)
    for query, wanted in ((CRYSTAL_QUERY, "crystal"), (OSCILLATOR_QUERY, "oscillator")):
        q = urllib.parse.urlencode({"q": query, "max_results": 20, "distributors": "LCSC"})
        resp = api.get("/api/v1/parts/search?" + q, headers=auth)
        body = resp.json() if resp.status == 200 else {}
        families = [p.get("attributes", {}).get("Family") for d in body.get("distributors", [])
                    for p in d.get("parts", [])]
        rec.check(f"rest: '{query}' returns only {wanted}s (crystals and oscillators are never mixed)",
                  resp.status == 200 and families and all(f == wanted for f in families)
                  and not shape_problems(body), f"families {sorted(set(map(str, families)))}", resp.millis)
    q = urllib.parse.urlencode({"q": FALLBACK_QUERY, "max_results": 20, "distributors": "LCSC"})
    resp = api.get("/api/v1/parts/search?" + q, headers=auth)
    body = resp.json() if resp.status == 200 else {}
    polarities = [p.get("attributes", {}).get("Polarity") for d in body.get("distributors", [])
                  for p in d.get("parts", [])]
    rec.check(f"rest: '{FALLBACK_QUERY}' returns no P-channel part", resp.status == 200
              and "P-channel" not in polarities and not shape_problems(body),
              f"polarities {sorted(set(map(str, polarities)))}", resp.millis)
    # JLCPCB lists a MOSFET's gate threshold before its Vds: the rating is the largest stated voltage (DESIGN.md 3.4)
    lcsc = next((d for d in body.get("distributors", []) if d["distributor"] == "LCSC"), {})
    volts = [p.get("attributes", {}).get("Voltage") for p in lcsc.get("parts", [])]
    def volts_ok(v):
        try:
            return v is None or float(str(v).rstrip("V")) >= 30
        except ValueError:
            return False
    rec.check(f"rest: LCSC '{FALLBACK_QUERY}' returns its 30 V N-channel parts (no mass exclusion)",
              len(lcsc.get("parts", [])) >= 10 and all(volts_ok(v) for v in volts)
              and lcsc.get("excluded_below_spec", 0) + lcsc.get("excluded_by_constraints", 0) < lcsc.get("fetched", 0),
              f"returned {lcsc.get('returned')} of {lcsc.get('fetched')}, excluded {lcsc.get('excluded_by_constraints')}"
              f"+{lcsc.get('excluded_below_spec')} below spec, voltages {sorted(set(map(str, volts)))}")

    q = urllib.parse.urlencode({"q": CHASSIS_QUERY, "max_results": 10})
    resp = api.get("/api/v1/parts/search?" + q, headers=auth)
    body = resp.json() if resp.status == 200 else {}
    exact = {d["distributor"]: d.get("exact_matches") for d in body.get("distributors", [])}
    scores = [p for d in body.get("distributors", []) for p in d.get("parts", []) if "score" in p]
    rec.check(f"rest: '{CHASSIS_QUERY}' -> exact_matches > 0 somewhere, no score in compact",
              resp.status == 200 and any((v or 0) > 0 for v in exact.values()) and not scores
              and not shape_problems(body), f"exact_matches {exact}, form_factor "
              f"{body.get('parsed', {}).get('form_factor')!r}; " + "; ".join(shape_problems(body)), resp.millis)
    q = urllib.parse.urlencode({"q": SOT227_QUERY, "max_results": 10, "distributors": "MOUSER"})
    resp = api.get("/api/v1/parts/search?" + q, headers=auth)
    body = resp.json() if resp.status == 200 else {}
    mouser = next((d for d in body.get("distributors", []) if d["distributor"] == "MOUSER"), {})
    chips = [p["mpn"] for p in mouser.get("parts", [])
             if " - SMD" in p.get("description", "") or p.get("attributes", {}).get("FormFactor") == "chip"]
    rec.check(f"rest: Mouser '{SOT227_QUERY}' returns no chip resistor", resp.status == 200 and not mouser.get(
        "error") and not chips and not shape_problems(body),
              f"returned {[p['mpn'] for p in mouser.get('parts', [])]}, excluded "
              f"{mouser.get('excluded_by_constraints_detail')}, chips {chips}", resp.millis)

    q = urllib.parse.urlencode({"q": FALLBACK_QUERY, "max_results": 3, "distributors": "TME"})
    resp = api.get("/api/v1/parts/search?" + q, headers=auth)
    tme = next((d for d in resp.json().get("distributors", []) if d["distributor"] == "TME"), {}) \
        if resp.status == 200 else {}
    rec.data["rest_fallback"] = {"ms": round(resp.millis), "tme": {k: tme.get(k) for k in (
        "total_results", "fetched", "returned", "cache", "error", "fallback_query")}}
    rec.check(f"rest: TME phrase fallback for '{FALLBACK_QUERY}' (informational)", resp.status == 200,
              f"fallback_query {tme.get('fallback_query')!r}, fetched {tme.get('fetched')}, cache {tme.get('cache')}",
              resp.millis)

    symbol = rec.data.get("tme_symbol")
    if not symbol:
        search = api.get("/api/v1/parts/search?" + urllib.parse.urlencode(
            {"q": SEARCH_QUERY, "max_results": 1, "distributors": "TME"}), headers=auth).json()
        symbol = search["distributors"][0]["parts"][0]["part_number"]
    resp = api.get("/api/v1/parts/TME/" + symbol, headers=auth)
    rec.check(f"rest: GET /api/v1/parts/TME/{symbol}", resp.status == 200 and resp.json().get(
        "part_number") == symbol, f"stock {resp.json().get('stock') if resp.status == 200 else None}", resp.millis)

    resp = api.get("/api/v1/parts/LCSC/C999999999", headers=auth)
    rec.check("rest: unknown part -> 404 problem", resp.status == 404 and "problem+json" in resp.header(
        "Content-Type") and resp.json().get("type") == "urn:kina:problem:not-found", f"status {resp.status}",
              resp.millis)
    resp = api.get("/api/v1/parts/DIGIKEY/123", headers=auth)
    rec.check("rest: unknown distributor -> 400 problem", resp.status == 400 and resp.json().get(
        "type") == "urn:kina:problem:unknown-distributor", f"status {resp.status}", resp.millis)
    resp = api.get("/api/v1/parts/search?q=x&max_results=99", headers=auth)
    rec.check("rest: max_results out of range -> 400 validation problem", resp.status == 400 and resp.json().get(
        "type") == "urn:kina:problem:validation", f"status {resp.status}")
    resp = api.get("/api/v1/distributors", headers=bearer("kina_" + "q" * 43))
    rec.check("rest: invalid token -> 401", resp.status == 401 and 'error="invalid_token"' in resp.header(
        "WWW-Authenticate"), resp.header("WWW-Authenticate"))


def suite_metrics(base: str, metrics_base: str, token: str, rec: Recorder):
    """Actuator on the management port without authentication; not served on the main port (DESIGN.md 3.7)."""
    api = Client(base)
    management = Client(metrics_base)
    auth = bearer(token)
    resp = management.get("/actuator/health")
    rec.check("metrics: /actuator/health on the management port is public and UP", resp.status == 200
              and resp.json().get("status") == "UP", resp.text[:60], resp.millis)
    resp = management.get("/actuator/prometheus")
    text = resp.text if resp.status == 200 else ""
    rec.check("metrics: /actuator/prometheus answers without credentials", resp.status == 200
              and resp.header("Content-Type").startswith("text/plain"), f"status {resp.status}", resp.millis)
    expected = ["kina_searches_total", "kina_search_queries_total{type=",
                "kina_tool_calls_total{tool=\"search_parts\"}",
                "kina_distributor_calls_total{", "kina_cache_parts{distributor=\"TME\",type=",
                "kina_cache_parts_fresh{", "kina_cache_parts_stale{", "kina_cache_searches{", "kina_users_known",
                "kina_tokens_active", "kina_jlcpcb_database_parts", "kina_search_duration_seconds_count"]
    missing = [name for name in expected if name not in text]
    rec.check("metrics: the kina_ series are exported", not missing, f"missing {missing}" if missing else
              f"{sum(1 for line in text.splitlines() if line.startswith('kina_'))} kina_ samples")
    typed = ("kina_search_queries_total", "kina_distributor_calls_total", "kina_parts_returned_total",
             "kina_parts_fetched_total", "kina_cache_search_lookups_total")
    untyped = [line for line in text.splitlines() if line.startswith(typed) and "type=\"" not in line]
    rec.check("metrics: the search counters carry the type tag", not untyped, str(untyped[:3]))
    cache_gauges = ("kina_cache_parts{", "kina_cache_searches{")
    untyped = [line for line in text.splitlines() if line.startswith(cache_gauges) and "type=\"" not in line]
    rec.check("metrics: kina_cache_parts and kina_cache_searches carry distributor and type",
              not untyped and "kina_cache_searches{distributor=\"MOUSER\",type=\"unknown\"}" in text,
              str(untyped[:3]))
    # the backfill runs once at the first start of a database, then daily (DESIGN.md 3.7 "Backfill")
    last_run = [line for line in text.splitlines() if line.startswith("kina_metrics_backfill_last_run_seconds ")]
    ran = bool(last_run) and float(last_run[0].split()[-1]) > 0
    rec.check("metrics: the metrics backfill ran (runs_total, last_run_seconds)", ran
              and "kina_metrics_backfill_runs_total{outcome=\"ok\"}" in text,
              last_run[0] if last_run else "kina_metrics_backfill_last_run_seconds missing")
    resp = api.get("/actuator/prometheus")
    rec.check("metrics: the main port does not serve /actuator/prometheus", resp.status != 200
              and "kina_" not in resp.text, f"status {resp.status}")
    resp = api.get("/actuator/health")
    rec.check("metrics: the main port does not serve /actuator/health", resp.status != 200, f"status {resp.status}")
    resp = api.get("/api/v1/metrics/summary", headers=auth)
    summary = resp.json().get("summary", {}) if resp.status == 200 else {}
    by_type = summary.get("search_queries_by_type")
    rec.check("metrics: GET /api/v1/metrics/summary", resp.status == 200 and summary.get("searches", 0) > 0
              and len(resp.json().get("counters", [])) > 0 and isinstance(by_type, dict) and len(by_type) > 0,
              f"searches {summary.get('searches')}, tool_calls {summary.get('tool_calls')}, by type {by_type}",
              resp.millis)
    resp = api.get("/api/v1/distributors", headers=auth)
    metrics = resp.json().get("metrics") if resp.status == 200 else None
    rec.check("metrics: list_distributors carries the key counters", isinstance(metrics, dict)
              and {"searches", "tool_calls", "cache_added", "rate_limited_calls",
                   "cross_encoder_executions", "search_queries_by_type"} <= set(metrics), str(metrics)[:200])


def suite_prod(base: str, rec: Recorder, expected_auth_host: str = "accounts.google.com",
               metrics_base: str = "http://localhost:9090"):
    """Production mode without real OIDC credentials: protection and provider discovery."""
    api = Client(base)
    resp = Client(metrics_base).get("/actuator/health")
    rec.check("prod: application started (/actuator/health UP on the management port)", resp.status == 200,
              resp.text[:60], resp.millis)
    resp = Client(metrics_base).get("/actuator/prometheus")
    rec.check("prod: /actuator/prometheus needs no credentials on the management port", resp.status == 200
              and "kina_users_known" in resp.text, f"status {resp.status}")
    resp = api.get("/actuator/prometheus")
    rec.check("prod: the main port does not serve /actuator/prometheus", resp.status != 200
              and "kina_" not in resp.text, f"status {resp.status}")
    resp = api.post("/mcp", headers={"Accept": MCP_ACCEPT}, body={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    www = resp.header("WWW-Authenticate")
    rec.check("prod: POST /mcp without a token -> 401 + resource_metadata", resp.status == 401
              and "resource_metadata=" in www, www, resp.millis)
    resp = api.get("/api/v1/distributors")
    rec.check("prod: GET /api/v1/distributors without a token -> 401", resp.status == 401, f"status {resp.status}")
    resp = api.get("/", headers={"Accept": "text/html"})
    location = resp.header("Location")
    rec.check("prod: GET / redirects to /oauth2/authorization/oidc", resp.status in (302, 303)
              and location.endswith("/oauth2/authorization/oidc"), f"{resp.status} -> {location}", resp.millis)
    browser = Client(base, cookies=True)
    resp = browser.get("/oauth2/authorization/oidc")
    location = resp.header("Location")
    split = urllib.parse.urlsplit(location)
    params = urllib.parse.parse_qs(split.query)
    rec.check("prod: /oauth2/authorization/oidc -> 302 to the discovered provider endpoint", resp.status == 302
              and split.hostname == expected_auth_host and params.get("response_type") == ["code"]
              and "openid" in (params.get("scope") or [""])[0],
              f"{resp.status} -> {split.scheme}://{split.hostname}{split.path} (client_id {params.get('client_id')}, "
              f"scope {params.get('scope')}, redirect_uri {params.get('redirect_uri')})", resp.millis)
    resp = api.get("/.well-known/oauth-authorization-server")
    rec.check("prod: OAuth metadata is public", resp.status == 200, f"status {resp.status}")


# ------------------------------------------------------------------------------------------------------------- main

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("suites", nargs="*", default=["all"],
                        help="ui, mcp, oauth, forwarded, rest, metrics, prod or all (default)")
    parser.add_argument("--base", default=os.environ.get("KINA_URL", "http://localhost:8080"))
    parser.add_argument("--metrics", default=os.environ.get("KINA_METRICS_URL", "http://localhost:9090"),
                        help="management port (actuator health and Prometheus)")
    parser.add_argument("--report", help="write results, timings and response summaries to this JSON file")
    parser.add_argument("--auth-host", default="accounts.google.com",
                        help="prod suite: expected host of the provider's authorization endpoint")
    args = parser.parse_args()

    suites = args.suites
    if "all" in suites:
        suites = ["ui", "mcp", "oauth", "forwarded", "rest", "metrics"]
    rec = Recorder()
    token = os.environ.get("KINA_TOKEN")
    needs_token = any(s in suites for s in ("mcp", "rest", "metrics"))
    if "ui" in suites or (needs_token and not token):
        try:
            created = suite_ui(args.base, rec)
            token = token or created
        except Exception as e:  # noqa: BLE001 - report and continue with the other suites
            rec.fail("ui", e)
    runners = {
        "mcp": lambda: suite_mcp(args.base, token, rec),
        "oauth": lambda: suite_oauth(args.base, rec),
        "forwarded": lambda: suite_forwarded(args.base, rec),
        "rest": lambda: suite_rest(args.base, token, rec),
        "metrics": lambda: suite_metrics(args.base, args.metrics, token, rec),
        "prod": lambda: suite_prod(args.base, rec, args.auth_host, args.metrics),
    }
    for suite in suites:
        if suite == "ui":
            continue
        if suite in ("mcp", "rest", "metrics") and not token:
            rec.check(f"{suite}: needs a token", False, "token UI failed and KINA_TOKEN is not set")
            continue
        try:
            runners[suite]()
        except Exception as e:  # noqa: BLE001
            rec.fail(suite, e)

    passed = len(rec.results) - rec.failures
    print(f"\n{passed}/{len(rec.results)} checks passed", flush=True)
    if args.report:
        with open(args.report, "w", encoding="utf-8") as fh:
            json.dump({"base": args.base, "results": rec.results, "data": rec.data}, fh, indent=2)
    return 1 if rec.failures else 0


if __name__ == "__main__":
    sys.exit(main())
