package io.github.moalrubaie.orders.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moalrubaie.orders.demo.Checklist.Outcome;
import io.github.moalrubaie.orders.demo.Demo.Run;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs the whole demo, as `make demo` does, and checks what it claims. */
class DemoTest {

    private static Demo.Result result;
    private static String output;

    @BeforeAll
    static void runDemo() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        result = Demo.run(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        output = bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void noProblems() {
        assertThat(result.problems()).isEmpty();
        assertThat(output).contains("Both servers answer all five tasks.");
    }

    @Test
    void bothServersGetEveryTaskRight() {
        assertThat(result.naiveRuns()).hasSize(5).allMatch(Run::correct);
        assertThat(result.designedRuns()).hasSize(5).allMatch(Run::correct);
    }

    @Test
    void theDesignedServerReadsLessOnEveryTask() {
        for (int i = 0; i < 5; i++) {
            assertThat(result.designedRuns().get(i).inputTokens())
                    .as("task %d", i + 1)
                    .isLessThan(result.naiveRuns().get(i).inputTokens());
        }
    }

    @Test
    void theNaiveDefinitionsFailMostOfTheChecklist() {
        assertThat(result.designedChecklist()).allMatch(o -> o.failures().isEmpty());
        assertThat(result.naiveChecklist().stream().filter(o -> o.passed() < o.total()))
                .extracting(Outcome::question)
                .hasSize(7);
    }

    @Test
    void theSameNumbersEveryRun() {
        ByteArrayOutputStream again = new ByteArrayOutputStream();
        Demo.run(new PrintStream(again, true, StandardCharsets.UTF_8));
        assertThat(again.toString(StandardCharsets.UTF_8)).isEqualTo(output);
    }
}
