# Connecting KINA to Claude for the organisation (research, 2026-10-05)

Goal: get KINA into every `ElectronicsEngineer` member's Claude with as few steps as possible, and keep everyone else out.

Context:
- Users have Google accounts on `@alacrity.ro`.
- They sign in with Google into the organisation's Authentik.
- Only members of the Authentik group `ElectronicsEngineer` may use KINA.
- The organisation runs the HTTPS reverse proxy in front of KINA.

This is research only. No code was changed.

## 0. Summary

**Recommendation:** keep KINA as the OAuth authorization server for Claude (architecture A) and harden it. Concretely:

- **Login:** KINA keeps delegating login to Authentik over OIDC.
- **Group check at two points:**
  - Authentik denies non-members at its own login, through a group binding on the KINA application.
  - KINA rejects any login whose `groups` claim lacks `ElectronicsEngineer`.
- **Fast removal:** KINA re-checks membership against Authentik at least every hour while tokens are in use. It does this by refreshing the user's Authentik session server-side. OAuth access tokens drop from 30 days to 1 hour, so a removed member loses access within about an hour.
- **Client identity:** KINA adds support for Client ID Metadata Documents (CIMD). Claude can then use its Anthropic-hosted identity instead of registering a new client each time. KINA can skip its consent page for that verified identity.
- **Organisation connector:** if the organisation has Claude Team or Enterprise, an Owner adds KINA once as an organisation connector. Members then only click Connect and sign in.

**Why not let Authentik be the authorization server (B)?**
- Authentik 2026.8 does have dynamic client registration, but it needs a bearer token, so Claude cannot register itself.
- Authentik also ignores the `resource` parameter.
- So with B, every user on a Pro or Max plan must paste a client ID into Claude. Claude Code users need extra flags too.
- B remains a good option if the organisation is on Team or Enterprise, because the admin enters the client ID once for everyone. See section 4.

**Effort:** about 7 to 9 developer days for the recommended changes (section 7).

**LDAP:** not needed. OIDC already carries the `groups` claim. LDAP is only worth it as an optional backend for the out-of-band membership re-check (section 3.6).

## 1. Sources and what was verified

All sources were read on **2026-10-05**.

**Claude:**
- Anthropic's connector developer docs. https://claude.com/docs/connectors/building and https://claude.com/docs/connectors/building/authentication (the "Claude auth page" below).
- https://claude.com/docs/connectors/custom/add-unlisted (the "add connector page").
- https://claude.com/docs/connectors/building/enterprise-managed-auth.
- https://claude.com/docs/directory/publish and https://claude.com/docs/connectors/building/review-criteria.
- https://platform.claude.com/docs/en/api/ip-addresses.
- https://support.claude.com/en/articles/11175166-getting-started-with-custom-connectors-using-remote-mcp.
- Claude Code: https://code.claude.com/docs/en/mcp, https://code.claude.com/docs/en/managed-mcp and https://code.claude.com/docs/en/legal-and-compliance.
- The older help article "Building custom connectors via remote MCP servers" (support.claude.com 11503834) now returns 404. Its content moved to claude.com/docs/connectors.

**MCP specification:** https://modelcontextprotocol.io/specification/2025-11-25/changelog.

**Authentik** (current docs, version 2026.8):
- https://docs.goauthentik.io/add-secure-apps/providers/oauth2/
- .../oauth2/dynamic-client-registration/
- .../oauth2/client_credentials/
- .../property-mappings/
- .../applications/manage_apps/
- .../providers/ldap/
- https://docs.goauthentik.io/releases/2026.8/
- .../sources/social-logins/google/cloud/
- .../policies/types/expression/whitelist_email/
- Source files on GitHub: `authentik/providers/oauth2/models.py`, `id_token.py`, `views/token.py`, `blueprints/system/providers-oauth2.yaml`.
- Issues #8751, #14545, #22070, #25520.

**KINA:**
- `docs/DESIGN.md` sections 6, 7 and 10, `docs/OPERATIONS.md`, `README.md`.
- `security/*`, `oauth/*`, `config/KinaProperties.java`, `db/migration/V1__init.sql`.

**Labels used in this document:**
- **Verified** means the page itself says so.
- **Inferred** means it is my reasoning, or it came from a search snippet or a community issue. Inferred points are marked.
- Nothing was tested against a live Claude account or a live Authentik instance.

## 2. How Claude connects to remote MCP servers today

### 2.1 claude.ai, Claude Desktop, mobile (custom connectors)

**One auth stack for every surface** (Verified): "The same authentication infrastructure backs claude.ai, Claude Desktop, Claude mobile, Claude Code, and Cowork" (Claude auth page).

**Transport** (Verified):
- Use Streamable HTTP. "Claude also supports the legacy HTTP+SSE transport, which is being deprecated."
- A URL ending in `/sse` selects SSE.
- KINA serves Streamable HTTP (stateless) at `/mcp`, which fits.

**Spec versions** (Verified): "Claude follows the 2025-03-26, 2025-06-18, and 2025-11-25 authorization specifications."

**Discovery rules.** These are stricter than the spec in places (Verified, Claude auth page):
- **401, never 200:** the MCP endpoint must answer `401` with `WWW-Authenticate: Bearer resource_metadata=...`. Claude ignores that header on a `200`. KINA already does this.
- **`resource` must match:** "`resource` must equal the URL as the user enters it in Claude."
  - KINA returns `<public>/mcp`.
  - So users must enter exactly `https://<host>/mcp`, with no trailing slash and no other spelling.
- **First server only:** Claude uses only the first entry of `authorization_servers`. It does not fall back to later entries.
- **Metadata formats:** authorization server metadata may be RFC 8414 or OpenID Connect Discovery.

