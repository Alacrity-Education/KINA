package ro.alacrity.kina.security;

import ro.alacrity.kina.config.KinaProperties;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Group and e-mail-domain authorisation of an OIDC identity (DESIGN.md 7.6). Provider-agnostic: the claim name,
 * the required groups and the allowed domains are configuration.
 * <ul>
 *   <li>The groups claim ({@code kina.security.oidc.groups-claim}, default {@code groups}) is read from the ID token
 *   first; when the ID token does not carry it, from userinfo. The name is first looked up literally (so namespaced
 *   claims such as {@code https://example.com/groups} work), then as a dotted path into nested objects
 *   ({@code realm_access.roles}).</li>
 *   <li>The claim may be an array of strings or a single string. A group matches a required group when they are equal
 *   ignoring case; a leading {@code /} (path-style group names) is ignored.</li>
 *   <li>The user must be in at least one required group. No required groups: no group check.</li>
 *   <li>Allowed e-mail domains (optional): the {@code email} claim must exist, must not be marked unverified
 *   ({@code email_verified=false}) and its domain must equal one of them (case-insensitive).</li>
 * </ul>
 */
public final class OidcAccessPolicy {

    /** OAuth2 error code of a login rejected for missing group membership. */
    public static final String ERROR_GROUP = "kina_group_membership_required";
    /** OAuth2 error code of a login rejected for its e-mail domain. */
    public static final String ERROR_EMAIL_DOMAIN = "kina_email_domain_not_allowed";

    private final String groupsClaim;
    private final List<String> requiredGroups;
    private final List<String> allowedEmailDomains;

    public OidcAccessPolicy(KinaProperties.Oidc oidc) {
        this(oidc.groupsClaim(), oidc.requiredGroups(), oidc.allowedEmailDomains());
    }

    public OidcAccessPolicy(String groupsClaim, List<String> requiredGroups, List<String> allowedEmailDomains) {
        this.groupsClaim = groupsClaim == null || groupsClaim.isBlank() ? "groups" : groupsClaim;
        this.requiredGroups = requiredGroups == null ? List.of() : List.copyOf(requiredGroups);
        this.allowedEmailDomains = allowedEmailDomains == null ? List.of()
                : allowedEmailDomains.stream().map(d -> stripAt(d).toLowerCase(Locale.ROOT)).toList();
    }

    /** Outcome of an evaluation; {@code errorCode} is null when allowed. */
    public record Decision(boolean allowed, String errorCode, String reason) {

        static final Decision ALLOW = new Decision(true, null, null);

        static Decision deny(String errorCode, String reason) {
            return new Decision(false, errorCode, reason);
        }
    }

    public boolean checksGroups() {
        return !requiredGroups.isEmpty();
    }

    public List<String> requiredGroups() {
        return requiredGroups;
    }

    public List<String> allowedEmailDomains() {
        return allowedEmailDomains;
    }

    /** Full login check: e-mail domain, then groups. {@code userInfoClaims} may be null. */
    public Decision evaluateLogin(Map<String, Object> idTokenClaims, Map<String, Object> userInfoClaims) {
        if (!allowedEmailDomains.isEmpty()) {
            Object email = claim(idTokenClaims, userInfoClaims, "email").orElse(null);
            Object verified = claim(idTokenClaims, userInfoClaims, "email_verified").orElse(null);
            if (!(email instanceof String address) || !emailDomainAllowed(address)) {
                return Decision.deny(ERROR_EMAIL_DOMAIN, "e-mail domain not allowed");
            }
            if (Boolean.FALSE.equals(verified) || "false".equals(verified)) {
                return Decision.deny(ERROR_EMAIL_DOMAIN, "e-mail address not verified");
            }
        }
        return evaluateGroups(idTokenClaims, userInfoClaims);
    }

    /** Group check only (membership re-checks). {@code userInfoClaims} may be null. */
    public Decision evaluateGroups(Map<String, Object> idTokenClaims, Map<String, Object> userInfoClaims) {
        if (requiredGroups.isEmpty()) {
            return Decision.ALLOW;
        }
        Optional<List<String>> groups = groups(idTokenClaims, userInfoClaims);
        if (groups.isEmpty()) {
            return Decision.deny(ERROR_GROUP, "groups claim '" + groupsClaim + "' missing");
        }
        return isMember(groups.get()) ? Decision.ALLOW : Decision.deny(ERROR_GROUP, "not in a required group");
    }

    /** The groups from the ID token claim, or, when the ID token lacks the claim, from userinfo. */
    public Optional<List<String>> groups(Map<String, Object> idTokenClaims, Map<String, Object> userInfoClaims) {
        Optional<List<String>> fromIdToken = extractGroups(idTokenClaims, groupsClaim);
        return fromIdToken.isPresent() ? fromIdToken : extractGroups(userInfoClaims, groupsClaim);
    }

    /** True when the ID token carries the groups claim (no userinfo call needed). */
    public boolean hasGroupsClaim(Map<String, Object> claims) {
        return extractGroups(claims, groupsClaim).isPresent();
    }

    boolean isMember(Collection<String> groups) {
        for (String group : groups) {
            String normalized = normalizeGroup(group);
            for (String required : requiredGroups) {
                if (normalized.equalsIgnoreCase(normalizeGroup(required))) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean emailDomainAllowed(String email) {
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        String domain = email.substring(at + 1).strip().toLowerCase(Locale.ROOT);
        return allowedEmailDomains.contains(domain);
    }

    /**
     * The claim {@code name} as a list of strings: looked up literally, then as a dotted path. A string becomes a
     * one-element list; non-string array elements are ignored. Empty when absent or of another type.
     */
    static Optional<List<String>> extractGroups(Map<String, Object> claims, String name) {
        if (claims == null) {
            return Optional.empty();
        }
        Object value = claims.containsKey(name) ? claims.get(name) : path(claims, name);
        if (value instanceof String single) {
            return Optional.of(single.isBlank() ? List.of() : List.of(single));
        }
        if (value instanceof Collection<?> collection) {
            List<String> result = new ArrayList<>();
            for (Object element : collection) {
                if (element instanceof String s && !s.isBlank()) {
                    result.add(s);
                }
            }
            return Optional.of(List.copyOf(result));
        }
        if (value instanceof Object[] array) {
            return extractGroups(Map.of(name, List.of(array)), name);
        }
        return Optional.empty();
    }

    private static Object path(Map<String, Object> claims, String dotted) {
        if (!dotted.contains(".")) {
            return null;
        }
        Object current = claims;
        for (String part : dotted.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static Optional<Object> claim(Map<String, Object> idToken, Map<String, Object> userInfo, String name) {
        if (idToken != null && idToken.get(name) != null) {
            return Optional.of(idToken.get(name));
        }
        if (userInfo != null && userInfo.get(name) != null) {
            return Optional.of(userInfo.get(name));
        }
        return Optional.empty();
    }

    private static String normalizeGroup(String group) {
        String trimmed = group.strip();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    private static String stripAt(String domain) {
        String trimmed = domain.strip();
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }
}
