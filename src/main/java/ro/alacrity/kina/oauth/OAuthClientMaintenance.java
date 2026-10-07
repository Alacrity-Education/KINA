package ro.alacrity.kina.oauth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Registration hygiene: every day, deletes dynamically registered clients that issued no token for
 * {@code kina.oauth.unused-client-retention} (default 90 days) and have no live access or refresh token. Claude
 * registers a new client on every fresh connection, so without this {@code oauth_clients} only grows. Clients
 * identified by a Client ID Metadata Document are not touched (they are re-created from their document on demand).
 */
@Component
@Slf4j
public class OAuthClientMaintenance {

    @Autowired private OAuthClientRepository clients;
    @Autowired private KinaProperties properties;
    private final Clock clock = Clock.systemUTC();

    @Scheduled(initialDelayString = "PT15M", fixedDelayString = "P1D")
    public void scheduledCleanup() {
        try {
            cleanup();
        } catch (RuntimeException e) {
            log.warn("Cleanup of unused OAuth clients failed: {}", e.toString());
        }
    }

    /** Deletes unused registered clients; returns how many. */
    public int cleanup() {
        Instant now = clock.instant();
        Duration retention = properties.oauth().unusedClientRetention();
        int deleted = clients.deleteUnusedRegisteredClients(now.minus(retention), now);
        if (deleted > 0) {
            log.info("Deleted {} dynamically registered OAuth clients unused for {}", deleted, retention);
        }
        return deleted;
    }
}