**PKCE** (Verified): `S256` on every authorization request.

**Resource indicator (RFC 8707):**
- Verified for Enterprise Managed Auth: Claude sends `resource`.
- Inferred for the normal flow, because the 2025-06-18 and 2025-11-25 specs require it. KINA already accepts and stores `resource`.

**How Claude identifies itself to the authorization server.** Three options (Verified, add connector page):

| Option in the dialog | Mechanism | When Claude can use it |
|---|---|---|
| "Use Claude's published identity" (recommended) | CIMD: `client_id` is a URL to a metadata document that Anthropic hosts | Only if the server's metadata advertises `"client_id_metadata_document_supported": true` and lists `"none"` in `token_endpoint_auth_methods_supported`. Otherwise Claude falls back to DCR (Verified). |
| "Register automatically" | RFC 7591 dynamic client registration (DCR) | The server has an open `registration_endpoint`. Anthropic notes "DCR causes Claude to register a new client on every fresh connection." |
| "Use your own OAuth client" | Pre-registered client ID, optional secret | Always. "Leave the secret blank unless your authorization server requires one." Older dialog layouts show the same fields under "Advanced settings". |

Related points:
- **DCR is not required.** A pre-registered client ID works (Verified).
- **Settings are fixed once added:** "You can't change authentication settings after you add a connector." To change them, remove the connector and add it again (Verified).
- **Other mechanisms exist.** Anthropic can hold a confidential client's secret (`oauth_anthropic_creds`), or each customer can enter their own client credentials. Both are arranged with Anthropic through mcp-review@anthropic.com (Verified). Static headers set by an Owner are "Beta, for a limited set of organizations" (Verified).
- **No machine-to-machine grant:** "A machine-to-machine `client_credentials` grant isn't supported" (Verified).

**Redirect URIs** (Verified):
- The hosted apps (web, Desktop, mobile, Cowork) use exactly `https://claude.ai/api/mcp/auth_callback`.
- `https://claude.com/api/mcp/auth_callback` is not mentioned in the current docs. Register only the claude.ai one. Adding the claude.com one as well does no harm on an allowlist-based server.

**Tokens and refresh** (Verified):
- **When Claude refreshes:** reactively on a `401`, and proactively up to 5 minutes before expiry.
- **Failed refresh:** the token endpoint must return `invalid_grant` when a refresh token is no longer valid. Claude then asks the user to reconnect.
- **Rotation:** refresh tokens must rotate for public clients. KINA already rotates.
- **`offline_access`:** Claude appends it automatically if the server lists it in `scopes_supported`.
- **OAuth endpoint timeouts:** Claude waits up to 10 seconds for discovery, registration and token responses, and up to 30 seconds for refresh requests.

**Tool calls** (Verified):
- **Timeout:** "240 seconds per tool call" on claude.ai and Desktop. A slow KINA call takes 2 minutes plus up to 60 seconds of batch ranking, which fits with a small margin.
- **Result size:** at most about 150,000 characters.

**Network** (Verified):
- **Anthropic's outbound range:** "Anthropic's outbound traffic to your server originates from `160.79.104.0/21`." The IP page says "These addresses will not change without notice." It also lists five older `34.162.x.x/32` addresses as phased out.
- **Public reachability:** the server "must be reachable over the public internet from Anthropic's IP ranges."
- **WAF warning:** the auth page warns that "a WAF in front of your identity provider can break the flow."

### 2.2 Plans and organisation connectors

**Who can add custom connectors** (Verified): Free, Pro, Max, Team and Enterprise. Free accounts are "limited to one custom connector."

**Team and Enterprise** (Verified):
- An Owner adds the connector in **Organization settings > Connectors > Add > Custom > Web**. On Enterprise, a custom role with library management can do this too.
- Members "can't add a custom connector yourself".
- Each member then goes to **Customize > Connectors**, finds the entry labelled Custom, and clicks **Connect**. Each member authenticates with their own account.
- The exception is organisation-wide static headers, where "every member's requests carry the same key".

**Enterprise Managed Auth** (cross-app access, ID-JAG; RFC 7523 JWT bearer grant). Verified, on Team and Enterprise:
- The authorization server must accept `urn:ietf:params:oauth:grant-type:jwt-bearer`.
- "DCR isn't supported with Enterprise Managed Auth"; it needs CIMD or Anthropic-held credentials.
- Anthropic points to Okta Cross App Access. Interest is registered through a form.
- I found no evidence that Authentik can issue ID-JAG assertions (Inferred from the absence of any doc or issue). So this path does not apply here today.

### 2.3 Claude Code

Source: https://code.claude.com/docs/en/mcp (Verified).

**Adding the server:**
- `claude mcp add --transport http <name> <url>`.
- Static headers with `--header "Authorization: Bearer ..."`.
- OAuth through `/mcp`. Tokens are "stored securely and refreshed automatically".
- Pre-registered clients: `--client-id`, `--client-secret` (masked prompt, or the `MCP_CLIENT_SECRET` env var) and `--callback-port`. In JSON, an `oauth` object `{clientId, callbackPort, authServerMetadataUrl, scopes}`.
- CIMD is discovered automatically. Claude Code's own metadata document is `https://claude.ai/oauth/claude-code-client-metadata`.

**Redirect** (Verified):
- A loopback redirect on an ephemeral port, for example `http://localhost:3118/callback`.
- The server must accept `http://localhost/callback` and `http://127.0.0.1/callback` with any port. KINA's DCR already accepts loopback with any port.

**Distribution** (Verified):
- A committed project `.mcp.json` (users approve it on first use). It supports `${VAR}` expansion, including in headers.
- `managed-mcp.json` under `/etc/claude-code/` and the other system paths. It takes exclusive control.
- The `managedMcpServers` managed setting (v2.1.259 or later), with allow and deny lists.

