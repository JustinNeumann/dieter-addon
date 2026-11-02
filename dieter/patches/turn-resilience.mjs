import { randomUUID } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { homedir, totalmem } from "node:os";
import { join } from "node:path";

// Raise `/proc/<pid>/oom_score_adj` on Linux when its current value is below `minScore`.
// Unprivileged processes can always increase their own (or same-UID children's) oom_score_adj,
// keeping the add-on's web host, operator, and daemon (at the container's baseline 200) alive
// ahead of expendable harness workers (500) and agent CLI subprocesses (600) under memory pressure.
export function raiseOomScoreAdj(minScore, pid = "self", procRoot = "/proc") {
  if (process.platform !== "linux" && procRoot === "/proc") return false;
  if (!Number.isInteger(minScore) || minScore < -1000 || minScore > 1000) return false;
  const target = `${procRoot}/${pid}/oom_score_adj`;
  try {
    const current = Number.parseInt(readFileSync(target, "utf8").trim(), 10);
    if (!Number.isFinite(current) || current >= minScore) return false;
    writeFileSync(target, `${minScore}\n`, "utf8");
    return true;
  } catch {
    return false;
  }
}

// Guard a worker's stdout pipe against unhandled EPIPE crashes when the parent daemon dies
// (for example, from SIGKILL) and notify the worker so it can unwind its session and kill
// its child process tree before exiting.
export function guardWorkerStdout({
  stream = process.stdout,
  initialPpid = process.ppid,
  currentPpid = () => process.ppid,
} = {}) {
  let broken = false;
  let notified = false;
  let disconnectCallback;

  const trigger = () => {
    if (notified) return;
    if ( typeof disconnectCallback === "function") {
      notified = true;
      disconnectCallback();
    }
  };

  const markBroken = () => {
    broken = true;
    trigger();
  };

  stream?.on?.("error", markBroken);
  stream?.on?.("close", markBroken);

  const isParentGone = () => initialPpid > 1 && currentPpid() !== initialPpid;

  return {
    isBroken: () => broken || Boolean(stream?.destroyed || stream?.writableEnded),
    isParentGone,
    checkParent() {
      if (broken || isParentGone()) {
        broken = true;
        trigger();
        return true;
      }
      return false;
    },
    onDisconnect(fn) {
      disconnectCallback = fn;
      if (broken || isParentGone()) {
        broken = true;
        trigger();
      }
    },
  };
}

export function processAlive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error?.code === "EPERM";
  }
}

export function defaultMaxSlots(totalMemBytes = totalmem()) {
  const gib = totalMemBytes / (1024 * 1024 * 1024);
  return Math.max(2, Math.min(6, Math.floor(gib / 2)));
}

export function availableMemoryBytes(meminfoPath = "/proc/meminfo") {
  try {
    const text = readFileSync(meminfoPath, "utf8");
    const match = text.match(/^MemAvailable:\s+(\d+)\s+kB$/m);
    if (!match) return null;
    const kb = Number.parseInt(match[1], 10);
    return Number.isFinite(kb) && kb >= 0 ? kb * 1024 : null;
  } catch {
    return null;
  }
}

// Acquire a host-wide concurrency slot before loading heavy harness SDKs and spawning provider CLIs.
// Slot 0 is always admitted regardless of available memory so at least one turn can make forward
// progress without deadlocking; slots 1..N-1 require MemAvailable >= minAvailableBytes when known.
export async function acquireTurnSlot({
  slotDir = process.env.DIETER_TURN_SLOTS_DIR || join(homedir(), ".ai-sdk-harness", ".turn-slots"),
  maxSlots = Number.parseInt(process.env.DIETER_MAX_CONCURRENT_TURNS || "", 10) || defaultMaxSlots(),
  minAvailableBytes = (Number.parseInt(process.env.DIETER_MIN_AVAILABLE_MB ?? "640", 10) || 0) *
    1024 *
    1024,
  getAvailableMemoryBytes = availableMemoryBytes,
  isProcessAlive = processAlive,
  pid = process.pid,
  pollMs = 200,
  abortSignal,
  shouldStop,
} = {}) {
  const effectiveMaxSlots = Math.max(1, Math.min(32, maxSlots));
  await mkdir(slotDir, { recursive: true, mode: 0o700 });
  const token = `${pid} ${randomUUID()}\n`;

  const tryClaimSlot = async (index) => {
    const path = join(slotDir, `slot-${index}.lock`);
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        await writeFile(path, token, { flag: "wx", mode: 0o600 });
        let released = false;
        return async () => {
          if (released) return;
          released = true;
          try {
            if ((await readFile(path, "utf8").catch(() => null)) === token) {
              await rm(path, { force: true });
            }
          } catch {
            // best-effort lock cleanup
          }
        };
      } catch (error) {
        if (error?.code !== "EEXIST") throw error;
      }
      const existing = await readFile(path, "utf8").catch(() => "");
      const owner = Number.parseInt(existing, 10);
      if (Number.isInteger(owner) && owner > 0 && !isProcessAlive(owner)) {
        await rm(path, { force: true }).catch(() => {});
        continue;
      }
      break;
    }
    return null;
  };

  for (;;) {
    if (shouldStop?.()) return async () => {};
    abortSignal?.throwIfAborted();

    const avail = getAvailableMemoryBytes();
    const memoryOk = avail == null || minAvailableBytes <= 0 || avail >= minAvailableBytes;

    for (let i = 0; i < effectiveMaxSlots; i++) {
      if (i > 0 && !memoryOk) break;
      const release = await tryClaimSlot(i);
      if (release) return release;
    }

    if (shouldStop?.()) return async () => {};
    abortSignal?.throwIfAborted();

    await new Promise((resolve) => {
      let timer;
      const onAbort = () => {
        clearTimeout(timer);
        resolve();
      };
      timer = setTimeout(() => {
        abortSignal?.removeEventListener?.("abort", onAbort);
        resolve();
      }, pollMs);
      abortSignal?.addEventListener?.("abort", onAbort, { once: true });
    });
  }
}
