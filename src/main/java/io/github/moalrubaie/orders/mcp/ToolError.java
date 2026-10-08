package io.github.moalrubaie.orders.mcp;

/**
 * A tool call that failed in a way the model should hear about. Its message becomes the text of
 * an {@code isError} tool result, so it is written for the model, not for a log file.
 *
 * <p>It is a checked exception on purpose: Spring AI passes checked exceptions through to
 * {@link ToolSpecs}, which turns them into a result with exactly this message and nothing else.
 */
public class ToolError extends Exception {

    public ToolError(String message) {
        super(message, null, false, false);
    }
}
