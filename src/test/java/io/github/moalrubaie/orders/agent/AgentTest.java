package io.github.moalrubaie.orders.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moalrubaie.orders.agent.Agent.Outcome;
import io.github.moalrubaie.orders.demo.Servers;
import io.github.moalrubaie.orders.demo.Session;
import io.github.moalrubaie.orders.store.SeedData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** The live agent's loop, with a scripted stand-in for the model and a real MCP server. */
class AgentTest {

    /** Asks for search_orders, then answers with whatever the tool returned. */
    static class ScriptedModel implements ChatModel {

        final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            Message last = prompt.getInstructions().getLast();
            AssistantMessage reply = last instanceof ToolResponseMessage tools
                    ? AssistantMessage.builder()
                            .content("Found: " + tools.getResponses().getFirst().responseData())
                            .build()
                    : AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    "call-1", "function", "search_orders", "{\"status\":\"DELAYED\"}")))
                            .build();
            return new ChatResponse(
                    List.of(new Generation(reply)),
                    ChatResponseMetadata.builder()
                            .usage(new DefaultUsage(100, 20))
                            .build());
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    @Test
    void runsTheToolTheModelAsksForAndSendsBackTheResult() {
        ScriptedModel model = new ScriptedModel();
        try (Servers.Running server = Servers.start("designed");
                Session session = Session.connect(server.url(), SeedData.DANA)) {
            Outcome outcome = new Agent(model).run(session, "system", "Are any of my orders late?");

            assertThat(session.calls()).extracting(Session.Call::tool).containsExactly("search_orders");
            assertThat(outcome.answer()).startsWith("Found: ").contains("DELAYED");
            assertThat(outcome.turns()).isEqualTo(2);
            assertThat(outcome.inputTokens()).isEqualTo(200);
            assertThat(outcome.outputTokens()).isEqualTo(40);
            assertThat(outcome.gaveUp()).isFalse();

            ToolCallingChatOptions options =
                    (ToolCallingChatOptions) model.prompts.getFirst().getOptions();
            assertThat(options.getToolCallbacks())
                    .extracting(t -> t.getToolDefinition().name())
                    .containsExactlyInAnyOrder("get_order_status", "search_orders", "cancel_order");
        }
    }
}
