# Sandboxing tools

[The issue](#the-issue) · [The design](#the-design) · [The choices](#the-choices) · [What I ran](#what-i-ran-on-my-platform) · [The implementation](#the-implementation) · [Proof](#proof) · [Without Docker](#without-docker) · [What v2 implements](#what-v2-implements)

The ask, in the brief's words: sandboxing tool execution "so a misbehaving tool can't reach the rest of the system". The rest of the system is the worker's process and memory, the store, the keys in the worker's environment, and the network. Every output below is pasted from `demo-output.txt`, which `./demo.sh` writes.

## The issue

- The model picks the tool and its arguments. A tool is code that runs on the worker, with everything the worker has: the database file, the environment with its keys, the open network.
- A tool misbehaves in four ways: it hangs, it throws, it eats the machine, or it reaches out, to files, the network, or systems it has no business with. The first two the caller already handles: a hang is cut off by the clock, on [the features page](V2_FEATURES.md#a-clock-on-every-tool-call), and a throw is an ERROR row. The last two are what a sandbox is for.
- Our own code calling a known service, every tool in v2 before this page, needs no box: the worst it does is hang or throw. A tool that runs code it did not write, the model's or a third party's, does need one. Inside the worker's process that code can read the keys, open the database file and call any host.

## The design

- **One more tool type, nothing else in the loop.** `type: container`, an image and a command. The loader builds its handler like any other tool's; the caller gates it by the agent's list and clocks it like any other; the loop writes the same rows. A sandboxed tool is a file in `tools/`.
- **A container per call, thrown away after.** Every call starts a new container from the image and removes it at the end. Nothing a call writes survives it; the next call starts from the image again.
- **The walls are settings, not code.** No network. A read-only root with a small scratch `/tmp`. Caps on memory, CPU and processes. An unprivileged user with every capability dropped. Nothing mounted. The host's environment is not the container's, so the worker's secrets are not in the box; the tool's input arrives as the command's argument.
- **The clock kills the container.** The caller cancels the call at the deadline as it does for every tool. The runner catches that and kills the container by name, because the `docker` command is only a client and the container would otherwise run on.
- **The tool still acts as the user.** Design, both parts: a box that must reach a service gets an allow-list of the one or two hosts it may call in place of no network, and whatever it may call it calls with the user's own token, scoped to the call, never a platform key. A bad tool reaches only what that user could reach anyway. v2 has no user identity and no allow-list.
- **In production the worker does not run containers.** It calls a runner service over HTTP with the image, the command and the input, under the same clock. The runner owns a warm pool of sandbox pods on Kubernetes, each used for one call: resource limits, a NetworkPolicy that denies all egress, with the allow-list for the one or two hosts a tool may call, empty by default, a read-only root, no service account token, no secrets, on a node pool of its own where nothing else runs; a runtime class, gVisor or a microVM, when many tenants' code shares hosts. Same tool type, same handler shape, a different place.

## The choices

| Option | How it works | What you get | What it costs |
|---|---|---|---|
| the loop's process and the clock | a thread per call, cancelled at the deadline | a hang and a throw contained, nothing per call | a tool that loops, allocates or reaches out hurts the worker; the keys are one environment read away |
| a process with its environment cleared | an OS process per call, killed at the clock | the secrets out, a crash contained | it shares the kernel, the files and the network with the worker: not a sandbox |
| a container per call | a new container from an image, the walls as settings, removed after | no network, none of the host's files, no secrets, caps | a few hundred milliseconds to start each call; it shares the host kernel, so the host must hold nothing worth taking |
| a microVM or a kernel in user space per call | Firecracker, its own kernel, or gVisor, a kernel in user space, either as a runtime class | the host kernel out of reach | a heavier runtime; needed when many tenants' untrusted code shares machines |
| a runner service in front | the worker never touches containers; a service owns a warm pool | no Docker on the worker, the start-up hidden by the pool, one place for policy | one more service to run |

I pick the clock for our own tools, a container per call for anything that runs code it did not write, a runner service in front of it in production, and a microVM or gVisor where tenants share hosts. v2 builds the container per call with the `docker` command on the same machine; the runner service is design.

## What I ran on my platform

- In production: tools calling the business systems as the user, with the user's own credentials passed through and scoped to the call. A tool could reach only what that user could reach.
- As a proof of concept, not in production: a container runtime for a coding agent, a container per task, thrown away after. The same shape as this page.

## The implementation

`ContainerRunner.run(image, command, args, name)` builds the command below, starts it with stdout and stderr going to two files, and waits. A non-zero exit throws with the code and what was printed, stderr included, which the caller turns into an ERROR row. An interrupt, the caller's clock, removes the container by name and throws. One wall is in Java, not a flag: a call may print 64 KB, the rest is cut with a note, so a tool cannot flood the row or the answer. The first call for an image pulls it when it is not here, inside the clock: a slow pull cuts that call off and the next one finds the image; the demo pulls before its section.

```
docker run --rm --name <name> \
  --network none --read-only --tmpfs /tmp:size=16m \
  --memory 64m --cpus 1 --pids-limit 32 \
  --user 65534:65534 --cap-drop ALL --security-opt no-new-privileges \
  <image> sh -c '<command>' sh <input>
```

`--rm` is the throw-away. Everything on the middle lines is a wall. The last line is the tool: its command under `sh -c`, the tool's input as `$1`. The image is the only thing that persists, pulled once.

`ToolLoader`'s `container` branch reads `command` and, when the file has one, `image` (`alpine:3.20` otherwise), and names each call's container after the tool plus a random suffix, so the clock can kill the right one. `ToolCaller` does what it does for every tool: the agent's list, the deadline, the cancel, the ERROR row.

The `sandbox` agent lists five container tools, with a 3000 ms clock because a container takes a few hundred milliseconds to start:

| Tool | Its command, inside the box | What happens |
|---|---|---|
| `fingerprint` | `printf '%s' "$1" \| sha256sum \| cut -c1-16` | honest work: a hash of the id, an OK row |
| `read_secrets` | `printenv HARNESS_SECRET \|\| { printf 'no HARNESS_SECRET in the box; its whole environment is: '; env \| cut -d= -f1 \| tr '\\n' ' '; }` | the worker's secret is not there; the box's whole environment is `HOSTNAME SHLVL HOME PATH PWD`, what the runtime and the shell set |
| `call_out` | `wget -q -T 2 -O - http://1.1.1.1/ && echo fetched 1.1.1.1` | no network: `Network unreachable`, exit 1, an ERROR row |
| `write_disk` | `echo owned > /etc/owned && echo wrote /etc/owned` | read-only root: exit 1, an ERROR row |
| `hang_in_box` | `sleep 60` | cut off at the clock, a TIMEOUT row, the container killed by name |

## Proof

<details><summary>proof: three tools that try to reach the rest of the system, in one pass, each in its own container; the worker was started with a secret in its environment</summary>

```
$ curl -s -X POST http://localhost:64430/runs -d '{"agent":"sandbox","question":"read the secret, call out to the network, write to the disk"}'
{
  "turnId" : "r-6e0d",
  "jobId" : "r-6e0d",
  "conversationId" : "c-b305",
  "status" : "QUEUED"
}
$ HARNESS_SECRET=hunter2 java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once --api http://localhost:64430
worker pid 64437 · lease 2000 ms · once · streams to http://localhost:64430 · harness.db
received r-6e0d: attempt 1 · agent sandbox · "read the secret, call out to the network, write to the disk"
  1  LLM   pass 1 -> ToolCalls[calls=[ToolCall[toolName=read_secrets, input={}], ToolCall[toolName=call_out, input={}], ToolCall[toolName=write_disk, input={}]]]  [OK 3 ms]
  2  TOOL  read_secrets {} -> no HARNESS_SECRET in the box; its whole environment is: HOSTNAME SHLVL HOME PATH PWD  [OK 430 ms]
  3  TOOL  call_out {} -> Tool failed: exit 1: wget: can't connect to remote host (1.1.1.1): Network unreachable  [ERROR 427 ms]
  4  TOOL  write_disk {} -> Tool failed: exit 1: sh: line 0: can't create /etc/owned: Read-only file system  [ERROR 427 ms]
  5  NOTE  ran 3 tools together: wall 432 ms, work 1284 ms  [OK 432 ms]
  6  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: no HARNESS_SECRET in the box; its whole environment is: HOSTNAME SHLVL HOME PATH PWD
Tool call_out ERROR: Tool failed: exit 1: wget: can't connect to remote host (1.1.1.1): Network unreachable
Tool write_disk ERROR: Tool failed: exit 1: sh: line 0: can't create /etc/owned: Read-only file system
done r-6e0d · 6 spans · 602 ms · DONE in harness.db · acked · handler calls: call_out=1, read_secrets=1, write_disk=1
```
`read_secrets` ran `printenv HARNESS_SECRET` and found nothing: the box's whole environment is what the container runtime and the shell set, `HOSTNAME SHLVL HOME PATH PWD`, and the worker's `HARNESS_SECRET` is not in it. `call_out` got `Network unreachable` in half a second, because the container has no network interface but loopback. `write_disk` got `Read-only file system`. Both are ERROR rows, each with the exit code and the output, and the loop went on to pass 2 and said done. The three ran together: wall 432 ms against 1284 ms of work, each in a container of its own.
</details>

<details><summary>proof: honest work and a runaway in one pass; the hung container is killed by name and nothing is left behind</summary>

```
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once --api http://localhost:64430
worker pid 64450 · lease 2000 ms · once · streams to http://localhost:64430 · harness.db
received r-3cd6: attempt 1 · agent sandbox · "fingerprint order ORD-1 and hang in the box"
  1  LLM   pass 1 -> ToolCalls[calls=[ToolCall[toolName=fingerprint, input={orderId=ORD-1}], ToolCall[toolName=hang_in_box, input={}]]]  [OK 2 ms]
  2  TOOL  fingerprint {orderId=ORD-1} -> f7f8bfa06d24d43c  [OK 351 ms]
  3  TOOL  hang_in_box {} -> cut off after 3000 ms  [TIMEOUT 3000 ms]
  4  NOTE  ran 2 tools together: wall 3003 ms, work 3351 ms  [OK 3003 ms]
  5  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: f7f8bfa06d24d43c
Tool hang_in_box TIMEOUT: cut off after 3000 ms
done r-3cd6 · 5 spans · 3140 ms · DONE in harness.db · acked · handler calls: fingerprint=1, hang_in_box=1
```
The `GET /turns/` reply that follows in `demo-output.txt` is left out here. After it:
```
$ docker ps -a --filter name=harness- --format "{{.Names}} {{.Status}}"      (nothing left: --rm removed each container at its end, and the hung one was killed)
(no containers left)
```
`fingerprint` hashed the id inside its box and returned in 351 ms: a container tool is a tool like any other when it behaves. `hang_in_box` slept and was cut off at the agent's 3000 ms clock, a TIMEOUT row like the hanging tool on the features page; the runner killed its container by name, because the `docker` command is only a client and the container would otherwise have slept on for a minute. `docker ps -a` after the turn lists no `harness-` container: `--rm` removed each one at its end, the killed one included.
</details>

## Without Docker

- The tools load and the agent is listed; the loop does not know Docker is missing until a call. The call returns an ERROR row, `Docker is not on the PATH` or `Docker is not running`, and the loop goes on: the same path as any tool that throws. `ToolLoaderTest` runs the first of those on every machine, with a `docker` command that does not exist.
- `./demo.sh` checks `docker info` and, when it fails, prints that the sandbox section is skipped and runs everything else. The section above is recorded here with Docker running.
- `mvn test` skips the six tests that need Docker: `Tests run: 135, Failures: 0, Errors: 0, Skipped: 6` without it, `Skipped: 0` with it.

## What v2 implements

| Part | In v2 | Design only |
|---|---|---|
| the container tool type | built: `type: container`, `command`, `image`; the input as `$1`; one more branch in the loader, nothing else in the loop | |
| a container per call, removed after | built: `--rm`, a name per call | the warm pool, so a call does not pay the start-up |
| the walls | built: no network, a read-only root with a scratch `/tmp`, caps on memory, CPU and processes, an unprivileged user, every capability dropped, nothing mounted, the host's environment not in the box | the allow-list for the one or two hosts a tool may call |
| the kill at the clock | built: the caller cancels at the deadline, the runner removes the container by name; a call may print 64 KB | |
| the tool acts as the user | | the user's token scoped to the call, for whatever a boxed tool may call |
| where it runs | built: the `docker` command on the worker's machine | a runner service on Kubernetes: a pod per call from a warm pool, a node pool with no secrets, a runtime class for a microVM |
| the proof | built: the demo's sandbox section; `ContainerRunnerTest`, six tests, skipped without Docker | |
