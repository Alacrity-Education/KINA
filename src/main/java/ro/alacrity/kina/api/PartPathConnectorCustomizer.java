package ro.alacrity.kina.api;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.stereotype.Component;

/**
 * Lets a percent-encoded slash ({@code %2F}) and backslash ({@code %5C}) reach the application undecoded
 * ({@code passthrough}) instead of Tomcat rejecting the request (DESIGN.md 5: the part lookup path
 * {@code /api/v1/parts/{distributor}/{*partNumber}}). Spring MVC matches on the raw request URI and decodes the path
 * variable; the request firewall ({@link ro.alacrity.kina.security.PartPathFirewall}) still rejects both encodings on
 * every other path.
 */
@Component
public class PartPathConnectorCustomizer implements TomcatConnectorCustomizer {

    @Override
    public void customize(Connector connector) {
        connector.setEncodedSolidusHandling("passthrough");
        connector.setEncodedReverseSolidusHandling("passthrough");
    }
}
