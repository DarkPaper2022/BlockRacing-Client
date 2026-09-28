package top.lqsnow.blockracing.client.test;

import top.lqsnow.blockracing.client.BoardState;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts command-solvable task requirements into vanilla commands. This does
 * not mark tasks complete: the server must observe the resulting inventory,
 * advancement, effect, level or location through its normal production logic.
 */
public final class TaskCommandPlanner {
    private static final Pattern SAFE_PLAYER = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern ITEM = Pattern.compile("([A-Z0-9_]+)(?:\\*(\\d+))?");

    public record Plan(boolean supported, String kind, List<String> commands, String reason) {
        static Plan supported(String kind, String... commands) {
            return new Plan(true, kind, List.of(commands), "");
        }
        static Plan unsupported(String reason) {
            return new Plan(false, "unsupported", List.of(), reason);
        }
    }

    private TaskCommandPlanner() {}

    public static Plan plan(BoardState.Task task, String player) {
        if (task == null || player == null || !SAFE_PLAYER.matcher(player).matches()) {
            return Plan.unsupported("invalid task or player");
        }
        String requirement = task.requirement() == null ? "" : task.requirement().trim();
        if (requirement.isEmpty()) {
            return give("block", player, task.id(), 1);
        }

        int separator = requirement.indexOf(':');
        String kind = separator < 0 ? requirement : requirement.substring(0, separator);
        String value = separator < 0 ? "" : requirement.substring(separator + 1);
        return switch (kind) {
            case "item" -> item(player, value);
            case "advancement" -> namespaced(value).isEmpty()
                    ? Plan.unsupported("invalid advancement")
                    : Plan.supported(kind, "advancement grant " + player + " only " + namespaced(value));
            case "effect" -> identifier(value).isEmpty()
                    ? Plan.unsupported("invalid effect")
                    : Plan.supported(kind, "effect give " + player + " minecraft:" + identifier(value) + " 20 0 true");
            case "level" -> positiveInt(value) < 0 ? Plan.unsupported("invalid level")
                    : Plan.supported(kind, "experience set " + player + " " + positiveInt(value) + " levels");
            case "location" -> location(player, value);
            default -> Plan.unsupported("interactive requirement type: " + kind);
        };
    }

    private static Plan item(String player, String value) {
        // item:A*64,B*64 means all listed stacks; alternatives are intentionally
        // not guessed here because the requirement language uses separate goal logic.
        String[] specs = value.split(",");
        java.util.ArrayList<String> commands = new java.util.ArrayList<>();
        for (String spec : specs) {
            Matcher matcher = ITEM.matcher(spec.trim().toUpperCase(Locale.ROOT));
            if (!matcher.matches()) return Plan.unsupported("unsupported item expression: " + spec);
            int amount = matcher.group(2) == null ? 1 : positiveInt(matcher.group(2));
            if (amount < 1 || amount > 99_999) return Plan.unsupported("invalid item amount: " + spec);
            Plan command = give("item", player, matcher.group(1), amount);
            if (!command.supported()) return command;
            commands.addAll(command.commands());
        }
        return new Plan(true, "item", List.copyOf(commands), "");
    }

    private static Plan give(String kind, String player, String material, int amount) {
        String id = identifier(material);
        if (id.isEmpty()) return Plan.unsupported("invalid material: " + material);
        return Plan.supported(kind, "give " + player + " minecraft:" + id + " " + amount);
    }

    private static Plan location(String player, String value) {
        return switch (value) {
            case "height-limit" -> Plan.supported("location", "tp " + player + " ~ 319 ~");
            case "bedrock" -> Plan.supported("location", "tp " + player + " ~ -59 ~");
            case "nether-roof" -> Plan.supported("location",
                    "execute in minecraft:the_nether run tp " + player + " ~ 129 ~");
            default -> Plan.unsupported("unknown location: " + value);
        };
    }

    private static String namespaced(String value) {
        if (!value.matches("[a-z0-9_./-]+")) return "";
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private static String identifier(String value) {
        String id = value.toLowerCase(Locale.ROOT);
        return id.matches("[a-z0-9_]+") ? id : "";
    }

    private static int positiveInt(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return -1; }
    }
}
