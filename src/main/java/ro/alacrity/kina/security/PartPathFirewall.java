package ro.alacrity.kina.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.firewall.FirewalledRequest;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;

/**
 * The request firewall (DESIGN.md 5 and 6): Spring Security's {@link StrictHttpFirewall} for every request, except the
 * part lookup {@code GET /api/v1/parts/{distributor}/{*partNumber}}, whose last segments are a distributor part number
 * that may hold {@code %}, a backslash or a slash ({@code RC0603FR-07100%}, TME symbols such as
 * {@code DTMSS-20/0.010/20V}; 77 cached parts in production): there a percent-encoded {@code %25}, {@code %5C} and
 * {@code %2F} are accepted. Everything else stays strict, including on that path: a path that is not normalised after
 * decoding ({@code %2F..%2F}), a null byte, a semicolon and non-printable characters are still rejected.
 */
public class PartPathFirewall implements HttpFirewall {

    /** The prefix of the part lookup path (raw request URI). */
    static final String PART_PATH = "/api/v1/parts/";

    private final StrictHttpFirewall strict = new StrictHttpFirewall();
    private final StrictHttpFirewall partPath = new StrictHttpFirewall();

    public PartPathFirewall() {
        partPath.setAllowUrlEncodedPercent(true);
        partPath.setAllowUrlEncodedSlash(true);
        partPath.setAllowBackSlash(true);
    }

    @Override
    public FirewalledRequest getFirewalledRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        boolean lookup = "GET".equals(request.getMethod()) && uri != null && uri.startsWith(PART_PATH);
        return (lookup ? partPath : strict).getFirewalledRequest(request);
    }

    @Override
    public HttpServletResponse getFirewalledResponse(HttpServletResponse response) {
        return strict.getFirewalledResponse(response);
    }
}
