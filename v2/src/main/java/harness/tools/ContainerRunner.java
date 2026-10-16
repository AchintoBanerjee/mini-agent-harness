package harness.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs one command in a throwaway container: the sandbox for a tool that runs code the
 * harness did not write. Every call starts a new container from the image and removes it
 * at the end, so nothing a call does survives it. The walls are flags on the docker
 * command: no network, a read-only root with a small scratch /tmp, caps on memory, CPU
 * and processes, an unprivileged user with every capability dropped, nothing mounted. The
 * host's environment is not the container's, so the worker's secrets are not in the box;
 * the tool's input arrives as the command's argument. When the caller's clock cancels the
 * call, the runner removes the container by name, because the docker command is only a
 * client and the container would otherwise run on. The one wall in Java is the cap on
 * what a call may print.
 *
 * This class is the stand-in for a runner service: in this POC the worker runs the docker
 * command on the same machine, in production the worker calls a service that owns the
 * containers on a node pool with no secrets on it (docs/SANDBOX.md). Without Docker the
 * call fails as an ERROR result and the loop goes on; the demo says so and skips its
 * sandbox section.
 */
public class ContainerRunner {

    public static final String DEFAULT_IMAGE = "alpine:3.20";

    /** The most a call may print; the rest is cut, with a note, so a tool cannot flood the row or the answer. */
    public static final int MAX_OUTPUT_BYTES = 64 * 1024;

    /** The walls, as the docker command takes them. */
    static final List<String> WALLS = List.of(
            "--network", "none",
            "--read-only", "--tmpfs", "/tmp:size=16m",
            "--memory", "64m", "--cpus", "1", "--pids-limit", "32",
            "--user", "65534:65534", "--cap-drop", "ALL", "--security-opt", "no-new-privileges");

    private static volatile Boolean available;

    private final String docker;
    private static final Map<String, Boolean> imagesHere = new ConcurrentHashMap<>();

    public ContainerRunner() {
        this("docker");
    }

    /** The docker command to run, "docker" unless a test points it elsewhere. */
    public ContainerRunner(String docker) {
        this.docker = docker;
    }

    /**
     * Starts a container named containerName from the image, runs the command in it with
     * args as $1, $2, ..., and returns what it printed on stdout. A non-zero exit throws with
     * the exit code and what it printed, stderr included, so "Network unreachable" reaches
     * the ERROR row. An interrupt (the caller's clock) removes the container and throws.
     * The first call for an image pulls it when it is not here yet, inside the caller's
     * clock: a slow pull cuts that one call off, and the next call finds the image.
     */
    public String run(String image, String command, List<String> args, String containerName) {
        ensureImage(image);
        List<String> cmd = new ArrayList<>(List.of(docker, "run", "--rm", "--name", containerName));
        cmd.addAll(WALLS);
        cmd.add(image);
        cmd.addAll(List.of("sh", "-c", command, "sh"));
        cmd.addAll(args);
        Path out = tempFile();
        Path err = tempFile();
        Process process;
        try {
            process = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        } catch (IOException e) {
            delete(out);
            delete(err);
            throw new IllegalStateException("Docker is not on the PATH (" + docker + ": " + e.getMessage()
                    + "): a container tool needs it, see docs/SANDBOX.md");
        }
        try {
            process.getOutputStream().close();
            int exit = process.waitFor();
            String printed = read(out);
            String errors = read(err);
            if (exit != 0) {
                if (errors.contains("Cannot connect to the Docker daemon")) {
                    throw new IllegalStateException("Docker is not running: a container tool needs it, see docs/SANDBOX.md");
                }
                String both = printed.isEmpty() ? errors : errors.isEmpty() ? printed : printed + "\n" + errors;
                throw new IllegalStateException("exit " + exit + (both.isEmpty() ? "" : ": " + both));
            }
            return printed;
        } catch (InterruptedException e) {
            remove(containerName);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("killed at the clock: container " + containerName);
        } catch (IOException e) {
            remove(containerName);
            process.destroyForcibly();
            throw new UncheckedIOException("cannot talk to the container's process", e);
        } finally {
            delete(out);
            delete(err);
        }
    }

    /** At most MAX_OUTPUT_BYTES of the file, stripped, with a note when there was more. */
    private static String read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            String text = new String(in.readNBytes(MAX_OUTPUT_BYTES), StandardCharsets.UTF_8).strip();
            return Files.size(file) > MAX_OUTPUT_BYTES ? text + "\n[output cut at " + MAX_OUTPUT_BYTES / 1024 + " KB]" : text;
        }
    }

    /** docker rm -f by name: stops a running container, removes one created and never started, harmless when there is none. */
    private void remove(String containerName) {
        try {
            Process rm = new ProcessBuilder(docker, "rm", "-f", containerName).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!rm.waitFor(10, TimeUnit.SECONDS)) {
                rm.destroyForcibly();
            }
        } catch (IOException | InterruptedException e) {
            // the container is gone or Docker is: nothing left to remove
        }
    }

    /** True when the docker command answers "docker info", checked once per process. */
    public static boolean available() {
        if (available == null) {
            available = new ContainerRunner().probe();
        }
        return available;
    }

    private boolean probe() {
        try {
            Process process = new ProcessBuilder(docker, "info").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Pulls the image unless it is already here, checked once per image and process: like the Maven build, the network once. */
    public void ensureImage(String image) {
        if (imagesHere.containsKey(image)) {
            return;
        }
        Path said = tempFile();
        try {
            Process inspect = new ProcessBuilder(docker, "image", "inspect", image).redirectErrorStream(true)
                    .redirectOutput(said.toFile()).start();
            if (!inspect.waitFor(15, TimeUnit.SECONDS)) {
                inspect.destroyForcibly();
                throw new IllegalStateException("docker image inspect " + image + " did not answer");
            }
            if (inspect.exitValue() == 0) {
                imagesHere.put(image, Boolean.TRUE);
                return;
            }
            if (read(said).contains("Cannot connect to the Docker daemon")) {
                throw new IllegalStateException("Docker is not running: a container tool needs it, see docs/SANDBOX.md");
            }
            Process pull = new ProcessBuilder(docker, "pull", image).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!pull.waitFor(3, TimeUnit.MINUTES)) {
                pull.destroyForcibly();
                throw new IllegalStateException("docker pull " + image + " did not finish in 3 minutes");
            }
            if (pull.exitValue() != 0) {
                throw new IllegalStateException("docker pull " + image + " failed");
            }
            imagesHere.put(image, Boolean.TRUE);
        } catch (IOException e) {
            // no docker command: run() reports that with the right message
        } catch (InterruptedException e) {
            // the caller's clock: the pull goes on in the background and the next call finds the image
            Thread.currentThread().interrupt();
            throw new IllegalStateException("killed at the clock while pulling " + image);
        } finally {
            delete(said);
        }
    }

    private static Path tempFile() {
        try {
            return Files.createTempFile("harness-box-", ".out");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the container's output file", e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // a leftover temp file is not worth failing the call
        }
    }
}
