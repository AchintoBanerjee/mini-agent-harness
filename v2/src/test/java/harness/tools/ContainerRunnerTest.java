package harness.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.core.Span;
import harness.model.ToolCall;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The walls of the sandbox, each tried from inside a container. These run only where
 * Docker answers; elsewhere they are skipped and the suite stays green (ToolLoaderTest
 * covers the path without Docker).
 */
@EnabledIf("harness.tools.ContainerRunner#available")
class ContainerRunnerTest {

    private static final String IMAGE = ContainerRunner.DEFAULT_IMAGE;
    private final ContainerRunner runner = new ContainerRunner();

    @BeforeAll
    static void pullTheImageOnce() {
        new ContainerRunner().ensureImage(IMAGE);
    }

    @Test
    void theCommandRunsInTheBoxWithTheInputAsItsArgument() {
        String out = runner.run(IMAGE, "echo \"hello $1\" | tr a-z A-Z", List.of("ORD-1"), name("echo"));

        assertEquals("HELLO ORD-1", out);
    }

    @Test
    void theWorkersEnvironmentIsNotInTheBox() {
        String env = runner.run(IMAGE, "env", List.of(), name("env"));

        for (String line : env.split("\n")) {
            String key = line.substring(0, line.indexOf('='));
            assertTrue(Set.of("PATH", "HOSTNAME", "HOME", "SHLVL", "PWD").contains(key),
                    "only what the container runtime and the shell set, got " + key);
        }
        assertFalse(env.contains(System.getProperty("user.home")), "nothing of this process's environment leaks in");
    }

    @Test
    void thereIsNoNetworkInTheBox() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> runner.run(IMAGE, "wget -q -T 2 -O - http://1.1.1.1/ && echo fetched", List.of(), name("net")));

        assertTrue(thrown.getMessage().startsWith("exit 1"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Network unreachable"), thrown.getMessage());
    }

    @Test
    void theRootIsReadOnlyAndTmpIsTheOnlyScratch() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> runner.run(IMAGE, "echo x > /etc/owned", List.of(), name("ro")));
        assertTrue(thrown.getMessage().contains("Read-only file system"), thrown.getMessage());

        assertEquals("scratch ok", runner.run(IMAGE, "echo x > /tmp/scratch && echo scratch ok", List.of(), name("tmp")));
    }

    @Test
    void theMemoryCapKillsAToolThatEatsIt() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> runner.run(IMAGE, "head -c 200m /dev/zero | tail", List.of(), name("mem")));

        assertTrue(thrown.getMessage().startsWith("exit 137"), "killed by the cap: " + thrown.getMessage());
    }

    @Test
    void aHangIsKilledAtTheClockAndNoContainerIsLeft() throws Exception {
        String container = name("hang");
        ToolRegistry one = new ToolRegistry();
        one.register(new Tool("hang_in_box", "sleeps in the box", "container", null, new MatchingCondition(List.of(), null),
                input -> runner.run(IMAGE, "sleep 60", List.of(), container)));
        ToolCaller caller = new ToolCaller(one, List.of("hang_in_box"), Duration.ofMillis(2500));

        ToolResult result = caller.call(new ToolCall("hang_in_box", Map.of()));

        assertEquals(Span.Status.TIMEOUT, result.status());
        assertEquals("cut off after 2500 ms", result.output());
        assertTrue(waitUntilGone(container), "the container " + container + " was killed by name");
    }

    private static boolean waitUntilGone(String container) throws Exception {
        for (int i = 0; i < 50; i++) {
            Process ps = new ProcessBuilder("docker", "ps", "-a", "-q", "--filter", "name=" + container).start();
            String ids = new String(ps.getInputStream().readAllBytes()).strip();
            ps.waitFor();
            if (ids.isEmpty()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static String name(String what) {
        return "harness-test-" + what + "-" + Long.toHexString(System.nanoTime());
    }
}