**Connectors added on claude.ai also appear in Claude Code** (Verified) when the user logs into Claude Code with their claude.ai subscription. They do not appear with an API key, Bedrock or similar. So a user who connected KINA on claude.ai usually needs no extra step in Claude Code.

**Timeouts** (Verified): `MCP_TOOL_TIMEOUT` defaults to about 28 hours, so the 2 to 3 minute KINA calls are not a problem.

### 2.4 "OAuth to Anthropic" and the connector directory

**No "Sign in with Claude"** (Verified):
- Anthropic's terms say "Anthropic does not permit third-party developers to offer Claude.ai login into their own applications."
- There is no mechanism where Anthropic acts as the identity provider for an MCP server (Inferred: none found).
- KINA must keep its own authorization server, or delegate to the organisation's IdP.

**What Anthropic does offer:**
- CIMD, a published client identity for Claude. KINA can trust it.
- Anthropic-held client credentials, by arrangement.

**The public Connectors Directory** (Verified):
- Paid-plan users submit through `https://claude.ai/directory/manage`.
- Requirements include tool annotations, test credentials for a populated account, public documentation and first-party APIs.
- Submissions are listed as "Community connector" by default.
- This is for public products. It is not useful for a private internal tool gated by one company's group. An organisation connector (2.2) is the right tool for that.

## 3. Authentik capabilities (version 2026.8)

### 3.1 OAuth2/OIDC provider

**Grants** (Verified): authorization code (with or without PKCE), refresh token, client credentials, device code, token exchange, and others.

**Client types** (Verified): confidential and public. "Public clients ... should use the authorization code flow with PKCE."

