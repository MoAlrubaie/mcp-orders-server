package io.github.moalrubaie.orders.demo;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Reads tool definitions the way a model does, names, descriptions and schemas only, and checks
 * them against a pre-ship checklist. Mechanical checks can't tell you a design is good, but they
 * catch the usual ways a definition leaves the model guessing.
 */
public final class Checklist {

    /** {@code failure} returns null when the tool passes, otherwise why it fails. */
    public record Check(String question, BiFunction<Tool, List<Tool>, String> failure) {}

    public record Outcome(String question, int passed, int total, List<String> failures) {}

    private static final Set<String> ACTION_VERBS = Set.of(
            "get", "search", "list", "find", "track", "cancel", "create", "update", "delete", "add", "remove", "start");
    private static final Set<String> READ_VERBS = Set.of("get", "search", "list", "find", "track");
    private static final Pattern CATEGORICAL = Pattern.compile("status|state|reason|category|type|kind|sort");
    // An example, a format, or where the value comes from ("the next_cursor value from ...").
    private static final Pattern EXAMPLE = Pattern.compile("\\blike\\b|e\\.g\\.|\\bform\\b|YYYY|\\d|value from");

    public static final List<Check> CHECKS = List.of(
            new Check("Named with an action verb and a noun", (tool, all) -> {
                String verb = firstWord(tool.name());
                return ACTION_VERBS.contains(verb) ? null : "starts with \"" + verb + "\"";
            }),
            new Check(
                    "Can't be confused with another tool's name",
                    (tool, all) -> all.stream()
                            .filter(other -> other != tool)
                            .filter(other -> other.name().startsWith(tool.name())
                                    || tool.name().startsWith(other.name()))
                            .map(other -> "vs " + other.name())
                            .findFirst()
                            .orElse(null)),
            new Check("Description says when to use it", (tool, all) -> {
                String d = tool.description() == null ? "" : tool.description();
                return d.length() >= 80 && d.contains("Use ") ? null : "\"" + d + "\"";
            }),
            new Check("Parameters described, with an example, allowed values or a source", (tool, all) -> {
                for (var p : properties(tool).entrySet()) {
                    Map<?, ?> schema = (Map<?, ?>) p.getValue();
                    String description = (String) schema.get("description");
                    if (description == null || description.isBlank()) {
                        return p.getKey() + " has no description";
                    }
                    if ("string".equals(schema.get("type"))
                            && schema.get("enum") == null
                            && !EXAMPLE.matcher(description).find()) {
                        return p.getKey() + ": \"" + description + "\"";
                    }
                }
                return null;
            }),
            new Check("Fixed-choice parameters are enums, not free text", (tool, all) -> {
                for (var p : properties(tool).entrySet()) {
                    Map<?, ?> schema = (Map<?, ?>) p.getValue();
                    if (CATEGORICAL.matcher(p.getKey().toLowerCase()).find() && schema.get("enum") == null) {
                        return p.getKey() + " is free text";
                    }
                }
                return null;
            }),
            new Check("Hints match what it does (reads are read-only)", (tool, all) -> {
                ToolAnnotations a = tool.annotations();
                if (!READ_VERBS.contains(firstWord(tool.name()))) {
                    return a == null ? "no annotations" : null;
                }
                return a != null && Boolean.TRUE.equals(a.readOnlyHint()) && !Boolean.TRUE.equals(a.destructiveHint())
                        ? null
                        : "a read marked " + (a == null ? "with no hints" : "destructive, not read-only");
            }),
            new Check(
                    "Declares an output schema",
                    (tool, all) -> tool.outputSchema() != null ? null : "returns untyped text"));

    private Checklist() {}

    public static List<Outcome> run(List<Tool> tools) {
        return CHECKS.stream()
                .map(check -> {
                    List<String> failures = tools.stream()
                            .map(t -> {
                                String why = check.failure().apply(t, tools);
                                return why == null ? null : t.name() + ": " + why;
                            })
                            .filter(f -> f != null)
                            .toList();
                    return new Outcome(check.question(), tools.size() - failures.size(), tools.size(), failures);
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Tool tool) {
        Object properties = tool.inputSchema().get("properties");
        return properties instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** "search_orders" and "searchOrders" both start with "search". */
    private static String firstWord(String name) {
        return name.split("_|(?=[A-Z])")[0].toLowerCase();
    }
}
