#!/usr/bin/env python3
"""End-to-end test of KINA's group authorisation against a real, disposable Authentik (standard library only).

Steps (documented in docs/DEVELOPMENT.md, "Group authorisation against a real Authentik"):
  1. writes ./.env with fresh random secrets (git-ignored), starts compose.yaml (Authentik server + worker +
     PostgreSQL, and a PostgreSQL for KINA), waits until Authentik is ready;
  2. bootstrap.py: group ElectronicsEngineer, users, groups scope mapping, OIDC provider + application + bindings;
  3. starts target/kina.jar in prod mode on port 18080 against Authentik (required group ElectronicsEngineer,
     encryption key set, so upstream refresh tokens are stored and re-checked);
  4. drives the browser flow over HTTP (Authentik's flow executor API for the identification and password stages):
     - member: KINA /oauth/authorize -> Authentik login -> KINA consent -> code -> tokens -> MCP tools/list;
       refresh with a fresh check (no upstream call), refresh with an aged check (real Authentik refresh + groups);
       removed from the group in Authentik -> next refresh is invalid_grant and the access token is dead;
     - non-member: refused by Authentik's application binding;
     - guest (bound group, but not ElectronicsEngineer): passes Authentik, refused by KINA (/login-denied);
  5. stops KINA and removes the compose project (volumes included) unless --keep.

  python3 scripts/e2e/authentik/kina_authentik_e2e.py            # build the jar first: ./mvnw -q -DskipTests package
  python3 scripts/e2e/authentik/kina_authentik_e2e.py --keep     # leave Authentik and KINA running for inspection
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
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import bootstrap

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
AUTHENTIK = os.environ.get("AUTHENTIK_URL", "http://localhost:19000")
KINA = os.environ.get("KINA_E2E_URL", "http://localhost:18080")
# management port (actuator health, Prometheus); not 9090, which the compose stack may publish
KINA_METRICS = os.environ.get("KINA_E2E_METRICS_URL", "http://localhost:19091")
KINA_DB_PORT = os.environ.get("KINA_DB_PORT", "15432")
CLIENT_REDIRECT = "http://localhost:33418/callback"  # the MCP client's callback (never contacted)
TIMEOUT = 20
RESULTS: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    RESULTS.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""), flush=True)
    return ok


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Browser:
    """Cookie-keeping HTTP client that never follows redirects on its own."""

    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar), NoRedirect)

    def request(self, method: str, url: str, form=None, body=None, headers=None):
        data, all_headers = None, {"Accept": "text/html,application/xhtml+xml,*/*;q=0.8"}
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            all_headers["Content-Type"] = "application/x-www-form-urlencoded"
        if body is not None:
            data = json.dumps(body).encode()
            all_headers["Content-Type"] = "application/json"
        all_headers.update(headers or {})
        request = urllib.request.Request(url, data=data, method=method, headers=all_headers)
        try:
            with self.opener.open(request, timeout=TIMEOUT) as response:
                return response.status, response.headers, response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            return e.code, e.headers, e.read().decode("utf-8", "replace")

    def cookie(self, name: str):
        return next((c.value for c in self.jar if c.name == name), None)


def absolute(base_url: str, location: str) -> str:
    return urllib.parse.urljoin(base_url, location)


def run_flow(browser: Browser, flow_url: str, username: str, password: str) -> tuple[str, str]:
    """Drives an Authentik flow (/if/flow/<slug>/?...) through the flow executor API.
    Returns ("redirect", url) or ("denied", message)."""
    parsed = urllib.parse.urlparse(flow_url)
    slug = parsed.path.rstrip("/").split("/")[-1]
    executor = f"{AUTHENTIK}/api/v3/flows/executor/{slug}/?" + urllib.parse.urlencode({"query": parsed.query})
    status, headers, text = browser.request("GET", executor, headers={"Accept": "application/json"})
    for _ in range(12):
        if status in (301, 302):
            status, headers, text = browser.request("GET", absolute(executor, headers["Location"]),
                                                    headers={"Accept": "application/json"})
            continue
        challenge = json.loads(text)
        component = challenge.get("component")
        if component == "xak-flow-redirect":
            return "redirect", absolute(AUTHENTIK, challenge["to"])
        if component == "ak-stage-access-denied":
            return "denied", challenge.get("error_message") or "access denied"
        if component == "ak-stage-identification":
            answer = {"component": component, "uid_field": username}
            if challenge.get("password_fields"):
                answer["password"] = password
        elif component == "ak-stage-password":
            answer = {"component": component, "password": password}
        elif component == "ak-stage-consent":
            answer = {"component": component, "token": challenge.get("token", "")}
        else:
            raise RuntimeError(f"unexpected Authentik stage {component}: {text[:300]}")
        csrf = browser.cookie("authentik_csrf")
        status, headers, text = browser.request(
            "POST", executor, body=answer,
            headers={"Accept": "application/json", **({"X-authentik-CSRF": csrf} if csrf else {})})
    raise RuntimeError("Authentik flow did not finish")


def browse(browser: Browser, url: str, username: str, password: str):
    """Follows redirects from url across KINA and Authentik (running flows) until a non-redirect KINA response or a
    redirect to the MCP client's callback. Returns (kind, url, status, text)."""
    for _ in range(25):
        if url.startswith(CLIENT_REDIRECT):
            return "client-callback", url, 302, ""
        if url.startswith(AUTHENTIK) and "/if/flow/" in url:
            outcome, target = run_flow(browser, url, username, password)
            if outcome == "denied":
                return "authentik-denied", url, 403, target
            url = target
            continue
        status, headers, text = browser.request("GET", url)
        if status in (301, 302, 303, 307):
            url = absolute(url, headers["Location"])
            continue
        if url.startswith(AUTHENTIK) and "Permission denied" in text:
            # The application's policy binding refused the signed-in user (rendered page, not a flow stage).
            return "authentik-denied", url, status, "Permission denied"
        return "page", url, status, text
    raise RuntimeError("too many redirects")


