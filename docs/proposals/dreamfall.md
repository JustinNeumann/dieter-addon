# Proposal for Dieter: Dreamfall, a funnel of agent stages that ends at a person's pick

Status: draft from dieter-addon-kotlin, for discussion upstream (dbpprt/dieter); not posted. Dreamfall runs in the
Home Assistant add-on (JustinNeumann/dieter-addon) since 0.6.429.42 on Dieter as it is, without daemon changes. This
note proposes what would make it a Dieter feature, with this repository's Compose screens as the reference client.

## What it is

A Dreamfall board ties ordinary boards of one project into ordered stages:

1. **Enumeration**: an agent writes new ideas per category on a schedule (e.g. nightly at 02:00).
2. **Navigation**: a second agent judges them (promote with a rationale and a score, or release).
3. **Implementation**: only a person picks what gets built: a copy of the idea as a Todo draft (own worktree or
   the project folder, started now or later). No agent creates or moves cards there.

Each idea keeps its provenance (which run wrote it, which stage it came from) and a trail of every transfer,
verdict, pick and run. The stage boards stay normal Kanban boards.

## How it works today (no daemon change)

- Pipeline, idea and run records in `dieter kv`, namespace `dreamfall`; the add-on's bridge follows them with one
  `dieter kv watch`.
- Stage runs are Dieter schedules with prompt templates that call a `dreamfall` command to record ideas, verdicts
  and runs. A transfer is idempotent: the copy's brief names its source.
- Clients: the add-on's classic page and the Compose screens here (`web/…/addon/Dreamfall*.kt`), drawn from slices
  of the add-on core (`addon/…/core/Dreamfall.kt`, protocol `proto/dieter/addon/v1/addon.proto`): list, board with
  funnel and stages (tabs on phones), idea page with brief, provenance and trail, settings, decisions.

## What upstream support would add

1. **Stage permissions.** A per-board "agents may not create or move cards here" flag in the daemon. Today the
   Implementation stage is protected only by convention (no schedule points there, the prompts say so, the trail
   shows any breach).
2. **Schedule ordering.** "Run Navigation after Enumeration's run finished" as a schedule dependency, instead of
   fixed times plus a check in the prompt.
3. **Card provenance.** A `source_card_id` (and the creating run) on a card, so a copy's origin is data, not a
   marker in its brief.
4. **A home for the screens.** With the UI extension points from `web-client.md` (item 5), Dreamfall stays a
   host feature: the board renderer claims stage boards, a Tools page lists Dreamfall boards. If upstream wants it
   in the apps, the rules (`Dreamfall.kt`, `AddonRules.dreamfallProblem`) move into the shared core and the screens
   into `apps/kotlin/ui` unchanged, since they only draw slices and send commands.

## Open questions for upstream

- Is a funnel of stages a Dieter concept (every client) or a host feature behind the extension points?
- Should ideas be cards at all, or a lighter record that becomes a card only when picked?
