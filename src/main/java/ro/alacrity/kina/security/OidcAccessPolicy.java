package ro.alacrity.kina.security;

import ro.alacrity.kina.config.KinaProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Group and e-mail-domain authorisation of an OIDC identity (DESIGN.md 7.1). Provider-agnostic: the claim name,
 * the required groups and the allowed domains are configuration.
 * <ul>
 *   <li>The groups claim ({@code kina.security.oidc.groups-claim}, default {@code groups}) is read from the ID token
 *   first; when the ID token does not carry it, from userinfo. The name is first looked up literally (so namespaced
 *   claims such as {@code https://example.com/groups} work), then as a dotted path into nested objects
 *   ({@code realm_access.roles}).</li>
 *   <li>The claim may be an array of strings or a single string. A group matches a required group when they are equal
 *   ignoring case; a leading {@code /} (path-style group names) is ignored.</li>
 *   <li>The user must be in at least one required group. No required groups: no group check.</li>
 *   <li>Allowed e-mail domains (optional): an e-mail address must be present ({@link Reason#EMAIL_MISSING}), its domain
 *   must equal one of them, case-insensitively ({@link Reason#EMAIL_DOMAIN}), and it must not be marked unverified
 *   ({@code email_verified} {@code false} or {@code "false"}, {@link Reason#EMAIL_UNVERIFIED}). The address is the
 *   {@code email} claim (ID token, then userinfo); with {@code email-from-preferred-username} also
 *   {@code preferred_username} or {@code upn} when they contain {@code @}.</li>
 * </ul>
 * Decisions never carry the address itself, only the domain, so they are safe to log.
 */
public final class OidcAccessPolicy {

    /** OAuth2 error code of a login rejected for missing group membership. */
    public static final String ERROR_GROUP = "kina_group_membership_required";
    /** OAuth2 error code of a login rejected for its e-mail address (missing, unverified or another domain). */
    public static final String ERROR_EMAIL_DOMAIN = "kina_email_domain_not_allowed";

    /** Claims that may carry the address when {@code email-from-preferred-username} is on, in this order. */
    private static final List<String> FALLBACK_EMAIL_CLAIMS = List.of("preferred_username", "upn");

    private final String groupsClaim;
    private final List<String> requiredGroups;
    private final List<String> allowedEmailDomains;
    private final boolean emailFromPreferredUsername;

    public OidcAccessPolicy(KinaProperties.Oidc oidc) {
        this(oidc.groupsClaim(), oidc.requiredGroups(), oidc.allowedEmailDomains(), oidc.emailFromPreferredUsername());
    }

    public OidcAccessPolicy(String groupsClaim, List<String> requiredGroups, List<String> allowedEmailDomains) {
        this(groupsClaim, requiredGroups, allowedEmailDomains, false);
    }

    public OidcAccessPolicy(String groupsClaim, List<String> requiredGroups, List<String> allowedEmailDomains,
                            boolean emailFromPreferredUsername) {
        this.groupsClaim = groupsClaim == null || groupsClaim.isBlank() ? "groups" : groupsClaim;
        this.requiredGroups = requiredGroups == null ? List.of() : List.copyOf(requiredGroups);
        this.allowedEmailDomains = allowedEmailDomains == null ? List.of()
                : allowedEmailDomains.stream().map(d -> stripAt(d).toLowerCase(Locale.ROOT)).toList();
        this.emailFromPreferredUsername = emailFromPreferredUsername;
    }

    /** Why a login was refused. {@link #code()} is the machine-readable form (the {@code /login-denied} reason). */
    public enum Reason {
        /** No usable e-mail address in the ID token or userinfo. */
        EMAIL_MISSING("email_missing", ERROR_EMAIL_DOMAIN),
        /** The provider marks the address as unverified. */
        EMAIL_UNVERIFIED("email_unverified", ERROR_EMAIL_DOMAIN),
        /** The address belongs to a domain that is not allowed. */
        EMAIL_DOMAIN("email_domain", ERROR_EMAIL_DOMAIN),
        /** Not in a required group (or no groups claim). */
        GROUP("group", ERROR_GROUP);

        private final String code;
        private final String errorCode;

        Reason(String code, String errorCode) {
            this.code = code;
            this.errorCode = errorCode;
        }

        public String code() {
            return code;
        }

        /** The OAuth2 error code ({@link #ERROR_GROUP} or {@link #ERROR_EMAIL_DOMAIN}). */
        public String errorCode() {
            return errorCode;
        }

        /** The reason with this code, or null. */
        public static Reason fromCode(String code) {
            for (Reason reason : values()) {
                if (reason.code.equals(code)) {
                    return reason;
                }
            }
            return null;
        }
    }

    /**
     * Outcome of an evaluation. When refused: {@code reason}, the OAuth2 {@code errorCode}, the offending
     * {@code domain} (lower case, {@link Reason#EMAIL_DOMAIN} only) and a log-safe {@code detail} (claim names and
     * domains, never addresses or other claim values). All null when allowed.
     */
    public record Decision(boolean allowed, String errorCode, Reason reason, String domain, String detail) {

        static final Decision ALLOW = new Decision(true, null, null, null, null);

        static Decision deny(Reason reason, String detail) {
            return deny(reason, null, detail);
        }

        static Decision deny(Reason reason, String domain, String detail) {
            return new Decision(false, reason.errorCode(), reason, domain, detail);
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

    public boolean emailFromPreferredUsername() {
        return emailFromPreferredUsername;
    }

    /** The effective policy in one line, for the startup log (no secrets). */
    public String describe() {
        return "groups claim '" + groupsClaim + "', required groups "
                + (requiredGroups.isEmpty() ? "none (no group check)" : requiredGroups) + ", allowed e-mail domains "
                + (allowedEmailDomains.isEmpty() ? "any" : allowedEmailDomains)
                + ", e-mail from preferred_username/upn " + (emailFromPreferredUsername ? "on" : "off");
    }

    /** Full login check: e-mail address (present, domain, verified), then groups. {@code userInfoClaims} may be null. */
    public Decision evaluateLogin(Map<String, Object> idTokenClaims, Map<String, Object> userInfoClaims) {
        if (!allowedEmailDomains.isEmpty()) {
            Optional<String> address = email(idTokenClaims, userInfoClaims);
            if (address.isEmpty()) {
                return Decision.deny(Reason.EMAIL_MISSING, "no e-mail claim in ID token or userinfo (claims present: "
                        + String.join(", ", claimNames(idTokenClaims, userInfoClaims)) + ")"
                        + (emailFromPreferredUsername ? "" : "; e-mail from preferred_username/upn is off"));
            }
            String domain = domainOf(address.get());
            if (domain.isEmpty()) {
                return Decision.deny(Reason.EMAIL_MISSING, "e-mail claim has no domain part");
            }
            if (!allowedEmailDomains.contains(domain)) {
                return Decision.deny(Reason.EMAIL_DOMAIN, domain, "e-mail domain " + domain + " not allowed");
            }
            Object verified = claim(idTokenClaims, userInfoClaims, "email_verified").orElse(null);
            if (Boolean.FALSE.equals(verified)
                    || verified instanceof String text && "false".equalsIgnoreCase(text.strip())) {
                return Decision.deny(Reason.EMAIL_UNVERIFIED, "e-mail address not verified (email_verified=false)");
            }
        }
        return evaluateGroups(idTokenClaims, userInfoClaims);
    }

    /**
     * The user's e-mail address: the {@code email} claim (ID token, then userinfo); when absent and the fallback is on,
     * {@code preferred_username}, then {@code upn}, if it contains {@code @}. Blank and non-string values are ignored.
     */
    public Optional<String> email(Map<String, Object> idTokenClaims, Map<String, Object> userInfoClaims) {
        Optional<String> email = stringClaim(idTokenClaims, userInfoClaims, "email");
        if (email.isPresent() || !emailFromPreferredUsername) {
            return email;
        }
        for (String name : FALLBACK_EMAIL_CLAIMS) {
            Optional<String> candidate = stringClaim(idTokenClaims, userInfoClaims, name).filter(v -> v.contains("@"));
            if (candidate.isPresent()) {
                return candidate;
            }
        }
        return Optional.empty();
    }

    /** Sorted claim names (never values) of the ID token and userinfo together. */
    static List<String> claimNames(Map<String, Object> idTokenClaims, Map<String, Object> userInfoClaims) {
        Set<String> names = new TreeSet<>();
        if (idTokenClaims != null) {
            names.addAll(idTokenClaims.keySet());
        }
        if (userInfoClaims != null) {
            names.addAll(userInfoClaims.keySet());
        }
        return List.copyOf(names);
    }

    /** Group check only (membership re-checks). {@code userInfoClaims} may be null. */
    public Decision evaluateGroups(Map<String, Object> idTokenClaims, Map<String, Object> userInfoClaims) {
        if (requiredGroups.isEmpty()) {
            return Decision.ALLOW;
        }
        Optional<List<String>> groups = groups(idTokenClaims, userInfoClaims);
        if (groups.isEmpty()) {
            return Decision.deny(Reason.GROUP, "groups claim '" + groupsClaim + "' missing in ID token and userinfo");
        }
        return isMember(groups.get()) ? Decision.ALLOW
                : Decision.deny(Reason.GROUP, "not in a required group " + requiredGroups);
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
        String domain = domainOf(email);
        return !domain.isEmpty() && allowedEmailDomains.contains(domain);
    }

    /** The lower-case domain of an address, or an empty string when it has none. */
    static String domainOf(String email) {
        int at = email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1).strip().toLowerCase(Locale.ROOT);
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

    private static Optional<String> stringClaim(Map<String, Object> idToken, Map<String, Object> userInfo,
                                                String name) {
        for (Map<String, Object> claims : Arrays.asList(idToken, userInfo)) {
            if (claims != null && claims.get(name) instanceof String value && !value.isBlank()) {
                return Optional.of(value.strip());
            }
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
