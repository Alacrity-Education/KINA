package ro.alacrity.kina.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.oauth.ClientMetadataDocumentResolver.UnresolvableClientException;
import ro.alacrity.kina.oauth.OAuthClientRepository.OAuthClient;

import java.util.Optional;

/**
 * Finds the OAuth client for a {@code client_id}: an {@code https} URL is a Client ID Metadata Document client
 * ({@link ClientMetadataDocumentResolver}), anything else a dynamically registered client.
 */
@Component
@RequiredArgsConstructor
public class OAuthClientLookup {

    private final OAuthClientRepository clients;
    private final ClientMetadataDocumentResolver metadataDocuments;

    /** A client that cannot be used; {@code getMessage()} is safe to show. */
    public static final class UnknownClientException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnknownClientException(String message) {
            super(message);
        }
    }

    /** The client, or {@link UnknownClientException} explaining why there is none. */
    public OAuthClient find(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new UnknownClientException("The request has no client_id.");
        }
        if (metadataDocuments.handles(clientId)) {
            try {
                return metadataDocuments.resolve(clientId);
            } catch (UnresolvableClientException e) {
                throw new UnknownClientException(e.getMessage());
            }
        }
        return clients.findById(clientId)
                .orElseThrow(() -> new UnknownClientException("Unknown client. Register the client first."));
    }

    public Optional<OAuthClient> findOptional(String clientId) {
        try {
            return Optional.of(find(clientId));
        } catch (UnknownClientException e) {
            return Optional.empty();
        }
    }
}
