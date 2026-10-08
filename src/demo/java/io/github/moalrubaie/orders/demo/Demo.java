package io.github.moalrubaie.orders.demo;

import io.github.moalrubaie.orders.demo.Checklist.Outcome;
import io.github.moalrubaie.orders.demo.Session.Call;
import io.github.moalrubaie.orders.demo.Tasks.Task;
import io.github.moalrubaie.orders.store.OrderStore;
import io.github.moalrubaie.orders.store.SeedData;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.PrintStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts both servers in-process, connects to each as an MCP client, and compares them: what the
 * model has to read, how the definitions fare against a checklist, what the same five tasks cost,
 * what the same mistakes return, and what a manipulated model could reach. No API key needed.
 *
 * <p>Exits non-zero if the designed server gets an answer wrong, fails a checklist item, or costs
 * more than the naive one on any task.
 */
public final class Demo {

    public record Run(String server, int calls, int resultTokens, int inputTokens, String answer, boolean correct) {}

    public record Result(
            int naiveDefinitionTokens,
            int designedDefinitionTokens,
            List<Outcome> naiveChecklist,
            List<Outcome> designedChecklist,
            List<Run> naiveRuns,
            List<Run> designedRuns,
            List<String> problems) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SOMEONE_ELSE = "ORD-10003"; // carries the planted note

    private final PrintStream out;

