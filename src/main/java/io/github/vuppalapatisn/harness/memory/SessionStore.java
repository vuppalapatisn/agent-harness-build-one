package io.github.vuppalapatisn.harness.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vuppalapatisn.harness.model.ChatMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkpointed session memory keyed by session id (the article's {@code thread_id}). Append-only:
 * messages are never rewritten, which keeps prompt caching and Claude's thinking replay valid.
 * H2 by default; point {@code spring.datasource.*} at Postgres for production.
 */
@Repository
public class SessionStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SessionStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public String create(String userId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO harness_session (id, user_id, created_at) VALUES (?, ?, ?)",
                id, userId, Timestamp.from(Instant.now(clock)));
        return id;
    }

    /** Throws {@link SessionNotFoundException} if the session does not exist or belongs to another user. */
    public void requireOwner(String sessionId, String userId) {
        List<String> owners = jdbc.queryForList("SELECT user_id FROM harness_session WHERE id = ?", String.class, sessionId);
        if (owners.isEmpty() || !owners.get(0).equals(userId)) {
            throw new SessionNotFoundException(sessionId);
        }
    }

    public List<ChatMessage> load(String sessionId) {
        return jdbc.query("SELECT payload FROM harness_message WHERE session_id = ? ORDER BY seq",
                (rs, i) -> read(rs.getString(1)), sessionId);
    }

    @Transactional
    public void append(String sessionId, List<ChatMessage> messages) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM harness_message WHERE session_id = ?", Integer.class, sessionId);
        int seq = next == null ? 1 : next;
        Timestamp now = Timestamp.from(Instant.now(clock));
        for (ChatMessage m : messages) {
            jdbc.update("INSERT INTO harness_message (session_id, seq, role, payload, created_at) VALUES (?, ?, ?, ?, ?)",
                    sessionId, seq++, m.role().name(), write(m), now);
        }
    }

    private ChatMessage read(String json) {
        try {
            return mapper.readValue(json, ChatMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt session message", e);
        }
    }

    private String write(ChatMessage message) {
        try {
            return mapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize session message", e);
        }
    }
}
