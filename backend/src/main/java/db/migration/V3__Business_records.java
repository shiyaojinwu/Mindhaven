package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * Copies scoped records without deleting the original migration backup.
 */
public class V3__Business_records extends BaseJavaMigration {
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public Integer getChecksum() {
        return 1;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE chat_session (
                        row_id VARCHAR(40) PRIMARY KEY,
                        tenant_id VARCHAR(40) NOT NULL,
                        owner_id VARCHAR(40) NOT NULL,
                        id VARCHAR(100) NOT NULL,
                        title TEXT NOT NULL,
                        created_at VARCHAR(40) NOT NULL,
                        UNIQUE(tenant_id, owner_id, id),
                        FOREIGN KEY(tenant_id) REFERENCES tenants(id)
                    )
                    """);
            statement.execute("CREATE INDEX idx_chat_session_list ON chat_session(tenant_id, owner_id, created_at, id)");
            statement.execute("""
                    CREATE TABLE chat_message (
                        row_id VARCHAR(40) PRIMARY KEY,
                        tenant_id VARCHAR(40) NOT NULL,
                        owner_id VARCHAR(40) NOT NULL,
                        id VARCHAR(100) NOT NULL,
                        session_id VARCHAR(100) NOT NULL,
                        seq BIGINT NOT NULL CHECK(seq > 0),
                        role VARCHAR(20) NOT NULL,
                        content TEXT NOT NULL,
                        created_at VARCHAR(40) NOT NULL,
                        citations_json TEXT NOT NULL,
                        status VARCHAR(20) NOT NULL,
                        citation_check_json TEXT,
                        UNIQUE(tenant_id, owner_id, id),
                        UNIQUE(tenant_id, owner_id, session_id, seq),
                        FOREIGN KEY(tenant_id, owner_id, session_id) REFERENCES chat_session(tenant_id, owner_id, id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE knowledge_chunk (
                        row_id VARCHAR(40) PRIMARY KEY,
                        tenant_id VARCHAR(40) NOT NULL,
                        id VARCHAR(100) NOT NULL,
                        title TEXT NOT NULL,
                        topic VARCHAR(120) NOT NULL,
                        version VARCHAR(100) NOT NULL,
                        source_url TEXT,
                        text TEXT NOT NULL,
                        created_at VARCHAR(40) NOT NULL,
                        UNIQUE(tenant_id, id),
                        FOREIGN KEY(tenant_id) REFERENCES tenants(id)
                    )
                    """);
            statement.execute("CREATE INDEX idx_knowledge_scope ON knowledge_chunk(tenant_id, version, topic)");
            statement.execute("CREATE INDEX idx_knowledge_list ON knowledge_chunk(tenant_id, created_at, id)");
        }
        copy(connection, "sessions");
        copy(connection, "knowledge");
        copy(connection, "messages");
    }

    private void copy(Connection connection, String kind) throws Exception {
        String filter = kind.equals("messages") ? "bucket LIKE 'messages:%'" : "bucket='" + kind + "'";
        try (var select = connection.prepareStatement("SELECT tenant_id, owner_id, bucket, id, payload, created_at FROM tenant_records WHERE " + filter); var rows = select.executeQuery()) {
            while (rows.next()) {
                JsonNode value = json.readTree(rows.getString("payload"));
                String id = rows.getString("id");
                if (!id.equals(required(value, "id"))) throw new IllegalStateException("Legacy record ID mismatch");
                String tenant = rows.getString("tenant_id"), owner = rows.getString("owner_id");
                if (kind.equals("sessions")) {
                    insert(connection, "INSERT INTO chat_session VALUES(?,?,?,?,?,?)", UUID.randomUUID().toString(), tenant, owner, id, required(value, "title"), required(value, "createdAt"));
                } else if (kind.equals("knowledge")) {
                    if (!owner.isEmpty()) throw new IllegalStateException("Knowledge must be tenant shared");
                    insert(connection, "INSERT INTO knowledge_chunk VALUES(?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), tenant, id, required(value, "title"), required(value, "topic"), required(value, "version"), value.path("sourceUrl").isMissingNode() || value.path("sourceUrl").isNull() ? null : value.get("sourceUrl").asText(), required(value, "text"), rows.getString("created_at"));
                } else {
                    String session = required(value, "sessionId");
                    if (!rows.getString("bucket").equals("messages:" + session))
                        throw new IllegalStateException("Legacy message session mismatch");
                    if (!value.path("seq").isIntegralNumber())
                        throw new IllegalStateException("Missing message sequence");
                    JsonNode citations = value.path("citations");
                    if (!citations.isArray()) throw new IllegalStateException("Invalid message citations");
                    JsonNode check = value.get("citationCheck");
                    insert(connection, "INSERT INTO chat_message VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), tenant, owner, id, session, value.get("seq").longValue(), required(value, "role"), required(value, "content"), required(value, "createdAt"), citations.toString(), required(value, "status"), check == null || check.isNull() ? null : check.toString());
                }
            }
        }
    }

    private String required(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual()) throw new IllegalStateException("Invalid legacy field: " + field);
        return node.textValue();
    }

    private void insert(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }
}
