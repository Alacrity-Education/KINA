#!/usr/bin/env python3
"""Configure a disposable Authentik for KINA's group-authorisation end-to-end test (standard library only).

Uses the admin API token from AUTHENTIK_BOOTSTRAP_TOKEN and creates (get-or-create, so it can be re-run):
  - group ElectronicsEngineer, plus group KinaGuests (bound to the application too, so Authentik lets its members
    through and KINA's own group check is what refuses them);
  - users e2e-member (ElectronicsEngineer), e2e-nonmember (no group) and e2e-guest (KinaGuests), with passwords;
  - a scope mapping "groups" (scope_name groups, the user's group names);
  - an OAuth2/OIDC provider "KINA e2e" (confidential, grant types authorization_code + refresh_token, strict redirect
    URI, RS256 signing key, scopes openid, email, profile, offline_access and groups, implicit consent) and an application with slug "kina";
  - policy bindings of both groups on the application (policy engine mode "any").

Prints the issuer, client ID and client secret as JSON on stdout (the secret is generated per run, never stored in
the repository). Usage:
  AUTHENTIK_URL=http://localhost:19000 AUTHENTIK_BOOTSTRAP_TOKEN=... python3 bootstrap.py
"""
from __future__ import annotations

import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

GROUP = "ElectronicsEngineer"
GUEST_GROUP = "KinaGuests"
APP_SLUG = "kina"
CLIENT_ID = "kina-e2e"
GROUPS_EXPRESSION = 'return {"groups": [group.name for group in request.user.groups.all()]}'
TIMEOUT = 15


