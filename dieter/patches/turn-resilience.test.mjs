import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  acquireTurnSlot,
  availableMemoryBytes,
  defaultMaxSlots,
  guardWorkerStdout,
  raiseOomScoreAdj,
} from "./turn-resilience.mjs";

test("turn-resilience: raiseOomScoreAdj raises a lower score and leaves a higher score untouched", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "dieter-oom-adj-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(join(root, "self"));
  const file = join(root, "self", "oom_score_adj");
  await writeFile(file, "200\n");

  assert.equal(raiseOomScoreAdj(500, "self", root), true);
  assert.equal((await readFile(file, "utf8")).trim(), "500");

  assert.equal(raiseOomScoreAdj(300, "self", root), false);
  assert.equal((await readFile(file, "utf8")).trim(), "500");
});

test("turn-resilience: guardWorkerStdout catches EPIPE and parent reparenting and notifies once", () => {
  const stream = new EventEmitter();
  let ppid = 4242;
  const guard = guardWorkerStdout({
    stream,
    initialPpid: 4242,
    currentPpid: () => ppid,
  });
  let disconnects = 0;
  guard.onDisconnect(() => {
    disconnects += 1;
  });

  assert.equal(guard.isBroken(), false);
  assert.equal(guard.checkParent(), false);
  assert.equal(disconnects, 0);

  const epipe = Object.assign(new Error("write EPIPE"), { code: "EPIPE" });
  stream.emit("error", epipe);
  assert.equal(guard.isBroken(), true);
  assert.equal(disconnects, 1);

  // Subsequent close or parent-gone checks do not fire duplicate disconnect notifications
  stream.emit("close");
  ppid = 1;
  assert.equal(guard.checkParent(), true);
  assert.equal(disconnects, 1);
});

test("turn-resilience: guardWorkerStdout detects when parent daemon exits and worker is reparented to init", () => {
  const stream = new EventEmitter();
  let ppid = 1000;
  const guard = guardWorkerStdout({
    stream,
    initialPpid: 1000,
    currentPpid: () => ppid,
  });
  let disconnected = false;
  guard.onDisconnect(() => {
    disconnected = true;
  });

  ppid = 1;
  assert.equal(guard.checkParent(), true);
  assert.equal(guard.isBroken(), true);
  assert.equal(disconnected, true);
});

test("turn-resilience: acquireTurnSlot limits concurrent active turns and reclaims dead owner locks", async (t) => {
  const slotDir = await mkdtemp(join(tmpdir(), "dieter-turn-slots-"));
  t.after(() => rm(slotDir, { recursive: true, force: true }));

  // Seed slot-0 with a dead PID lock: should be reclaimed automatically
  await writeFile(join(slotDir, "slot-0.lock"), "2147483646 stale-token\n");

  const order = [];
  const release1 = await acquireTurnSlot({
    slotDir,
    maxSlots: 2,
    minAvailableBytes: 0,
    pollMs: 20,
  });
  order.push("turn1-acquired");

  const release2 = await acquireTurnSlot({
    slotDir,
    maxSlots: 2,
    minAvailableBytes: 0,
    pollMs: 20,
  });
  order.push("turn2-acquired");

  let turn3Acquired = false;
  const turn3Promise = acquireTurnSlot({
    slotDir,
    maxSlots: 2,
    minAvailableBytes: 0,
    pollMs: 20,
  }).then((release) => {
    turn3Acquired = true;
    order.push("turn3-acquired");
    return release;
  });

  await new Promise((r) => setTimeout(r, 60));
  assert.equal(turn3Acquired, false, "3rd turn waits while both slots are held");

  await release1();
  const release3 = await turn3Promise;
  assert.equal(turn3Acquired, true, "3rd turn acquires slot as soon as 1st releases");
  assert.deepEqual(order, ["turn1-acquired", "turn2-acquired", "turn3-acquired"]);

  await release2();
  await release3();
});

test("turn-resilience: acquireTurnSlot always admits slot 0 under low memory and holds slot 1+ until memory recovers", async (t) => {
  const slotDir = await mkdtemp(join(tmpdir(), "dieter-turn-mem-"));
  t.after(() => rm(slotDir, { recursive: true, force: true }));

  let availBytes = 100 * 1024 * 1024; // 100 MiB available (below 500 MiB threshold)
  const minAvailableBytes = 500 * 1024 * 1024;

  // Slot 0 is admitted even when memory is below minAvailableBytes so the system cannot deadlock
  const release0 = await acquireTurnSlot({
    slotDir,
    maxSlots: 3,
    minAvailableBytes,
    getAvailableMemoryBytes: () => availBytes,
    pollMs: 20,
  });

  // Slot 1 waits while available memory is below threshold
  let secondAdmitted = false;
  const secondPromise = acquireTurnSlot({
    slotDir,
    maxSlots: 3,
    minAvailableBytes,
    getAvailableMemoryBytes: () => availBytes,
    pollMs: 20,
  }).then((release) => {
    secondAdmitted = true;
    return release;
  });

  await new Promise((r) => setTimeout(r, 60));
  assert.equal(secondAdmitted, false);

  // Memory recovers above threshold -> slot 1 is admitted
  availBytes = 800 * 1024 * 1024;
  const release1 = await secondPromise;
  assert.equal(secondAdmitted, true);

  await release0();
  await release1();
});

test("turn-resilience: acquireTurnSlot aborts immediately when abortSignal fires while waiting", async (t) => {
  const slotDir = await mkdtemp(join(tmpdir(), "dieter-turn-abort-"));
  t.after(() => rm(slotDir, { recursive: true, force: true }));

  const release0 = await acquireTurnSlot({
    slotDir,
    maxSlots: 1,
    minAvailableBytes: 0,
    pollMs: 500,
  });

  const controller = new AbortController();
  setTimeout(() => controller.abort(new Error("turn cancelled while queued")), 30);

  await assert.rejects(
    acquireTurnSlot({
      slotDir,
      maxSlots: 1,
      minAvailableBytes: 0,
      pollMs: 500,
      abortSignal: controller.signal,
    }),
    /turn cancelled while queued/,
  );

  await release0();
});

test("turn-resilience: defaultMaxSlots and availableMemoryBytes parse system capacity", async (t) => {
  assert.equal(defaultMaxSlots(4 * 1024 ** 3), 2);
  assert.equal(defaultMaxSlots(8 * 1024 ** 3), 4);
  assert.equal(defaultMaxSlots(16 * 1024 ** 3), 6);

  const root = await mkdtemp(join(tmpdir(), "dieter-meminfo-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const meminfo = join(root, "meminfo");
  await writeFile(meminfo, "MemTotal:        8253440 kB\nMemFree:          512000 kB\nMemAvailable:    2048000 kB\n");
  assert.equal(availableMemoryBytes(meminfo), 2048000 * 1024);
});
