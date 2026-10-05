package ro.alacrity.kina.oauth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code oauth_clients}: dynamically registered OAuth clients (RFC 7591). Secrets are stored as SHA-256 hex. */
@Repository
public class OAuthClientRepository {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;

    public OAuthClientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record OAuthClient(String clientId, String clientSecretHash, String clientName, List<String> redirectUris,
                              List<String> grantTypes, List<String> responseTypes, String tokenEndpointAuthMethod,
                              String scope, Map<String, Object> metadata, Instant createdAt) {

        public boolean isPublic() {
            return ClientRegistrationController.AUTH_NONE.equals(tokenEndpointAuthMethod);
        }

        /** Name for display and for access token names; falls back to the client id. */
        public String displayName() {
            return clientName == null || clientName.isBlank() ? clientId : clientName;
        }
    }

    public void insert(OAuthClient client) {
        jdbc.sql("""
                        INSERT INTO oauth_clients (client_id, client_secret_hash, client_name, redirect_uris, grant_types,
                                                   response_types, token_endpoint_auth_method, scope, metadata, created_at)
                        VALUES (:clientId, :secretHash, :clientName, :redirectUris::jsonb, :grantTypes::jsonb,
                                :responseTypes::jsonb, :authMethod, :scope, :metadata::jsonb, :createdAt)""")
                .param("clientId", client.clientId())
                .param("secretHash", client.clientSecretHash())
                .param("clientName", client.clientName())
                .param("redirectUris", JSON.writeValueAsString(client.redirectUris()))
                .param("grantTypes", JSON.writeValueAsString(client.grantTypes()))
                .param("responseTypes", JSON.writeValueAsString(client.responseTypes()))
                .param("authMethod", client.tokenEndpointAuthMethod())
                .param("scope", client.scope())
                .param("metadata", JSON.writeValueAsString(client.metadata() == null ? Map.of() : client.metadata()))
                .param("createdAt", Timestamp.from(client.createdAt()))
                .update();
    }

    public Optional<OAuthClient> findById(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT client_id, client_secret_hash, client_name, redirect_uris::text AS redirect_uris,
                               grant_types::text AS grant_types, response_types::text AS response_types,
                               token_endpoint_auth_method, scope, metadata::text AS metadata, created_at
                        FROM oauth_clients WHERE client_id = ?""")
                .param(clientId)
                .query(OAuthClientRepository::map)
                .optional();
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
                rs.getTimestamp("created_at").toInstant());
    }
}