    private Demo(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] args) {
        Result result = run(System.out);
        System.exit(result.problems().isEmpty() ? 0 : 1);
    }

    public static Result run(PrintStream out) {
        try (Servers.Running naive = Servers.start("naive");
                Servers.Running designed = Servers.start("designed")) {
            return new Demo(out).compare(naive, designed);
        }
    }

    private Result compare(Servers.Running naiveServer, Servers.Running designedServer) {
        String designedUrl = designedServer.url();
        try (Session naive = Session.connect(naiveServer.url(), null);
                Session designed = Session.connect(designedUrl, SeedData.DANA)) {
            List<Tool> naiveTools = naive.tools();
            List<Tool> designedTools = designed.tools();
            int naiveDefs = definitionTokens(naiveTools);
            int designedDefs = definitionTokens(designedTools);

            out.println("One order service, two MCP servers");
            out.printf("  naive     %2d tools, one per REST endpoint, returning the API's JSON%n", naiveTools.size());
            out.printf("  designed  %2d tools, shaped around what a customer asks%n", designedTools.size());
            OrderStore data = designedServer.store();
            out.printf(
                    "  Seeded data: %d customers, %d orders. Today is %s. The customer is Dana Whitfield (%s).%n",
                    data.customers().size(),
                    data.orders().size(),
                    DateTimeFormatter.ofPattern("EEE MMM d, yyyy", Locale.US).format(data.today()),
                    SeedData.DANA);

            section("1. What the model reads before every turn: the tool definitions");
            out.printf("  %-10s %5s %18s%n", "server", "tools", "≈ tokens");
            out.printf("  %-10s %5d %,18d%n", "naive", naiveTools.size(), naiveDefs);
            out.printf("  %-10s %5d %,18d%n", "designed", designedTools.size(), designedDefs);

            section("2. Checklist, run against each server's tools/list");
            List<Outcome> naiveChecklist = Checklist.run(naiveTools);
            List<Outcome> designedChecklist = Checklist.run(designedTools);
            out.printf("  %-66s %8s %9s%n", "tools that pass", "naive", "designed");
            for (int i = 0; i < naiveChecklist.size(); i++) {
                Outcome n = naiveChecklist.get(i);
                Outcome d = designedChecklist.get(i);
                out.printf(
                        "  %-66s %8s %9s%n",
                        n.question(), n.passed() + " of " + n.total(), d.passed() + " of " + d.total());
            }
            out.println("  For example, on the naive server:");
            naiveChecklist.stream()
                    .filter(o -> !o.failures().isEmpty())
                    .forEach(o -> out.println("    " + truncate(o.failures().getFirst(), 90)));

            section("3. Five tasks, each run as the shortest correct sequence of calls");
            List<Run> naiveRuns = new ArrayList<>();
            List<Run> designedRuns = new ArrayList<>();
            out.printf(
                    "  %-44s %-9s %5s %10s %13s  %s%n", "task", "server", "calls", "results", "input total*", "answer");
            for (Task task : Tasks.all()) {
                Run n = attempt("naive", naive, task.naive(), task, naiveServer.store(), naiveDefs);
                Run d = attempt("designed", designed, task.designed(), task, designedServer.store(), designedDefs);
                naiveRuns.add(n);
                designedRuns.add(d);
                printRun(task.question(), n);
                printRun("", d);
            }
            printTotal("total", naiveRuns);
            printTotal("", designedRuns);
            out.println("  Token counts are ≈ characters ÷ 4. *Input total: what the model reads across the task's"
                    + " turns, the tool");
            out.println("  definitions on every turn plus each earlier result, since results stay in the"
                    + " conversation.");
            out.println("  The designed cancel takes two calls on purpose: the first one only previews.");

            section("4. The same mistakes, and the error each server sends back");
            mistake(
                    "An invoice number where the order ID goes",
                    naive.call("getOrdersById", Map.of("id", "8841")),
                    designed.call("get_order_status", Map.of("order_id", "8841")));
            mistake(
                    "\"late\" as a status",
                    naive.call("getOrders", Map.of("customerId", SeedData.DANA, "status", "late")),
                    designed.call("search_orders", Map.of("status", "late")));
            mistake(
                    "\"last month\" as a date",
                    null,
                    designed.call("search_orders", Map.of("placed_after", "last month")));
            mistake(
                    "Cancelling an order that has already shipped",
                    naive.call("postOrdersByIdCancel", Map.of("id", Tasks.IN_TRANSIT)),
                    designed.call("cancel_order", Map.of("order_id", Tasks.IN_TRANSIT, "reason", "CHANGED_MIND")));

            section("5. What a model talked into misbehaving could reach, signed in as Dana");
            safety(naive, designed, naiveTools, designedTools, designedUrl);

            List<String> problems = problems(designedChecklist, naiveRuns, designedRuns);
            section("Result");
            if (problems.isEmpty()) {
                int naiveTotal = naiveRuns.stream().mapToInt(Run::inputTokens).sum();
                int designedTotal =
                        designedRuns.stream().mapToInt(Run::inputTokens).sum();
                out.printf(
                        "  Both servers answer all five tasks. The designed one passes every checklist item and"
                                + " reads %.1fx fewer tokens doing it.%n",
                        (double) naiveTotal / designedTotal);
            } else {
                problems.forEach(p -> out.println("  PROBLEM: " + p));
            }
            return new Result(
                    naiveDefs, designedDefs, naiveChecklist, designedChecklist, naiveRuns, designedRuns, problems);
        }
    }

    private Run attempt(
            String server,
            Session session,
            Function<Session, String> playbook,
            Task task,
            OrderStore store,
            int definitionTokens) {
        session.forget();
        String answer;
        try {
            answer = playbook.apply(session);
        } catch (RuntimeException e) {
            answer = "failed: " + e.getMessage();
        }
        List<Call> calls = session.calls();
        int results = calls.stream().mapToInt(Call::tokens).sum();
        // Turn k sees the definitions plus every result before it; the last turn writes the answer.
        int input = (calls.size() + 1) * definitionTokens;
        for (int i = 0; i < calls.size(); i++) {
            input += calls.get(i).tokens() * (calls.size() - i);
        }
        return new Run(server, calls.size(), results, input, answer, task.answered(answer, store));
    }

    private void printRun(String question, Run run) {
        out.printf(
                "  %-44s %-9s %5d %,10d %,13d  %s %s%n",
                question,
                run.server(),
                run.calls(),
                run.resultTokens(),
                run.inputTokens(),
                run.correct() ? "✓" : "✗",
                truncate(run.answer(), 56));
    }

    private void printTotal(String label, List<Run> runs) {
        out.printf(
                "  %-44s %-9s %5d %,10d %,13d%n",
                label,
                runs.getFirst().server(),
                runs.stream().mapToInt(Run::calls).sum(),
                runs.stream().mapToInt(Run::resultTokens).sum(),
                runs.stream().mapToInt(Run::inputTokens).sum());
    }

    private void mistake(String what, Call naive, Call designed) {
        out.println("  " + what);
        if (naive == null) {
            out.println("    naive     (no date filter: the agent has to page through every order and compare dates)");
        } else {
            out.println("    naive     " + describe(naive));
        }
        out.println("    designed  " + describe(designed));
    }

    private void safety(
            Session naive, Session designed, List<Tool> naiveTools, List<Tool> designedTools, String designedUrl) {
        out.println("  Someone else's order (" + SOMEONE_ELSE + ")");
        JsonNode other = naive.json("getOrdersById", Map.of("id", SOMEONE_ELSE));
        out.printf(
                "    naive     getOrdersById returns %s's email, phone, address and card digits,%n"
                        + "              and a customer note that says:%n",
                other.get("customer").get("name").asString());
        out.println("              \"" + truncate(other.get("customerNote").asString(), 92) + "\"");
        out.println("    designed  " + describe(designed.call("get_order_status", Map.of("order_id", SOMEONE_ELSE))));

        out.println("  Everyone's contact details, which that note asks for");
        JsonNode customers = naive.json("getCustomers", Map.of("size", 200));
        out.printf(
                "    naive     getCustomers returns %d customers with email and phone (≈ %,d tokens)%n",
                customers.get("content").size(), naive.calls().getLast().tokens());
        int reachable = 0;
        String cursor = null;
        do {
            Map<String, Object> args = new LinkedHashMap<>();
            if (cursor != null) {
                args.put("cursor", cursor);
            }
            JsonNode page = designed.json("search_orders", args);
            reachable += page.get("orders").size();
            cursor = page.has("next_cursor") ? page.get("next_cursor").asString() : null;
        } while (cursor != null);
        out.printf(
                "    designed  no tool returns customers; everything reachable is Dana's own %d orders%n", reachable);

        out.println("  Tools a client should confirm before running (destructiveHint)");
        out.printf(
                "    naive     %d of %d, every read included, so confirmations turn into noise%n",
                destructive(naiveTools), naiveTools.size());
        out.printf(
                "    designed  %d of %d, cancel_order, which also previews before it changes anything%n",
                destructive(designedTools), designedTools.size());

        out.println("  A connection with no signed-in customer");
        try (Session anonymous = Session.connect(designedUrl, null)) {
            out.println("    designed  " + describe(anonymous.call("search_orders", Map.of())));
        }
    }

    private static List<String> problems(List<Outcome> designedChecklist, List<Run> naive, List<Run> designed) {
        List<String> problems = new ArrayList<>();
        designedChecklist.stream()
                .filter(o -> !o.failures().isEmpty())
                .forEach(o -> problems.add("designed fails \"" + o.question() + "\": " + o.failures()));
        for (int i = 0; i < designed.size(); i++) {
            Run n = naive.get(i);
            Run d = designed.get(i);
            if (!d.correct()) {
                problems.add("designed got task " + (i + 1) + " wrong: " + d.answer());
            }
            if (!n.correct()) {
                problems.add("naive got task " + (i + 1) + " wrong: " + n.answer());
            }
            if (d.inputTokens() >= n.inputTokens()) {
                problems.add("designed cost more than naive on task " + (i + 1));
            }
        }
        return problems;
    }

    private static int destructive(List<Tool> tools) {
        return (int) tools.stream()
                .filter(t -> t.annotations() != null
                        && Boolean.TRUE.equals(t.annotations().destructiveHint()))
                .filter(t -> !Boolean.TRUE.equals(t.annotations().readOnlyHint()))
                .count();
    }

    /** What a host sends the model for each tool: name, description and input schema. */
    static int definitionTokens(List<Tool> tools) {
        List<Map<String, Object>> definitions = tools.stream()
                .map(t -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("name", t.name());
                    d.put("description", t.description());
                    d.put("input_schema", t.inputSchema());
                    return d;
                })
                .toList();
        return Tokens.of(JSON.writeValueAsString(definitions));
    }

    private static String describe(Call call) {
        return call.tool() + " " + new TreeMap<>(call.arguments()) + "\n              → " + wrap(call.text());
    }

    private static String wrap(String text) {
        StringBuilder out = new StringBuilder();
        int column = 0;
        for (String word : text.split(" ")) {
            if (column > 0 && column + word.length() > 84) {
                out.append("\n                ");
                column = 0;
            }
            out.append(column > 0 ? " " : "").append(word);
            column += word.length() + 1;
        }
        return out.toString();
    }

    private static String truncate(String text, int max) {
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }

    private void section(String title) {
        out.println();
        out.println(title);
    }
}