class Api:
    def __init__(self, base: str, token: str):
        self.base = base.rstrip("/")
        self.token = token

    def call(self, method: str, path: str, body=None, ok=(200, 201, 204)):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + "/api/v3" + path, data=data, method=method, headers={
            "Authorization": "Bearer " + self.token, "Accept": "application/json",
            "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
                raw = response.read()
                status = response.status
        except urllib.error.HTTPError as e:
            raw, status = e.read(), e.code
        if status not in ok:
            raise RuntimeError(f"{method} {path} -> {status}: {raw[:500]!r}")
        return json.loads(raw) if raw else None

    def find(self, path: str, **query):
        results = self.call("GET", path + "?" + urllib.parse.urlencode({**query, "page_size": 200}))["results"]
        return results[0] if results else None

    def get_or_create(self, path: str, lookup: dict, body: dict):
        found = self.find(path, **lookup)
        return found if found else self.call("POST", path, body)


def wait_ready(base: str, seconds: int = 300) -> None:
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(base.rstrip("/") + "/-/health/ready/", timeout=5) as response:
                if response.status in (200, 204):
                    return
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(3)
    raise RuntimeError(f"Authentik at {base} not ready after {seconds}s")


def wait_api(api: Api, seconds: int = 240) -> None:
    """The bootstrap token and the default flows are created by the worker's startup tasks; wait for them."""
    deadline = time.time() + seconds
    last = None
    while time.time() < deadline:
        try:
            if api.find("/flows/instances/", slug="default-provider-authorization-implicit-consent") \
                    and api.find("/flows/instances/", slug="default-authentication-flow"):
                return
        except RuntimeError as e:
            last = e
        time.sleep(3)
    raise RuntimeError(f"Authentik API/default flows not available: {last}")


def bootstrap(base: str, token: str, redirect_uri: str, passwords: dict[str, str], client_secret: str) -> dict:
    api = Api(base, token)
    wait_api(api)
    authz_flow = api.find("/flows/instances/", slug="default-provider-authorization-implicit-consent")["pk"]
    invalidation = api.find("/flows/instances/", slug="default-provider-invalidation-flow")
    keypair = next(k for k in api.call("GET", "/crypto/certificatekeypairs/?page_size=200")["results"]
                   if k["name"] == "authentik Self-signed Certificate")

    scope_mappings = api.call("GET", "/propertymappings/provider/scope/?page_size=200")["results"]
    managed = {m.get("managed"): m["pk"] for m in scope_mappings}
    mapping_pks = [managed[f"goauthentik.io/providers/oauth2/scope-{s}"]
                   for s in ("openid", "email", "profile", "offline_access")]
    groups_mapping = api.get_or_create("/propertymappings/provider/scope/", {"name": "KINA e2e groups"}, {
        "name": "KINA e2e groups", "scope_name": "groups", "description": "Group names for KINA",
        "expression": GROUPS_EXPRESSION})
    mapping_pks.append(groups_mapping["pk"])

    group = api.get_or_create("/core/groups/", {"name": GROUP}, {"name": GROUP})
    guests = api.get_or_create("/core/groups/", {"name": GUEST_GROUP}, {"name": GUEST_GROUP})

    users = {}
    for username, groups in (("e2e-member", [group["pk"]]), ("e2e-nonmember", []), ("e2e-guest", [guests["pk"]])):
        user = api.get_or_create("/core/users/", {"username": username}, {
            "username": username, "name": username, "email": f"{username}@example.test", "is_active": True,
            "path": "users", "groups": groups})
        # Re-runs restore the intended membership (the test removes the member from the group).
        api.call("PATCH", f"/core/users/{user['pk']}/", {"groups": groups, "is_active": True})
        api.call("POST", f"/core/users/{user['pk']}/set_password/", {"password": passwords[username]})
        users[username] = user["pk"]

    provider_body = {
        "name": "KINA e2e", "authorization_flow": authz_flow, "client_type": "confidential",
        # Authentik 2026.8: a provider created through the API has no grant types unless they are listed.
        "grant_types": ["authorization_code", "refresh_token"],
        "client_id": CLIENT_ID, "client_secret": client_secret,
        "redirect_uris": [{"matching_mode": "strict", "url": redirect_uri}],
        "signing_key": keypair["pk"], "property_mappings": mapping_pks, "sub_mode": "hashed_user_id",
        "include_claims_in_id_token": True, "access_token_validity": "hours=1",
        "refresh_token_validity": "days=90"}
    if invalidation:
        provider_body["invalidation_flow"] = invalidation["pk"]
    provider = api.find("/providers/oauth2/", name="KINA e2e")
    if provider:
        provider = api.call("PATCH", f"/providers/oauth2/{provider['pk']}/", provider_body)
    else:
        provider = api.call("POST", "/providers/oauth2/", provider_body)

    # The application list is filtered by the caller's access unless superuser_full_list is set.
    app = api.get_or_create("/core/applications/", {"slug": APP_SLUG, "superuser_full_list": "true"}, {
        "name": "KINA", "slug": APP_SLUG, "provider": provider["pk"], "policy_engine_mode": "any"})
    existing = api.call("GET", f"/policies/bindings/?target={app['pk']}&page_size=200")["results"]
    bound = {b.get("group") for b in existing}
    for order, group_pk in enumerate((group["pk"], guests["pk"])):
        if group_pk not in bound:
            api.call("POST", "/policies/bindings/", {"target": app["pk"], "group": group_pk, "order": order,
                                                     "enabled": True, "negate": False, "timeout": 30})
    return {"issuer": f"{base.rstrip('/')}/application/o/{APP_SLUG}/", "client_id": CLIENT_ID,
            "client_secret": client_secret, "group_pk": group["pk"], "users": users}


def remove_from_group(base: str, token: str, group_pk: str, user_pk) -> None:
    Api(base, token).call("POST", f"/core/groups/{group_pk}/remove_user/", {"pk": user_pk})


if __name__ == "__main__":
    url = os.environ.get("AUTHENTIK_URL", "http://localhost:19000")
    wait_ready(url)
    result = bootstrap(url, os.environ["AUTHENTIK_BOOTSTRAP_TOKEN"],
                       os.environ.get("KINA_REDIRECT_URI", "http://localhost:18080/login/oauth2/code/oidc"),
                       {u: os.environ.get("E2E_PASSWORD", "change-me-" + secrets.token_hex(4))
                        for u in ("e2e-member", "e2e-nonmember", "e2e-guest")},
                       secrets.token_urlsafe(32))
    json.dump(result, sys.stdout, indent=2)
    print()
