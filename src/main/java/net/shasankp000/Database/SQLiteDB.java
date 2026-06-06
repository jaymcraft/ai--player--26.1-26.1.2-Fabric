package net.shasankp000.Database;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SQLiteDB {

    private static final Logger logger = LoggerFactory.getLogger("ai-player");

    private static final String DB_URL = "jdbc:sqlite:" +
            FabricLoader.getInstance().getGameDir().resolve("sqlite_databases/memory_agent.db").toAbsolutePath();

    public static void createDB() {
        File dbDir = FabricLoader.getInstance().getGameDir().resolve("sqlite_databases").toFile();
        if (!dbDir.exists() && dbDir.mkdirs()) {
            logger.info("✅ Database directory created: {}", dbDir);
        }

        // Configure SQLite to allow extensions
        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(true);

        try (Connection conn = DriverManager.getConnection(DB_URL, config.toProperties())) {
            // We bypass native extensions (sqlite-vec / sqlite-vss) because they require
            // system dependencies
            // (like libgomp) that are frequently missing on Pterodactyl/Crafty/Docker
            // containers.
            // Furthermore, the existing code queries 'cosine_distance' which is a custom
            // Java UDF,
            // so we must always register it globally.
            logger.info("✅ Registering global Java cosine_distance UDF");
            VectorExtensionHelper.registerCosineDistanceIfNeeded(conn);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON;");
                stmt.execute("PRAGMA journal_mode = WAL;");

                String createTable = """
                            CREATE TABLE IF NOT EXISTS memories (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                type TEXT NOT NULL,
                                timestamp TEXT DEFAULT CURRENT_TIMESTAMP,
                                prompt TEXT,
                                response TEXT,
                                embedding VECTOR
                            );
                        """;
                stmt.executeUpdate(createTable);
                logger.info("✅ Memory table created.");
            }
        } catch (SQLException e) {
            logger.error("❌ DB creation failed: SQLState={}, ErrorCode={}, Message={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public static void storeMemory(String type, String prompt, String response, List<Double> embedding) {
        String sql = """
                    INSERT INTO memories (type, prompt, response, embedding)
                    VALUES (?, ?, ?, ?);
                """;

        SQLiteConfig config = new SQLiteConfig();

        try (Connection conn = DriverManager.getConnection(DB_URL, config.toProperties());

                PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, type);
            pstmt.setString(2, prompt);
            pstmt.setString(3, response);
            pstmt.setString(4, vectorToLiteral(embedding));

            pstmt.executeUpdate();
            logger.info("📝 Memory stored with vector embedding.");
        } catch (SQLException e) {
            logger.error("❌ Failed to store memory: SQLState={}, ErrorCode={}, Message={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage());
        }
    }

    public static void storeMemory(String type, String prompt, String response) {
        storeMemory(type, prompt, response, List.of());
    }

    public static List<Memory> fetchRecentMemories(List<String> types, int limit) {
        List<Memory> results = new ArrayList<>();
        if (types == null || types.isEmpty() || limit <= 0) {
            return results;
        }

        String placeholders = String.join(",", types.stream().map(type -> "?").toList());
        String sql = """
                    SELECT id, type, timestamp, prompt, response, 0.0 AS similarity
                    FROM memories
                    WHERE type IN (%s)
                    ORDER BY id DESC
                    LIMIT ?;
                """.formatted(placeholders);

        try (Connection conn = DriverManager.getConnection(DB_URL);
                PreparedStatement pstmt = conn.prepareStatement(sql)) {

            int index = 1;
            for (String type : types) {
                pstmt.setString(index++, type);
            }
            pstmt.setInt(index, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new Memory(
                            rs.getInt("id"),
                            rs.getString("type"),
                            rs.getString("timestamp"),
                            rs.getString("prompt"),
                            rs.getString("response"),
                            0.0));
                }
            }
        } catch (SQLException e) {
            logger.error("❌ Failed to fetch recent memories: SQLState={}, ErrorCode={}, Message={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage());
        }

        return results;
    }

    public static List<Memory> findRelevantMemories(List<Double> queryEmbedding, String typeFilter, int topK) {
        logger.info("Query embedding size: {}", queryEmbedding.size());

        List<Memory> results = new ArrayList<>();
        String sql = """
                    SELECT id, type, timestamp, prompt, response, embedding
                    FROM memories
                    WHERE type = ?
                """;

        SQLiteConfig config = new SQLiteConfig();

        try (Connection conn = DriverManager.getConnection(DB_URL, config.toProperties())) {

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, typeFilter);

                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    List<Double> storedEmbedding = vectorFromLiteral(rs.getString("embedding"));
                    double similarity = cosineSimilarity(queryEmbedding, storedEmbedding);
                    results.add(new Memory(
                            rs.getInt("id"),
                            rs.getString("type"),
                            rs.getString("timestamp"),
                            rs.getString("prompt"),
                            rs.getString("response"),
                            similarity));
                }
            }

        } catch (SQLException e) {
            logger.error("❌ Vector search failed: SQLState={}, ErrorCode={}, Message={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage());
        }

        results.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        if (topK > 0 && results.size() > topK) {
            return new ArrayList<>(results.subList(0, topK));
        }
        return results;
    }

    public static List<SQLiteDB.Memory> fetchInitialResponse() {
        List<SQLiteDB.Memory> results = new ArrayList<>();
        String sql = """
                    SELECT id, type, timestamp, prompt, response, 0.0 AS similarity
                    FROM memories
                    WHERE type = 'conversation'
                    ORDER BY id ASC
                    LIMIT 1;
                """;

        try (Connection conn = DriverManager.getConnection(DB_URL);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                results.add(new SQLiteDB.Memory(
                        rs.getInt("id"),
                        rs.getString("type"),
                        rs.getString("timestamp"),
                        rs.getString("prompt"),
                        rs.getString("response"),
                        0.0));
            }
        } catch (SQLException e) {
            logger.error("Caught exception while fetching initial response: {}", e.getMessage());
            throw new RuntimeException(e);
        }

        return results;
    }

    private static String vectorToLiteral(List<Double> vec) {
        if (vec == null || vec.isEmpty()) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.size(); i++) {
            sb.append(vec.get(i));
            if (i < vec.size() - 1)
                sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private static List<Double> vectorFromLiteral(String literal) {
        List<Double> vector = new ArrayList<>();
        if (literal == null || literal.isBlank()) {
            return vector;
        }

        String trimmed = literal.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return vector;
        }

        for (String part : trimmed.split(",")) {
            try {
                vector.add(Double.parseDouble(part.trim()));
            } catch (NumberFormatException e) {
                logger.warn("Skipping invalid vector value '{}'", part);
            }
        }
        return vector;
    }

    private static double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }

        int size = Math.min(a.size(), b.size());
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < size; i++) {
            double valueA = a.get(i);
            double valueB = b.get(i);
            dot += valueA * valueB;
            normA += valueA * valueA;
            normB += valueB * valueB;
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public record Memory(
            int id,
            String type,
            String timestamp,
            String prompt,
            String response,
            double similarity) {
    }

}
