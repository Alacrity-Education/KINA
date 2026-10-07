package ro.alacrity.kina.oauth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code oauth_clients}: dynamically registered OAuth clients (RFC 7591), secrets stored as SHA-256 hex, and clients
 * identified by a Client ID Metadata Document ({@code metadata_url} set; refreshed from the document).
 */
@Repository
public class OAuthClientRepository {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final String COLUMNS = """
            client_id, client_secret_hash, client_name, redirect_uris::text AS redirect_uris,
            grant_types::text AS grant_types, response_types::text AS response_types,
            token_endpoint_auth_method, scope, metadata::text AS metadata, created_at, metadata_url""";

    @Autowired private JdbcClient jdbc;

    /**
     * An OAuth client. {@code metadataUrl} is set (and equals {@code clientId}) for clients identified by a Client ID
     * Metadata Document.
     */
    public record OAuthClient(String clientId, String clientSecretHash, String clientName, List<String> redirectUris,
                              List<String> grantTypes, List<String> responseTypes, String tokenEndpointAuthMethod,
                              String scope, Map<String, Object> metadata, Instant createdAt, String metadataUrl) {

        /** A dynamically registered client (no metadata document). */
        public OAuthClient(String clientId, String clientSecretHash, String clientName, List<String> redirectUris,
                           List<String> grantTypes, List<String> responseTypes, String tokenEndpointAuthMethod,
                           String scope, Map<String, Object> metadata, Instant createdAt) {
            this(clientId, clientSecretHash, clientName, redirectUris, grantTypes, responseTypes,
                    tokenEndpointAuthMethod, scope, metadata, createdAt, null);
        }

        public boolean isPublic() {
            return ClientRegistrationController.AUTH_NONE.equals(tokenEndpointAuthMethod);
        }

        public boolean isMetadataDocumentClient() {
            return metadataUrl != null;
        }

        /** Name for display and for access token names; falls back to the client id. */
        public String displayName() {
            return clientName == null || clientName.isBlank() ? clientId : clientName;
        }
    }

    public void insert(OAuthClient client) {
        jdbc.sql("""
                        INSERT INTO oauth_clients (client_id, client_secret_hash, client_name, redirect_uris, grant_types,
                                                   response_types, token_endpoint_auth_method, scope, metadata, created_at,
                                                   metadata_url)
                        VALUES (:clientId, :secretHash, :clientName, :redirectUris::jsonb, :grantTypes::jsonb,
                                :responseTypes::jsonb, :authMethod, :scope, :metadata::jsonb, :createdAt, :metadataUrl)""")
                .params(params(client))
                .update();
    }

    /**
     * Inserts or refreshes a metadata-document client (the document may change). Rows are keyed by the document URL,
     * so they never collide with registered clients (whose ids are random base64url strings).
     */
    public void upsertMetadataDocumentClient(OAuthClient client) {
        jdbc.sql("""
                        INSERT INTO oauth_clients (client_id, client_secret_hash, client_name, redirect_uris, grant_types,
                                                   response_types, token_endpoint_auth_method, scope, metadata, created_at,
                                                   metadata_url)
                        VALUES (:clientId, NULL, :clientName, :redirectUris::jsonb, :grantTypes::jsonb,
                                :responseTypes::jsonb, :authMethod, :scope, :metadata::jsonb, :createdAt, :metadataUrl)
                        ON CONFLICT (client_id) DO UPDATE SET
                          client_secret_hash = NULL,
                          client_name = EXCLUDED.client_name,
                          redirect_uris = EXCLUDED.redirect_uris,
                          grant_types = EXCLUDED.grant_types,
                          response_types = EXCLUDED.response_types,
                          token_endpoint_auth_method = EXCLUDED.token_endpoint_auth_method,
                          scope = EXCLUDED.scope,
                          metadata = EXCLUDED.metadata,
                          metadata_url = EXCLUDED.metadata_url""")
                .params(params(client))
                .update();
    }

    public Optional<OAuthClient> findById(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM oauth_clients WHERE client_id = ?")
                .param(clientId)
                .query(OAuthClientRepository::map)
                .optional();
    }

    /** Records a token issuance (registration hygiene). */
    public void touchLastUsed(String clientId, Instant now) {
        jdbc.sql("UPDATE oauth_clients SET last_used_at = ? WHERE client_id = ?")
                .param(Timestamp.from(now))
                .param(clientId)
                .update();
    }

    /**
     * Deletes dynamically registered clients (no metadata document) that issued no token since {@code unusedSince}
     * (or were never used and registered before it) and have no live access or refresh token. Codes and refresh tokens
     * of deleted clients go with them ({@code ON DELETE CASCADE}). Returns the number of deleted clients.
     */
    public int deleteUnusedRegisteredClients(Instant unusedSince, Instant now) {
        return jdbc.sql("""
                        DELETE FROM oauth_clients c
                        WHERE c.metadata_url IS NULL
                          AND COALESCE(c.last_used_at, c.created_at) < :unusedSince
                          AND NOT EXISTS (SELECT 1 FROM access_tokens a
                                          WHERE a.oauth_client_id = c.client_id AND a.revoked_at IS NULL
                                            AND a.expires_at > :now)
                          AND NOT EXISTS (SELECT 1 FROM oauth_refresh_tokens r
                                          WHERE r.client_id = c.client_id AND r.revoked_at IS NULL
                                            AND r.expires_at > :now)""")
                .param("unusedSince", Timestamp.from(unusedSince))
                .param("now", Timestamp.from(now))
                .update();
    }

    private static Map<String, Object> params(OAuthClient client) {
        Map<String, Object> params = new HashMap<>();
        params.put("clientId", client.clientId());
        params.put("secretHash", client.clientSecretHash());
        params.put("clientName", client.clientName());
        params.put("redirectUris", JSON.writeValueAsString(client.redirectUris()));
        params.put("grantTypes", JSON.writeValueAsString(client.grantTypes()));
        params.put("responseTypes", JSON.writeValueAsString(client.responseTypes()));
        params.put("authMethod", client.tokenEndpointAuthMethod());
        params.put("scope", client.scope());
        params.put("metadata", JSON.writeValueAsString(client.metadata() == null ? Map.of() : client.metadata()));
        params.put("createdAt", Timestamp.from(client.createdAt()));
        params.put("metadataUrl", client.metadataUrl());
        return params;
    }

    private static OAuthClient map(ResultSet rs, int rowNum) throws SQLException {
        return new OAuthClient(
                rs.getString("client_id"),
                rs.getString("client_secret_hash"),
                rs.getString("client_name"),
                JSON.readValue(rs.getString("redirect_uris"), STRING_LIST),
                JSON.readValue(rs.getString("grant_types"), STRING_LIST),
                JSON.readValue(rs.getString("response_types"), STRING_LIST),
                rs.getString("token_endpoint_auth_method"),
                rs.getString("scope"),
                JSON.readValue(rs.getString("metadata"), OBJECT_MAP),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("metadata_url"));
    }
}