def pkce():
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    return verifier, challenge


def kina_json(method: str, path: str, form=None, body=None, token=None):
    headers = {"Accept": "application/json, text/event-stream"}
    if token:
        headers["Authorization"] = "Bearer " + token
    status, _, text = Browser().request(method, KINA + path, form=form, body=body, headers=headers)
    try:
        return status, json.loads(text) if text.strip().startswith("{") else text
    except json.JSONDecodeError:
        return status, text


def register_client() -> str:
    status, body = kina_json("POST", "/oauth/register",
                             body={"redirect_uris": [CLIENT_REDIRECT], "client_name": "Authentik e2e"})
    assert status == 201, body
    return body["client_id"]


def authorize_url(client_id: str, challenge: str) -> str:
    return KINA + "/oauth/authorize?" + urllib.parse.urlencode({
        "response_type": "code", "client_id": client_id, "redirect_uri": CLIENT_REDIRECT,
        "code_challenge": challenge, "code_challenge_method": "S256", "state": "e2e",
        "resource": KINA + "/mcp"})


def psql(sql: str) -> str:
    return subprocess.run(["docker", "compose", "-f", str(HERE / "compose.yaml"), "exec", "-T", "kina-db", "psql",
                           "-U", "kina", "-d", "kina", "-tAc", sql], check=True, capture_output=True,
                          text=True, cwd=HERE).stdout.strip()


def age_membership(username: str, hours: int) -> None:
    psql(f"UPDATE users SET membership_checked_at = now() - interval '{hours} hours' "
         f"WHERE email = '{username}@example.test'")


