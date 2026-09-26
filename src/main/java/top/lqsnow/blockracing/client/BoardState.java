package top.lqsnow.blockracing.client;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Immutable bounded protocol model, independently testable without a running game. */
public record BoardState(String team, String state, boolean chinese, int score, int winScore,
                         int totalScore, String error, List<Task> tasks) {
    public static final int MAX_PACKET = 30_000;
    public static final int MAX_JSON = 512 * 1024;
    public record Task(String id, int index, String title, String requirement, String icon, String model,
                       boolean glint, int score, boolean bonus, boolean favorited, String status,
                       int current, int required, boolean progressKnown, boolean individual) {
        public double fraction() { return Math.min(1, Math.max(0, (double) current / required)); }
    }

    public static BoardState decode(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_PACKET) throw new IOException("Invalid packet size");
        byte[] json;
        try (var input = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            json = input.readNBytes(MAX_JSON + 1);
        }
        if (json.length > MAX_JSON) throw new IOException("Board decompression limit exceeded");
        try {
            JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            if (integer(root, "version", 0, 100) != 1) throw new IOException("Unsupported board protocol");
            String error = string(root, "error", 240, "");
            JsonArray rows = root.getAsJsonArray("tasks");
            if (rows == null || rows.size() > 512) throw new IOException("Invalid task count");
            var tasks = new ArrayList<Task>();
            var seen = new HashSet<String>();
            for (JsonElement element : rows) {
                JsonObject r = element.getAsJsonObject();
                String id = string(r, "id", 160, null);
                if (!seen.add(id)) throw new IOException("Duplicate task ID");
                String status = string(r, "status", 16, null);
                if (!Set.of("active", "queued", "resolved").contains(status)) throw new IOException("Invalid task state");
                tasks.add(new Task(id, integer(r, "index", 1, 512), string(r, "title", 241, null),
                        string(r, "requirement", 8193, ""), string(r, "icon", 160, null), string(r, "model", 200, ""),
                        bool(r, "glint"), integer(r, "score", 0, Integer.MAX_VALUE), bool(r, "bonus"), bool(r, "favorited"), status,
                        integer(r, "current", 0, Integer.MAX_VALUE), integer(r, "required", 1, Integer.MAX_VALUE),
                        bool(r, "progressKnown"), bool(r, "individual")));
            }
            if (!error.isEmpty()) return new BoardState("", "", true, 0, 0, 0, error, List.of());
            return new BoardState(string(root, "team", 8, ""), string(root, "state", 16, ""), bool(root, "chinese"),
                    integer(root, "score", 0, Integer.MAX_VALUE), integer(root, "winScore", 0, Integer.MAX_VALUE),
                    integer(root, "totalScore", 0, Integer.MAX_VALUE), "", List.copyOf(tasks));
        } catch (RuntimeException ex) { throw new IOException("Invalid task board data", ex); }
    }

    private static String string(JsonObject row, String key, int limit, String fallback) {
        if (!row.has(key)) {
            if (fallback != null) return fallback;
            throw new IllegalArgumentException("Missing " + key);
        }
        String value = row.get(key).getAsString();
        if (value.length() > limit) throw new IllegalArgumentException("Oversize " + key);
        return value;
    }
    private static boolean bool(JsonObject row, String key) {
        return row.has(key) && row.get(key).getAsBoolean();
    }
    private static int integer(JsonObject row, String key, int min, int max) {
        long value = row.get(key).getAsLong();
        if (value < min || value > max) throw new IllegalArgumentException("Out of range " + key);
        return (int) value;
    }
}