**PKCE is verified but not required** (Verified, issue #25520): "authentik cannot **require** PKCE". It accepts `plain` and `S256`. Claude always sends `S256`, so in practice this only matters for other clients.

**Refresh tokens** (Verified): only when the client requests `offline_access` and the provider has the `offline_access` scope mapping.

**Default lifetimes** (Verified, per provider, configurable):
- access code: 1 minute
- access token: 1 hour
- refresh token: 30 days

**Redirect URIs** (Verified):
- A list, with `strict` or `regex` matching. A literal dot must be escaped as `\.` in regex mode.
- Do not leave the field blank. Authentik then saves the first URI that gets used.

**`groups` claim** (Verified):
- The built-in `profile` scope mapping already emits `"groups": [group.name for group in request.user.groups.all()]`.
- KINA requests `openid profile email`, so the claim already arrives in KINA today. Nothing reads it yet.
- With "Include claims in id_token" on (the default), the claims also go into the ID token.

**Token format** (Verified):
- JWTs are always signed: RS256 or ES256 when a signing key is selected, otherwise HS256 with the client secret.
- `aud` equals the provider's `client_id`.
- There is an open request (#22070) for RFC 9068 access tokens with a resource audience.

**Endpoints** (Verified):
- Shared: `/application/o/authorize/`, `/application/o/token/`, `/application/o/userinfo/`, `/application/o/revoke/`, `/application/o/introspect/`.
- Per application: `/application/o/<slug>/jwks/` and `/application/o/<slug>/end-session/`.
- Issuer, per-provider mode (default): `https://<authentik>/application/o/<slug>/`.

**Introspection (RFC 7662)** (Verified):
- "A confidential provider can introspect tokens that it issued."
- Another provider can introspect them only if it is listed under the first provider's "Federated OAuth2/OpenID Providers".

### 3.2 Dynamic client registration, CIMD and resource indicators

**DCR (RFC 7591) shipped in 2026.8** (Verified):
- Endpoint: `/application/o/<slug>/register/`.
- "The client authenticates to the registration endpoint using a Bearer access token that includes the required DCR scope" (`goauthentik.io/oidc/dcr`).
- It is "not an anonymous or open registration unless the configured policies explicitly allow that behavior."
- Every registration creates a new application and provider. There are no RFC 7592 update or delete endpoints.

**What this means for Claude** (Inferred):
- Claude's DCR call carries no bearer token, so Claude cannot self-register at Authentik.
- Whether policies can really open registration to anonymous callers is unclear, and I could not confirm it.
- Even if they could, each Claude connection would create a new Authentik application. Would it carry the group binding? Unknown.
- Community projects report the same blocker (GitHub issues `mindovermachine-dev/policy-system#170` and `faviann/homelab-iac#235`). They use a manually registered client or an auth proxy.

**CIMD:** no support, issue or documentation found (Inferred). Authentik metadata will not advertise `client_id_metadata_document_supported`.

**Resource indicators (RFC 8707):** not supported (Verified, issue #14545). The token code ignores `resource`, and `aud` is always the client ID.

**RFC 9728:** not relevant to Authentik. Protected resource metadata is served by the resource server (KINA) in any architecture.

### 3.3 Access control by group

Verified, https://docs.goauthentik.io/add-secure-apps/applications/manage_apps/:
- "You can bind policies, groups, and users to grant access to an application."
- **"When nothing is bound, everyone has access."** So a binding of the group `ElectronicsEngineer` to the KINA application is mandatory.
- Policy engine mode `any` or `all` decides how multiple bindings combine.
- Inferred: the binding is evaluated when the user authorizes. Whether Authentik re-evaluates it on a refresh-token grant is not documented. KINA should therefore check the claim itself.

### 3.4 Google social login

- **Callback** (Verified): `https://<authentik>/source/oauth/callback/<slug>/`.
- **Restricting to one domain** (Verified): the documented way is an expression policy on the source flows (`whitelist_email`), allowing only `alacrity.ro`.
- **Internal consent screen** (Inferred): marking the Google OAuth consent screen as "Internal" also restricts sign-in to the Workspace domain.
- The user says Google login is already in place. Check that one of these domain restrictions is active. The group binding (3.3) is the real gate in any case.

### 3.5 LDAP provider

Verified: the LDAP provider runs in an outpost. It exposes `ou=users` and `ou=groups` for applications that only speak LDAP.

### 3.6 Where LDAP would be useful here

At login, OIDC already delivers `groups`, so LDAP adds nothing there.

LDAP is only useful for an **out-of-band** membership check: asking "is this user still in the group?" while the user is not present, for example when a long-lived token is used. Three ways to do that:

| Way | What it needs | Notes |
|---|---|---|
| (a) Refresh the user's Authentik session server-side and read `groups` from userinfo | A stored, encrypted Authentik refresh token per user | Standard OIDC and provider-neutral. Recommended. |
| (b) LDAP search against the Authentik LDAP outpost | A bind account and an outpost | Standard protocol. Reasonable if the outpost already exists. |
| (c) Authentik REST API | A service-account token | Authentik-specific. Conflicts with KINA's "no provider-specific code" rule. |

LDAP as the **login** mechanism (KINA asking for passwords) is worse in every way. It would bypass Google login and Authentik's MFA. Do not use it.

## 4. Architectures compared

Notation:
- **U** = steps for each user.
- **Adm** = one-time admin steps.
- **Pro** = Free, Pro and Max plans.
- **Org** = Team and Enterprise with an organisation connector.

### A. KINA stays the authorization server, login delegated to Authentik, plus group authorisation

**Flow:**
1. Claude discovers KINA's metadata.
2. Claude registers by DCR (today) or by CIMD (after the change).
3. The user is sent to `/oauth/authorize`.
4. KINA redirects to Authentik, where the user signs in with Google.
5. Authentik denies non-members (group binding).
6. KINA checks the `groups` claim.
7. KINA shows its consent page, or skips it for CIMD Claude.
8. KINA issues its own token pair.

**User steps:**
- **Pro:** Connectors > Add custom connector > name `KINA`, URL `https://<host>/mcp` > Add > Connect > sign in (often one click, since an Authentik session usually exists) > Approve (skipped with CIMD). Nothing to copy.
- **Org:** Connect > sign in. Approve too, unless CIMD auto-approval applies.
- **Claude Code:**
  - Nothing to do if the user logs in with their claude.ai subscription; the connector is inherited.
  - Otherwise: `claude mcp add --transport http kina https://<host>/mcp`, then `/mcp` and sign in. Or commit a `.mcp.json`.

**Admin steps:**
- Authentik: one confidential OIDC provider for KINA web login (it exists today in principle), plus the group binding.
- KINA: env variables.

**Group enforcement:**
- Authentik at login (binding).
- KINA at login (claim check).
- KINA again on every OAuth refresh and periodically for other tokens (upstream re-check).
- Removal takes effect within the access token lifetime, about 1 hour.

**Failure modes:**
- **Authentik down:** sign-in and re-checks fail. A short grace window keeps current users running; new sign-ins wait.
- **Wrong URL spelling:** the `resource` mismatch makes Claude refuse.
- **Client sprawl:** DCR adds a client row on every new connection. A cleanup job fixes it; CIMD removes it.

**Security:**
- KINA keeps a full authorization server. It is already built and covered by tests, but it is code KINA must maintain.
- The open `/oauth/register` endpoint needs rate limiting.
- The consent page protects against malicious DCR clients.

**KINA changes:** see section 7.

### B. Authentik is the authorization server, KINA only the resource server

**Flow:**
1. `/.well-known/oauth-protected-resource` lists `authorization_servers: ["https://<authentik>/application/o/kina-claude/"]`.
2. Claude reads Authentik's OIDC discovery.
3. The user signs in at Authentik.
4. Claude receives Authentik tokens.
5. KINA validates each JWT by JWKS: `iss`, `aud` equal to the Claude client ID, `exp`, and `ElectronicsEngineer` in `groups`.

**Registration:**
- DCR does not work for Claude (3.2), and CIMD is not supported.
- So the connector must use "Use your own OAuth client": a public client ID (no secret), registered by hand in Authentik with `https://claude.ai/api/mcp/auth_callback`.
- If a user picks the recommended default ("Use Claude's published identity"), Claude falls back to DCR, which fails. The user must then remove the connector and add it again, because auth settings cannot be edited.

**User steps:**
- **Pro:** Add custom connector > name > URL > choose "Use your own OAuth client" > paste client ID > Add > Connect > sign in.
- **Org:** Connect > sign in. The admin entered the client ID once; this is the shortest flow of all.
- **Claude Code:**
  - Nothing to do if the connector is inherited from claude.ai.
  - Otherwise: `claude mcp add --transport http --client-id <id> --callback-port <port> kina https://<host>/mcp`. The second Authentik redirect entry (regex `^http://(localhost|127\.0\.0\.1):[0-9]+/callback$`) or a fixed port must match.
  - The `oauth.clientId` can go into `.mcp.json`.

**Admin steps (Authentik):**
- A public provider "KINA for Claude" with a signing key, the redirect URIs, the `offline_access` mapping, a refresh token validity that suits the organisation, and implicit consent.
- The group binding.
- KINA configuration: issuer, accepted client IDs, required group.

**Group enforcement:**
- Authentik at authorization.
- KINA on every request through the JWT `groups` claim.
- Access tokens last 1 hour by default, so removal takes effect within an hour.
- Inferred: a disabled user's refresh is refused by Authentik. Not verified.

**Audience:**
- Authentik cannot bind tokens to KINA through `resource`.
- Mitigation: a dedicated provider used only for KINA. Then `aud = <that client_id>` effectively means "for KINA". KINA must reject tokens whose `aud` is any other client.

**Other limits:**
- Anthropic's servers must reach Authentik's discovery and token endpoints from `160.79.104.0/21`. A WAF or geo block on Authentik would break this (Claude auth page).
- RFC 8414 path discovery for Authentik's per-provider issuer is unverified. Claude says it accepts OIDC discovery, which Authentik serves.

**KINA changes:**
- Add `spring-boot-starter-oauth2-resource-server`.
- Change the machine chain to validate JWTs, with a groups authorization check.
- Point the protected resource metadata at Authentik.
- Retire `/oauth/*` (about 1,100 lines of code plus tests) and the `oauth_*` tables.
- The REST API and scripts still need a token: keep KINA's static tokens (E), or use Authentik app passwords with the client credentials grant.
- Effort: about 4 to 5 days.

### C. Hybrid: KINA's authorization server bound to Authentik

There are two variants.

**C1. KINA's endpoints, with refresh tied to Authentik.**
- KINA keeps DCR, CIMD, the authorize and token endpoints.
- Each KINA refresh is allowed only if a server-side refresh of the user's Authentik session still shows the group.
- This is A plus the "upstream re-check". The recommendation (section 5) is exactly this.

**C2. KINA as a thin OAuth proxy.**
- KINA answers DCR itself.
- It maps every Claude client onto one pre-registered Authentik client.
- It passes `/authorize` through to Authentik, and passes Authentik tokens back to Claude.
- Problems:
  - It is the "confused deputy" pattern that the MCP security guidance warns about: one upstream client shared by many dynamic clients, with consent hard to attribute.
  - It needs as much code as C1.
  - It gives Claude Authentik tokens with `aud` set to the shared client.
- C2 has no advantage over C1. Not recommended.

### D. Organisation-level connector (Team or Enterprise)

**What it is:**
- It is not an architecture of its own. It is a distribution layer on top of A or B.
- The Owner adds the connector once with the URL (and, for B, the client ID).
- Each member clicks Connect and signs in individually, so per-user identity and group checks still apply.
- Members cannot add custom connectors themselves (2.2). For Team or Enterprise, D is therefore also the way to control what people connect.

**Combinations:**
- **With A:** the user does two clicks plus Approve. With CIMD auto-approval, just two clicks.
- **With B:** the user does two clicks.

**Requirement:** a Team or Enterprise subscription. I do not know whether the organisation has one.

### E. Static tokens (today's web UI)

**Today:** the README tells Claude Code users to create a token in the web UI and pass it with `--header`. With OAuth working in Claude Code (`/mcp`), that is no longer needed.

**Recommendation:**
- Keep personal tokens only for scripts, CI and the plain REST API.
- Remove them from the Claude instructions.
- Make them subject to the same group re-check.
- Cap their validity (30 days today).
- Add a switch `KINA_TOKENS_UI_ENABLED` (default true) so the organisation can turn the page off if nobody scripts against KINA.

**Why not remove the UI entirely?** That would leave no way to call `/api/v1` from scripts in architecture A. In B, scripts could use Authentik app passwords instead.

### Comparison table

| | A (KINA AS + groups) | B (Authentik AS) | C2 (KINA as thin proxy) | D (org connector) | E (static tokens only) |
|---|---|---|---|---|---|
| User steps, Pro plan | Add connector (name, URL), Connect, sign in, Approve. Approve disappears with CIMD. | Add connector (name, URL, choose own client, paste client ID), Connect, sign in | Same as A | n/a (Team or Enterprise only) | Open KINA UI, create token, copy, paste into config. claude.ai cannot use it. |
| User steps, Team or Enterprise | Connect, sign in, Approve (or no Approve with CIMD) | Connect, sign in | Same as A | Connect, sign in | Same as above |
| Claude Code | Inherited from claude.ai, or `claude mcp add` + `/mcp` | Inherited, or `claude mcp add --client-id ... --callback-port ...` | Same as A | Inherited | `--header` with token |
| Admin steps, once | Authentik: 1 confidential provider + group binding. KINA: env. | Authentik: 1 public provider (Claude redirect URIs, loopback regex) + group binding. KINA: env. | Authentik: 1 provider. KINA: env. | Claude Owner adds connector once | None beyond A or B |
| Group enforced at | Authentik login + KINA login + KINA refresh (hourly) | Authentik login + KINA on every request (JWT claim) | Authentik + KINA | Inherits from A or B | KINA periodic re-check (new) |
| Removal takes effect | ≤ 1 h (after changes); up to 30 to 90 days today | ≤ 1 h (access token lifetime) | ≤ 1 h | Inherits | ≤ re-check interval (new) |
| Needs DCR at the AS | DCR or CIMD at KINA (both under KINA's control) | No (manual client ID). Authentik DCR unusable by Claude. | Fake DCR at KINA | No | No |
| Works with claude.ai / Desktop / mobile | Yes (today, verified by KINA's e2e suite against the spec) | Yes in principle (manual client ID). Unverified end to end. | Yes in principle | Yes | No |
| Works with Claude Code | Yes, zero flags | Yes, with flags or `.mcp.json` | Yes | Yes, if the connector is inherited | Yes |
| KINA code change | About 7 to 9 days (section 7) | About 4 to 5 days, removes the AS | About 8 to 10 days | 0 | 1 day (re-check) |
| Main risks | KINA owns an AS (more code); open DCR endpoint; Authentik outage blocks refresh | Users picking the wrong identity option; no `resource` binding; Anthropic must reach Authentik through any WAF; unverified interop | Confused deputy; shared upstream client | Requires paid org plan | Long-lived secrets in files; no claude.ai support |

## 5. Recommendation

**Architecture:** A, extended as in C1, plus D when the organisation has Team or Enterprise. Keep E only for scripts.

**Why:**
1. It gives the shortest flow on every plan without anyone copying a value. A Pro user types a name and a URL, clicks Connect, and signs in with Google. With CIMD, KINA skips its consent page for Claude's verified identity.
2. It works today: the DCR, PKCE and refresh paths already exist and are tested. The work is the group gate and the re-check.
3. It does not depend on Authentik features that are missing (DCR without a token, CIMD, resource indicators) or unverified (refresh policy re-evaluation).
4. Anthropic's servers only talk to KINA, never to Authentik. Authentik's token endpoint can stay off the public internet for server-to-server traffic, as long as KINA can reach it. Users' browsers must still reach Authentik's login pages, as they do today.

**When to choose B instead:** the organisation is on Team or Enterprise, and the team prefers deleting KINA's authorization server over keeping it. Then B plus D gives "Connect, sign in" with less KINA code. Run the B spike in section 8 first.

### 5.1 End-to-end user story (recommended setup)

**First time:**
1. Ana (member of `ElectronicsEngineer`) opens claude.ai.
2. On Team or Enterprise: she opens Customize > Connectors, sees "KINA (Custom)" and clicks Connect.
3. On Pro or Max: she opens Customize > Connectors > Add custom connector, enters `KINA` and `https://kina.alacrity.ro/mcp`, keeps the default identity option, and clicks Add, then Connect.
4. A browser tab opens KINA, which redirects to Authentik. She clicks "Sign in with Google" (skipped if her Authentik session is alive) and picks her `@alacrity.ro` account.
5. Authentik checks the binding. She is a member, so Authentik redirects back to KINA.
6. KINA reads `groups` from the ID token, finds `ElectronicsEngineer`, and stores her user row and her Authentik refresh token (encrypted).
7. Claude is identified by CIMD, so KINA skips the consent page. With DCR it shows "Allow Claude to use KINA as Ana?", and she clicks Approve.
8. The tab returns to Claude, which shows the KINA tools. Done.
9. In Claude Code (subscription login) KINA is already there. With an API key login she runs `claude mcp add --transport http kina https://kina.alacrity.ro/mcp`, then `/mcp`, and signs in once in the browser.

**Every hour, invisible to her:**
1. Claude refreshes the KINA token about 5 minutes before expiry.
2. KINA refreshes her Authentik session server-side, calls userinfo, and confirms the group.
3. KINA issues a new pair.

**When she leaves the group:**
1. Within an hour, Claude's refresh hits KINA.
2. Authentik either refuses the refresh, or userinfo shows no `ElectronicsEngineer`.
3. KINA revokes all her tokens, marks the user `access_revoked_at`, and returns `invalid_grant`.
4. Claude shows the connector as disconnected. Reconnecting fails at Authentik's binding with "access denied".

**When her tokens expire after a long absence (refresh token older than 90 days, or the Authentik refresh token expired):**
1. Claude shows "Reconnect".
2. She clicks it and signs in again. This is the same flow as the first time, minus adding the connector.

**When Authentik is down:**
1. Existing access tokens keep working until they expire, at most 1 hour.
2. KINA refreshes still succeed during a grace window if the last successful membership check is younger than `KINA_MEMBERSHIP_GRACE` (default 4 hours). After that KINA answers `invalid_grant`, and she reconnects once Authentik is back.

### 5.2 Admin setup in Authentik

Assumptions: Authentik at `https://auth.alacrity.ro`, KINA at `https://kina.alacrity.ro`.

1. **Check the Google source.** Confirm it restricts to `alacrity.ro`: the email-domain expression policy bound to the source's enrollment and authentication flows, or the Google consent screen set to Internal.
2. **Create the provider.** Applications > Providers > Create > OAuth2/OpenID Provider:
   - Name: `KINA`.
   - Authorization flow: `default-provider-authorization-implicit-consent`. KINA has its own consent decision; one consent screen is enough.
   - Client type: **Confidential**. Copy the client ID and secret.
   - Redirect URIs: `strict`, `https://kina.alacrity.ro/login/oauth2/code/oidc`.
   - Signing key: select the default certificate, so tokens are RS256.
   - Scopes: `openid`, `email`, `profile` (it contains `groups`) and `offline_access`.
   - Access token validity: keep 1 hour.
   - Refresh token validity: at least 90 days, to match `kina.oauth.refresh-token-validity`, so KINA's upstream re-check does not end sessions earlier than intended.
   - Include claims in id_token: on (the default).
3. **Create the application.** Applications > Create:
   - Name `KINA`, slug `kina`, provider `KINA`, launch URL `https://kina.alacrity.ro/`.
4. **Bind the group.** In the application's Policy / Group / User Bindings, bind the group **`ElectronicsEngineer`**, with policy engine mode `any`. Without a binding everyone has access (3.3).
5. **Note the issuer:** `https://auth.alacrity.ro/application/o/kina/`.

No LDAP outpost is needed. No Claude-specific client is created in Authentik under A.

### 5.3 Admin setup in KINA

New keys are marked **new**.

```
KINA_MODE=prod
KINA_PUBLIC_BASE_URL=https://kina.alacrity.ro
OIDC_ISSUER_URI=https://auth.alacrity.ro/application/o/kina/
OIDC_CLIENT_ID=<from Authentik>
OIDC_CLIENT_SECRET=<from Authentik>
KINA_REQUIRED_GROUPS=ElectronicsEngineer          # new; comma list; empty = no group check (today's behaviour)
KINA_OIDC_GROUPS_CLAIM=groups                     # new
KINA_ALLOWED_EMAIL_DOMAINS=alacrity.ro            # new; optional defence in depth
KINA_OAUTH_ACCESS_TOKEN_VALIDITY=1h               # new; OAuth-issued access tokens (static tokens keep kina.tokens.validity)
KINA_MEMBERSHIP_CHECK_INTERVAL=15m                # new; max age of a membership check before re-checking
KINA_MEMBERSHIP_GRACE=4h                          # new; how long to tolerate an unreachable IdP
KINA_UPSTREAM_TOKEN_KEY=<32 random bytes, base64> # new; AES-GCM key for stored Authentik refresh tokens
KINA_OAUTH_CIMD_TRUSTED_HOSTS=claude.ai           # new; CIMD client_id hosts whose clients skip consent
KINA_TOKENS_UI_ENABLED=true                       # new; set false to hide personal tokens
```

### 5.4 Claude setup

**Team or Enterprise:**
- An Owner goes to Organization settings > Connectors > Add > Custom > Web.
- Name `KINA`, URL `https://kina.alacrity.ro/mcp`.
- Identity: "Use Claude's published identity" once KINA supports CIMD. Before that, "Register automatically".
- Tell members to click Connect.

**Pro or Max:**
- Each user adds the same name and URL.
- The default identity option works: Claude falls back to DCR when KINA does not advertise CIMD.

**Claude Code:**
- Nothing to do with subscription login.
- Otherwise commit this `.mcp.json` to a shared repository, or push it through `managedMcpServers`:

```json
{ "mcpServers": { "kina": { "type": "http", "url": "https://kina.alacrity.ro/mcp" } } }
```

## 6. HTTPS exposure requirements (for the organisation's reverse proxy)

**Name and certificate:**
- A public DNS name, for example `kina.alacrity.ro`, with a publicly trusted TLS certificate. Let's Encrypt is fine.
- Claude needs HTTPS. KINA does not terminate TLS itself.

**Forwarded headers:**
- Set `X-Forwarded-Proto`, `X-Forwarded-Host` (and `X-Forwarded-Port` if the port is not standard).
- Overwrite them; never append.
- Also set `KINA_PUBLIC_BASE_URL`, so request headers cannot change the advertised origin (OPERATIONS.md already says this).

**URL:**
- The connector URL must equal the `resource` KINA advertises: `https://kina.alacrity.ro/mcp`.
- Do not mount KINA under a path prefix unless `KINA_PUBLIC_BASE_URL` includes it.

**Timeouts and buffering:**
- Read and send timeouts of at least 180 seconds, so above about 2.5 minutes. The Caddy and nginx examples in OPERATIONS.md already use 180 seconds.
- Claude's own limit is 240 seconds per tool call.
- Turn off response buffering for `/mcp`.

**What must be reachable anonymously:**
- `/.well-known/oauth-protected-resource` (and `/mcp` variant)
- `/.well-known/oauth-authorization-server`
- `/oauth/register`
- `/oauth/token`
- `/oauth/revoke`
- `/mcp`, which must return a `401` with `WWW-Authenticate`, not a proxy login page.

Do not put proxy-level authentication (forward auth, basic auth, Authentik's proxy outpost) in front of these paths, or Claude cannot discover anything.

**CORS:**
- KINA already sends CORS headers for `/.well-known/**`, `/oauth/register|token|revoke` and `/mcp`.
- The proxy must not strip them or answer `OPTIONS` itself.
- claude.ai's OAuth calls come from Anthropic's servers, not the browser (Inferred from the published egress range). CORS mainly matters for browser-based MCP clients.

**Egress and allowlisting:**
- Anthropic's servers reach KINA from `160.79.104.0/21` (Verified).
- Do not restrict the whole host to that range:
  - users' browsers must reach `/oauth/authorize` and the KINA login redirect;
  - Claude Code connects from users' own machines.
- Optional: allow `/mcp` only from `160.79.104.0/21` plus the office and VPN ranges, if Claude Code users always work from those networks.

**Rate limiting and WAF:**
- Rate-limit `/oauth/register` (for example 10 per minute per IP) and `/oauth/token` (for example 60 per minute per IP).
- Do not apply bot challenges or JavaScript challenges to any of the paths above. Claude's server-side fetches cannot solve them.
- Make sure a WAF does not block `160.79.104.0/21`.

**Consent page:**
- Keep it for DCR clients. Anyone can register a client with any redirect URI, and the page stops a malicious site from silently getting a code for a signed-in user.
- Skip it only for CIMD clients whose `client_id` URL is on a trusted host (`claude.ai`). There the redirect URIs come from a document Anthropic controls.

## 7. KINA implementation plan (architecture A + C1)

Effort is in developer days and includes unit tests and documentation updates.

| # | Change | Classes / files | Effort |
|---|---|---|---|
| 1 | Group and domain gate at login | `KinaProperties.Security` (add `requiredGroups`, `groupsClaim`, `allowedEmailDomains`); `OidcUserSynchronizer.loadUser` (read the claim from ID token or userinfo; throw `OAuth2AuthenticationException("access_denied")` when missing); `LoginErrorController` and template (clear "You are not a member of ElectronicsEngineer" message); `LazyOidcClientRegistrationRepository` (add the `offline_access` scope) | 1 |
| 2 | Users can be blocked; bearer filter honours it | `V4__group_authorisation.sql`: `users` gets `access_revoked_at TIMESTAMPTZ`, `membership_checked_at TIMESTAMPTZ`, `upstream_refresh_token BYTEA` (AES-GCM ciphertext). `UserRepository`, `BearerTokenAuthenticationFilter.toPrincipal` (reject revoked users), `AccessTokenRepository` (add `revokeAllForUser`, which also revokes refresh tokens) | 1 |
| 3 | Upstream membership re-check | New `security/MembershipVerifier`. It refreshes the stored Authentik refresh token at the issuer's token endpoint (5 s timeout), stores the rotated one, calls userinfo and checks `groups`. Called from `TokenController` on `refresh_token` grants, and from `BearerTokenAuthenticationFilter` when `membership_checked_at` is older than `KINA_MEMBERSHIP_CHECK_INTERVAL` (covers static tokens; asynchronous with the grace rule so requests do not wait). On "not a member" or `invalid_grant` from upstream: revoke everything and set `access_revoked_at`. On network error: allow within `KINA_MEMBERSHIP_GRACE`. New `security/UpstreamTokenCipher` (AES-GCM, key from `KINA_UPSTREAM_TOKEN_KEY`). Login stores the upstream refresh token from `OAuth2AuthorizedClient`, through an `OAuth2AuthorizedClientService` wrapper or the login success handler. | 2.5 |
| 4 | Short OAuth access tokens | `KinaProperties.OAuth` (add `accessTokenValidity`, default `1h`); `TokenController` passes it to `AccessTokenService.issue` | 0.25 |
| 5 | CIMD support (Claude's published identity) and consent skip | `OAuthMetadataController` advertises `client_id_metadata_document_supported: true` (`none` is already listed). New `oauth/ClientMetadataDocumentResolver`: when `client_id` is an `https` URL, fetch it (HTTPS only, host allowlist `KINA_OAUTH_CIMD_TRUSTED_HOSTS`, 5 s timeout, 64 KB limit, no redirects to private IPs, cache by HTTP cache headers up to 24 h). Check that the document's `client_id` equals the URL, and match `redirect_uri` against its `redirect_uris`. `OAuthClientRepository.findById` falls back to the resolver. `AuthorizationController` auto-approves trusted CIMD clients. `TokenController` and `ClientAuthenticator` accept CIMD public clients. The `oauth_clients` row is cached with `metadata_url` (migration column). | 2 |
| 6 | DCR hygiene | `oauth_clients.last_used_at` (migration). Scheduled cleanup of DCR clients unused for 30 days that have no live tokens. Per-IP rate limit on `/oauth/register` in KINA as well as at the proxy (simple token bucket). | 0.5 |
| 7 | Static tokens as "script tokens" | `TokenPageController` and templates: wording, and `KINA_TOKENS_UI_ENABLED`. Token creation is allowed only for users who are not revoked and whose membership check is fresh. | 0.5 |
| 8 | Docs and tests | `README.md` "Connecting Claude" (remove the static-token recipe for Claude Code; add the org-connector and `.mcp.json` steps), `DESIGN.md` sections 6, 7 and 10, `OPERATIONS.md` (Authentik guide from 5.2, proxy checklist from section 6). `scripts/e2e/kina_e2e.py` gets an `oidc-groups` suite against a disposable Authentik (or a mock OIDC provider) in Testcontainers: member allowed, non-member denied, removal revokes on refresh, CIMD flow. | 1.5 |
| | **Total** | | **about 9 (7 without CIMD)** |

**Optional additions:**
- **LDAP backend for item 3:** `MembershipVerifier` with an LDAP implementation (`spring-ldap-core`, bind DN and password, search `memberOf`). For organisations that prefer not to store upstream refresh tokens. About 1.5 days.
- **Admin page "revoke user":** for immediate removal without waiting for the re-check. About 0.5 days.

**What does not change:**
- The MCP endpoint and the tool contracts.
- DCR (kept for clients without CIMD).
- PKCE.
- Refresh rotation.
- The `WWW-Authenticate` behaviour.
- Dev mode.

**Order of work:**
- Items 1, 2, 4 and the doc part of 8 make the setup safe for production. About 3 days.
- Items 3, 5, 6 and 7 follow.
- Until item 3 ships: the Authentik binding plus the login check plus a 1-hour access token still leave a 90-day refresh window. Set `kina.oauth.refresh-token-validity` to `7d` temporarily.

## 8. What could not be verified, and how to check

1. **CIMD behaviour of claude.ai against KINA.**
   - The URL of the claude.ai web client's metadata document is not published. Only Claude Code's is: `https://claude.ai/oauth/claude-code-client-metadata`.
   - I could not test whether the default dialog option uses CIMD as soon as KINA advertises it.
   - Check: implement item 5 behind a flag, connect from claude.ai, and log the `client_id`.
2. **Authentik refresh semantics.**
   - Not documented:
     - whether Authentik re-evaluates the application's group binding on a `refresh_token` grant;
     - whether it refuses refresh for a deactivated user;
     - whether it rotates refresh tokens;
     - whether refresh token validity slides.
   - KINA's userinfo check covers the binding question either way. The validity questions decide how often users must sign in again.
   - Check on a test instance: remove a user from the group, deactivate another, and refresh both.
3. **The organisation's Claude plan.**
   - D needs Team or Enterprise.
   - For B (if chosen) several points are unverified end to end:
     - Claude reading Authentik's per-provider OIDC discovery when it sits in the first `authorization_servers` entry;
     - whether it uses RFC 8414 path insertion;
     - Anthropic's servers reaching Authentik's token endpoint through any WAF.
   - Also unconfirmed: whether Authentik policies can make DCR anonymous, and what the created applications inherit.
4. **Smaller unknowns:**
   - Whether `https://claude.com/api/mcp/auth_callback` is ever used. The current docs list only claude.ai.
   - Whether claude.ai sends `resource` in the normal (non-enterprise) flow. Inferred from the spec; KINA accepts either way.
   - Authentik's Google domain restriction in this organisation's instance. Ask the Authentik admin.