def scenario_member(passwords, info) -> None:
    browser = Browser()
    client_id = register_client()
    verifier, challenge = pkce()
    kind, url, status, text = browse(browser, authorize_url(client_id, challenge), "e2e-member",
                                     passwords["e2e-member"])
    if not check("member: Authentik login leads to KINA's consent page", kind == "page" and status == 200
                 and 'value="approve"' in text, f"{kind} {status} {url}"):
        return
    csrf = re.search(r'name="_csrf" value="([^"]+)"', text).group(1)
    hidden = {html.unescape(n): html.unescape(v) for n, v in
              re.findall(r'<input type="hidden" name="([^"]+)" value="([^"]*)"', text)}
    hidden.update({"_csrf": csrf, "decision": "approve"})
    status, headers, _ = browser.request("POST", KINA + "/oauth/authorize", form=hidden)
    location = headers.get("Location", "")
    code = urllib.parse.parse_qs(urllib.parse.urlparse(location).query).get("code", [None])[0]
    if not check("member: approve returns an authorization code", status == 302 and code is not None, location[:60]):
        return
    status, tokens = kina_json("POST", "/oauth/token", form={
        "grant_type": "authorization_code", "code": code, "redirect_uri": CLIENT_REDIRECT, "client_id": client_id,
        "code_verifier": verifier})
    check("member: code exchange, expires_in 3600", status == 200 and tokens.get("expires_in") == 3600,
          f"{status}")
    status, listed = kina_json("POST", "/mcp", token=tokens["access_token"],
                               body={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    check("member: MCP tools/list with the OAuth token", status == 200 and "search_parts" in str(listed), str(status))
    stored = psql("SELECT left(upstream_refresh_token, 3) || ' ' || (membership_checked_at IS NOT NULL) "
                  "FROM users WHERE email = 'e2e-member@example.test'")
    check("member: upstream refresh token stored encrypted, membership checked", stored == "v1. true", stored)

    status, tokens = kina_json("POST", "/oauth/token", form={
        "grant_type": "refresh_token", "refresh_token": tokens["refresh_token"], "client_id": client_id})
    check("member: refresh within the re-check interval", status == 200, str(status))

    age_membership("e2e-member", 2)
    status, tokens = kina_json("POST", "/oauth/token", form={
        "grant_type": "refresh_token", "refresh_token": tokens["refresh_token"], "client_id": client_id})
    fresh = psql("SELECT membership_checked_at > now() - interval '5 minutes' FROM users "
                 "WHERE email = 'e2e-member@example.test'")
    check("member: refresh with an aged check re-verifies at Authentik", status == 200 and fresh == "t",
          f"{status} {tokens if status != 200 else ''} fresh={fresh}")

    bootstrap.remove_from_group(AUTHENTIK, os.environ["AUTHENTIK_BOOTSTRAP_TOKEN"], info["group_pk"],
                                info["users"]["e2e-member"])
    age_membership("e2e-member", 2)
    access = tokens.get("access_token") if isinstance(tokens, dict) else None
    status, error = kina_json("POST", "/oauth/token", form={
        "grant_type": "refresh_token", "refresh_token": tokens.get("refresh_token", ""), "client_id": client_id})
    check("member removed from the group in Authentik: refresh is invalid_grant",
          status == 400 and isinstance(error, dict) and error.get("error") == "invalid_grant", f"{status} {error}")
    status, _ = kina_json("POST", "/mcp", token=access, body={"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    check("member removed: the access token is revoked (401)", status == 401, str(status))
    revoked = psql("SELECT access_revoked_at IS NOT NULL FROM users WHERE email = 'e2e-member@example.test'")
    check("member removed: users.access_revoked_at set", revoked == "t", revoked)


def scenario_nonmember(passwords) -> None:
    browser = Browser()
    verifier, challenge = pkce()
    kind, url, status, text = browse(browser, authorize_url(register_client(), challenge), "e2e-nonmember",
                                     passwords["e2e-nonmember"])
    check("non-member: refused by Authentik's application binding", kind == "authentik-denied", f"{kind} {text[:80]}")
    status, _, _ = browser.request("GET", KINA + "/")
    check("non-member: no KINA session", status == 302, str(status))


def scenario_guest(passwords) -> None:
    browser = Browser()
    verifier, challenge = pkce()
    kind, url, status, text = browse(browser, authorize_url(register_client(), challenge), "e2e-guest",
                                     passwords["e2e-guest"])
    check("guest (bound group, not ElectronicsEngineer): passes Authentik, refused by KINA",
          kind == "page" and status == 403 and "/login-denied" in url and "ElectronicsEngineer" in text,
          f"{kind} {status} {url}")
    status, _, _ = browser.request("GET", KINA + "/")
    check("guest: no KINA session", status == 302, str(status))


def compose(*args: str, check_result: bool = True) -> None:
    subprocess.run(["docker", "compose", "-f", str(HERE / "compose.yaml"), *args], cwd=HERE, check=check_result)


def write_env() -> dict[str, str]:
    env = {"PG_PASS": secrets.token_urlsafe(24), "AUTHENTIK_SECRET_KEY": secrets.token_urlsafe(50),
           "AUTHENTIK_BOOTSTRAP_PASSWORD": secrets.token_urlsafe(18),
           "AUTHENTIK_BOOTSTRAP_TOKEN": secrets.token_urlsafe(40)}
    (HERE / ".env").write_text("".join(f"{k}={v}\n" for k, v in env.items()))
    os.chmod(HERE / ".env", 0o600)
    return env


def read_env() -> dict[str, str]:
    lines = (HERE / ".env").read_text().splitlines()
    return dict(line.split("=", 1) for line in lines if "=" in line)


def start_kina(info: dict) -> subprocess.Popen:
    jar = ROOT / "target" / "kina.jar"
    if not jar.exists():
        raise SystemExit("target/kina.jar missing: run ./mvnw -q -DskipTests package first")
    for url in (KINA + "/", KINA_METRICS + "/actuator/health"):
        try:
            urllib.request.urlopen(url, timeout=2)
            in_use = True
        except urllib.error.HTTPError:
            in_use = True  # something answers on that port
        except (urllib.error.URLError, OSError):
            in_use = False
        if in_use:
            raise SystemExit(f"{url} is already in use (a KINA left over from --keep?); stop it first")
    out = HERE / "out"
    out.mkdir(exist_ok=True)
    env = {**os.environ, "PORT": KINA.rsplit(":", 1)[1], "KINA_METRICS_PORT": KINA_METRICS.rsplit(":", 1)[1],
           "KINA_MODE": "prod", "KINA_PUBLIC_BASE_URL": KINA,
           "SPRING_DATASOURCE_URL": f"jdbc:postgresql://localhost:{KINA_DB_PORT}/kina",
           "SPRING_DATASOURCE_USERNAME": "kina", "SPRING_DATASOURCE_PASSWORD": "kina",
           "OIDC_ISSUER_URI": info["issuer"], "OIDC_CLIENT_ID": info["client_id"],
           "OIDC_CLIENT_SECRET": info["client_secret"], "OIDC_REQUIRED_GROUPS": "ElectronicsEngineer",
           "OIDC_EXTRA_SCOPES": "groups",
           "KINA_TOKEN_ENCRYPTION_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
           "KINA_JLCPCB_DATA_DIR": str(out / "jlcpcb")}
    log = open(out / "kina.log", "w")
    process = subprocess.Popen(["java", "-jar", str(jar), "--kina.jlcpcb.auto-download=false",
                                "--kina.ranking.cross-encoder.auto-download=false"],
                               env=env, stdout=log, stderr=subprocess.STDOUT, cwd=out)
    deadline = time.time() + 90
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(KINA_METRICS + "/actuator/health", timeout=3) as response:
                if response.status == 200:
                    return process
        except (urllib.error.URLError, OSError):
            pass
        if process.poll() is not None:
            raise SystemExit(f"KINA exited early, see {out / 'kina.log'}")
        time.sleep(1)
    raise SystemExit(f"KINA did not start, see {out / 'kina.log'}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--keep", action="store_true", help="leave Authentik and KINA running")
    parser.add_argument("--reuse", action="store_true", help="reuse a running Authentik and its .env")
    args = parser.parse_args()

    env = read_env() if args.reuse and (HERE / ".env").exists() else write_env()
    os.environ["AUTHENTIK_BOOTSTRAP_TOKEN"] = env["AUTHENTIK_BOOTSTRAP_TOKEN"]
    kina = None
    try:
        compose("up", "-d")
        print("waiting for Authentik ...", flush=True)
        bootstrap.wait_ready(AUTHENTIK)
        passwords = {u: secrets.token_urlsafe(16) for u in ("e2e-member", "e2e-nonmember", "e2e-guest")}
        info = bootstrap.bootstrap(AUTHENTIK, env["AUTHENTIK_BOOTSTRAP_TOKEN"],
                                   KINA + "/login/oauth2/code/oidc", passwords, secrets.token_urlsafe(32))
        print(f"Authentik configured: issuer {info['issuer']}", flush=True)
        kina = start_kina(info)
        for scenario in (lambda: scenario_member(passwords, info), lambda: scenario_nonmember(passwords),
                         lambda: scenario_guest(passwords)):
            try:
                scenario()
            except Exception as e:  # report and continue with the next scenario
                check("scenario error", False, repr(e)[:300])
        log = (HERE / "out" / "kina.log").read_text(errors="replace")
        check("KINA log: membership re-check outcome recorded",
              "no longer in a required group" in log or "rejected by the identity provider" in log,
              "group gone" if "no longer in a required group" in log else "invalid_grant from Authentik")
    finally:
        if kina is not None and not args.keep:
            kina.terminate()
            kina.wait(timeout=30)
        if not args.keep:
            compose("down", "-v", "--remove-orphans", check_result=False)
            (HERE / ".env").unlink(missing_ok=True)
    failed = [name for name, ok, _ in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
