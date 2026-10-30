# Dreamfall: a multi-stage idea pipeline on Dieter boards

Status: implemented in add-on 0.6.429.42 (`dieter/bridge/dreamfall.py`, the `dreamfall` command, and a Dreamfall
view of the classic page, which 0.6.431.9 replaced with the Compose screens `web/…/addon/Dreamfall*.kt`; since
0.6.431.19 the pipelines, the actions and the command are the operator's `Dreamfall.kt` and `DreamfallCommand.kt`); user
documentation in `dieter/DOCS.md` → Dreamfall boards. This note keeps the design reasoning; the screenshots of the
mockup (`board-*.png`, `phone-*.png`, `page-*.png`) predate the implementation, the mockup itself is in the history.

## Answer to the two open choices

**Several connected boards, one view.** Each stage stays an ordinary Dieter board (its own lanes,
labels, schedule, prompt template, publishing mode). A small *pipeline* record ties them together in
order, and the add-on page shows that pipeline as one "Dreamfall" board: the funnel on top, the stage
boards side by side below it (tabs on a phone). Transfer is a copy to the next board that remembers its
source; release is a lane move inside the stage board. The stage boards can still be opened as normal
Kanban boards.

Why not a single board with custom lanes:

- Dieter's lanes are fixed per workflow: `Todo, Running, [Review], Done` (`internal/model/model.go`,
  `WorkflowLanes`). A single board would need configurable lanes and per-lane permissions in the daemon,
  which is an upstream change to the Go daemon; the add-on only ships a bridge, a page and patches.
- Schedules, prompt templates, labels, publishing mode and retention are all per board. With one board per
  stage, "the Navigation agent only writes to Navigation" is a property of the schedule, and "no agent
  touches Implementation" is simply "no schedule and no agent instruction points there".
- The running prototype in project `sem2_ha` already works this way (boards `Enumeration` and
  `Navigation`, schedules at 02:00 and 05:00 Europe/Berlin). Dreamfall formalises it instead of
  replacing it.
- A plain Kanban board keeps working for everything else in the project, and a project may hold several
  pipelines.

## What the view shows (see the mockup)

1. **Funnel strip**: one chip per stage with its actor (agent or human), schedule, last run and the count
   of cards waiting in that stage; the arrows carry the pass rate ("1 in 4") or "you" for the human gate.
2. **Stage columns**: the stage's *active queue* (its Todo lane) as cards, with a run summary line on top.
   Below the queue, collapsible groups for cards that left the queue: *Released* (Enumeration: not
   promoted, with the verdict), *Parked* and *Picked* (Navigation), *Done* (Implementation).
3. **Cards** carry: category label, title, summary, verdict chip with the agent's one-sentence rationale,
   provenance pill ("from Enumeration · c_442a2485"), who created it (run or person).
4. **Human gate**: the Implementation column is marked with a lock, a dashed border and the sentence
   "agents never create or move cards here". Navigation cards get a **Pick** button; nothing else moves a
   card into Implementation.
5. **Card detail** gains a *Provenance* group (the chain of copies across stages, each row opens that
   card) and a *Trail* timeline (created, judged with rationale and score, transferred, picked with the
   human's note, started).

## How the eight mechanisms map onto Dieter as it is

| Brief | Dieter today | Dreamfall adds |
|---|---|---|
| Boards as ordered stages | boards exist, no order or relation | pipeline record (below) |
| Transfer = copy with provenance | `dieter card create` on the target board; provenance as text in the brief | a provenance record per card, rendered as pills, Provenance group and the source's "Transferred to" verdict |
| Release = leave the queue, keep the card | `dieter card move --lane review|done` | the view groups released cards under the queue with their verdict |
| Lanes per board | fixed `Todo, Running, [Review], Done` | the *meaning* of lanes per stage (queue = Todo, released = Review/Done) lives in the pipeline record, not in new lanes |
| Actor permissions per stage | none; agents can do anything the CLI allows | stage 3 has no schedule; the board prompt template (`dieter prompt board`) tells agents to stay out; the page offers Pick only to the human. Hard enforcement needs a daemon change (see Limits) |
| Scheduled runs with ordering | cron per schedule, no dependencies | stage 2's prompt checks that stage 1's run card is Done before judging; the view shows "waits for Enumeration". A real dependency is a daemon feature |
| Audit trail | card transcript, lane moves with timestamps | the Trail: every transfer, verdict, pick and run written as events (below) |
| Idempotency | none | transfer key = source card id: a copy exists for a source only once; reruns skip sources already marked transferred |

## Data model (no daemon change needed)

Stored in `dieter kv`, namespace `dreamfall` (account-wide replicated JSON; keys allow letters, digits, `. _ -`):

```
p.<pipeline id>        { id, name, project_id, goal, categories[], categories_file, per_category, navigation_logic, timezone,
                         enabled, mail, agent{provider,model,effort},
                         stages: [{ role, name, board_id, actor: agent|human, cron, run_title, old_run_titles[],
                                    schedule_id }] }
c.<card id>            { pipeline, stage, category, source, transferred_to, created_by{actor,name,run},
                         verdict{kind: promote|release|pick|park, rationale, score, actor, name, run, at},
                         events: [{ id, kind: created|judged|released|parked|transferred|picked|restored|started,
                                    actor, name, run, at, rationale|note, score, to, from }] }   (last 80 events)
r.<run card id>        { pipeline, stage, card, status: running|done|failed, started_at, finished_at, counts, note }
l.<pipeline>.<role>    the latest run of a stage (Navigation's `run begin` waits while Enumeration's is running)
```

The trail lives inline in the card record (one key per card, concurrent siblings are merged by event id), which keeps
the key count low: `kv list` pages hold 64 entries and its cursor breaks while the account writes, so the bridge
follows the namespace with one `dieter kv watch` instead. Categories travel in the title (`[Category] Title`) and in
the record; run cards are told from ideas by their schedule's run title. A copy's brief ends with
`<!-- dreamfall-source: <id> -->`, which makes a transfer idempotent even when the agent crashed before writing the
record.

## Limits worth knowing before building

- **Enforcement is by convention.** Nothing in the daemon stops an agent from running `dieter card create`
  on the Implementation board. The view, the prompts and the absence of a schedule make it very unlikely,
  and the Trail makes it visible. A real guard (per-board "agents may not write") is a feature request to
  upstream Dieter.
- **Lanes stay the daemon's four.** "Review" on Enumeration is the natural "released, not promoted" lane;
  "Done" marks "transferred". The view labels them by meaning so nobody has to remember that.
- **Ordering between schedules is time-based** plus a check in the Navigation prompt. If Enumeration is
  still running at 05:00, Navigation should wait or skip; the view shows which.
- **kv is account-scoped**, so pipeline records are visible on every enrolled machine, like the cards.

## Steps (as planned before building; all done except the migration of `sem2_ha`)

1. Pipeline record and a `New board → Dreamfall pipeline` sheet in the page that creates the three
   stage boards, the labels from the categories file and the two schedules (prompts from `sem2_ha`,
   generalised).
2. Dreamfall view in the classic page's `app.js` (funnel, stage columns, phone tabs) over the existing board data.
3. Provenance and Trail on the card screen; Pick button (copy to Implementation as Todo, record the pick).
4. Stage prompt templates that write the card records and events; migration of the existing `sem2_ha`
   cards (their briefs already contain the "Triage" and "Found in run" lines to parse).
5. e2e test with the mock harness: three boards, a fake run, a pick, the Trail.
