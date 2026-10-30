// Questions the agent asks the user (Claude Code's AskUserQuestion, surfaced by the harness as the
// reserved `askUserQuestions` tool) pause the turn until the host supplies the tool result. Dieter's
// daemon has no channel for that, so the worker relays the question to an HTTP endpoint named by
// DIETER_QUESTIONS_URL (the Home Assistant add-on's bridge shows it on the page) and polls for the
// answer. Without an endpoint the questions are declined at once, which tells the agent to ask in
// plain text instead of hanging the turn.
//
// Protocol (JSON): POST {url}            {id, session, input}  registers a question; the host may answer
//                                        {token}, a secret the worker then sends with every poll
//                                        (header x-question-token), so only the worker that asked can
//                                        read the answer, though others may learn the question's id
//                  GET  {url}/{id}        {done: false} | {done: true, output}
// where input is the harness questions tool input and output its tool output
// ({action: "answered" | "partially-answered", answers: {questionId: {optionIds, freeform?}}} or
// {action: "declined" | "cancelled"}).

export const QUESTIONS_TOOL = "askUserQuestions";
const OUTPUT_ACTIONS = new Set(["answered", "partially-answered", "declined", "cancelled"]);

export function declinedQuestions() {
  return { action: "declined" };
}

export function isQuestionsOutput(value) {
  if (!value || typeof value !== "object" || !OUTPUT_ACTIONS.has(value.action)) return false;
  if (value.action === "declined" || value.action === "cancelled") return true;
  return !!value.answers && typeof value.answers === "object";
}

const sleep = (ms, signal) =>
  new Promise((resolve) => {
    const timer = setTimeout(done, ms);
    function done() {
      clearTimeout(timer);
      signal?.removeEventListener("abort", done);
      resolve();
    }
    signal?.addEventListener("abort", done, { once: true });
  });

/**
 * Creates the relay. `url` empty → every question is declined. `ask(id, input)` resolves with the
 * tool output once the host answered, or with a cancelled output when `signal` aborts.
 */
export function createQuestionRelay({
  url,
  sessionId,
  signal,
  fetchImpl = globalThis.fetch,
  pollMs = 1500,
  log = () => {},
}) {
  const base = (url || "").trim().replace(/\/+$/, "");
  if (!base) {
    return {
      enabled: false,
      ask: async () => declinedQuestions(),
    };
  }
  const request = async (path, init) => {
    const response = await fetchImpl(`${base}${path}`, { ...init, signal });
    if (!response.ok)
      throw new Error(`${init?.method || "GET"} ${base}${path}: HTTP ${response.status}`);
    return response.json();
  };
  return {
    enabled: true,
    async ask(id, input) {
      if (typeof id !== "string" || !/^[A-Za-z0-9_.:-]{1,128}$/.test(id))
        throw new Error("invalid question id");
      const registered = await request("", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ id, session: sessionId, input }),
      });
      const token = typeof registered?.token === "string" ? registered.token : "";
      const poll = token ? { headers: { "x-question-token": token } } : undefined;
      log(`question ${id} waits for the user`);
      let failures = 0;
      while (!signal?.aborted) {
        await sleep(pollMs, signal);
        if (signal?.aborted) break;
        let state;
        try {
          state = await request(`/${encodeURIComponent(id)}`, poll);
          failures = 0;
        } catch (error) {
          if (signal?.aborted) break;
          failures += 1;
          if (failures === 1 || failures % 40 === 0)
            log(`question ${id}: poll failed (${error.message})`);
          continue;
        }
        if (state && state.done) {
          if (!isQuestionsOutput(state.output))
            throw new Error(`question ${id}: invalid answer from the host`);
          return state.output;
        }
      }
      return { action: "cancelled" };
    },
  };
}

/** The tool-result continuation the harness expects for an answered question. */
export function questionToolResult(toolCallId, output) {
  return {
    type: "tool-result",
    toolCallId,
    toolName: QUESTIONS_TOOL,
    output: { type: "json", value: output },
  };
}
