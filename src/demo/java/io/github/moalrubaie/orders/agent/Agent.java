package io.github.moalrubaie.orders.agent;

import io.github.moalrubaie.orders.demo.Session;
import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

/**
 * A plain tool-use loop: ask the model, run the tools it asks for, send the results back, until
 * it answers or hits {@value #MAX_TURNS} turns. Every turn's token usage is added up.
 */
public final class Agent {

    public record Outcome(String answer, int turns, int inputTokens, int outputTokens, boolean gaveUp) {}

    static final int MAX_TURNS = 12;

    private final ChatModel model;
    private final ToolCallingManager tools = ToolCallingManager.builder().build();

    public Agent(ChatModel model) {
        this.model = model;
    }

    public Outcome run(Session session, String system, String question) {
        List<ToolCallback> callbacks = session.tools().stream()
                .map(t -> (ToolCallback) new McpToolCallback(session, t))
                .toList();
        // The model's own options (model ID, max tokens) plus this server's tools. Spring AI chat
        // models only ask for tool calls; running them is up to the caller, which is this loop.
        if (!(model.getOptions() instanceof ToolCallingChatOptions defaults)) {
            throw new IllegalArgumentException("The chat model doesn't support tool calling");
        }
        ToolCallingChatOptions options =
                defaults.mutate().toolCallbacks(callbacks).build();
        Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(question)), options);
        int turns = 0;
        int input = 0;
        int output = 0;
        ChatResponse response;
        while (true) {
            response = model.call(prompt);
            turns++;
            Usage usage = response.getMetadata().getUsage();
            input += usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            output += usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            if (!response.hasToolCalls() || turns == MAX_TURNS) {
                break;
            }
            ToolExecutionResult result = tools.executeToolCalls(prompt, response);
            prompt = new Prompt(result.conversationHistory(), options);
        }
        // A response can hold several generations (thinking blocks come first); keep the text.
        String answer = response.getResults().stream()
                .map(g -> g.getOutput().getText())
                .filter(t -> t != null && !t.isBlank())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        return new Outcome(answer, turns, input, output, response.hasToolCalls());
    }
}
