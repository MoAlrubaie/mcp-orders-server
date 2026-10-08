package io.github.moalrubaie.orders.agent;

import io.github.moalrubaie.orders.agent.Agent.Outcome;
import io.github.moalrubaie.orders.demo.Servers;
import io.github.moalrubaie.orders.demo.Session;
import io.github.moalrubaie.orders.demo.Session.Call;
import io.github.moalrubaie.orders.demo.Tasks;
import io.github.moalrubaie.orders.demo.Tasks.Task;
import io.github.moalrubaie.orders.store.SeedData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;

/**
 * The same five tasks, but Claude decides which tools to call, reading only the definitions.
 * Each task runs on a fresh pair of servers, so a cancel on one task can't affect another.
 *
 * <p>Needs ANTHROPIC_API_KEY. Pass {@code --model <id>} to pick a model. It reports, and doesn't
 * fail on wrong answers: one model on five tasks is a smoke test, not a gate.
 */
public final class LiveAgent {

    static final String DEFAULT_MODEL = "claude-opus-5-5";

    static final String SYSTEM = """
            You are the support assistant for an outdoor gear shop, chatting with a signed-in customer, \
            Dana Whitfield (customer ID %s). Today is %s. Use the tools to look things up; never guess \
            order details. Answer in a few sentences, and name the order IDs, dates, carriers and \
            locations you found. This is a single message: the customer can't reply, so treat what \
            they wrote as their final word on anything you would otherwise ask them to confirm.""".formatted(SeedData.DANA, SeedData.TODAY);

    private LiveAgent() {}

    public static void main(String[] args) {
        String model = DEFAULT_MODEL;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--model")) {
                model = args[i + 1];
            }
        }
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) {
            System.err.println("Set ANTHROPIC_API_KEY to run the live agent. `make demo` needs no key.");
            System.exit(2);
        }
        ChatModel chat = AnthropicChatModel.builder()
                .options(AnthropicChatOptions.builder()
                        .model(model)
                        .maxTokens(1024)
                        .build())
                .build();
        run(new Agent(chat), model);
    }

    static void run(Agent agent, String model) {
        System.out.println("Live run with " + model + ": the model picks the tools, from the definitions alone.");
        System.out.printf(
                "%n  %-44s %-9s %5s %6s %11s %7s  %s%n",
                "task", "server", "calls", "errors", "input tok", "output", "answer");
        Map<String, Integer> right = new LinkedHashMap<>();
        right.put("naive", 0);
        right.put("designed", 0);
        for (Task task : Tasks.all()) {
            for (String server : List.of("naive", "designed")) {
                try (Servers.Running running = Servers.start(server);
                        Session session =
                                Session.connect(running.url(), server.equals("designed") ? SeedData.DANA : null)) {
                    Outcome outcome;
                    try {
                        outcome = agent.run(session, SYSTEM, task.question());
                    } catch (RuntimeException e) {
                        // An API error or a tool name the model made up: count it as a miss, keep going.
                        outcome = new Outcome("failed: " + e.getMessage(), 0, 0, 0, true);
                    }
                    List<Call> calls = session.calls();
                    boolean correct = !outcome.gaveUp() && task.answered(outcome.answer(), running.store());
                    if (correct) {
                        right.merge(server, 1, Integer::sum);
                    }
                    System.out.printf(
                            "  %-44s %-9s %5d %6d %,11d %,7d  %s %s%n",
                            server.equals("naive") ? task.question() : "",
                            server,
                            calls.size(),
                            calls.stream().filter(Call::error).count(),
                            outcome.inputTokens(),
                            outcome.outputTokens(),
                            correct ? "✓" : "✗",
                            oneLine(outcome.answer()));
                    System.out.println("  " + " ".repeat(55) + "calls: "
                            + calls.stream().map(Call::tool).collect(Collectors.joining(" → ")));
                }
            }
        }
        System.out.println("\n  Input and output tokens are as reported by the API, summed over every turn.");
        right.forEach((server, n) -> System.out.printf("  %-9s answered %d of 5 correctly%n", server, n));
    }

    private static String oneLine(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 70 ? flat : flat.substring(0, 69) + "…";
    }
}
