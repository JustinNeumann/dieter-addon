/* Dieter for Home Assistant: the Android app's screens (apps/kotlin/ui, Compose Material 3) rebuilt
   in plain JS on top of the bridge API (dieter_bridge.py). Navigation follows MobileApp.kt:
   six tabs with a route stack each, a navigation rail from 600 px, list + detail panes from 840 px.
   All API and asset URLs are relative: behind Home Assistant Ingress the page lives under
   /api/hassio_ingress/<token>/ (the trailing slash matters, hence the guard below). */
(() => {
  "use strict";
  if (!location.pathname.endsWith("/") && !/\.html?$/.test(location.pathname)) { location.replace(location.pathname + "/" + location.search + location.hash); return; }
  const $ = (s, r = document) => r.querySelector(s);
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const ico = (name, size = 24, cls = "") => `<span class="ms s${size} ${cls}">${name}</span>`;
  const q = new URLSearchParams(location.search);
  const EMBED = q.get("embed") === "1";

  // ------------------------------------------------------------------ constants (Navigation.kt, Lanes, Labels)
  const TABS = [
    ["inbox", "Inbox", "inbox"], ["projects", "Projects", "folder"], ["chats", "Chats", "forum"],
    ["mail", "Mail", "mail"], ["tools", "Tools", "grid_view"], ["channels", "Channels", "tag"],
  ];
  const EMPTY_DETAIL = {
    inbox: ["inbox", "No conversation selected", "Choose an item from your inbox."],
    projects: ["view_kanban", "No task selected", "Open a board and choose a task."],
    chats: ["forum", "No chat selected", "Choose a chat or start a new one."],
    mail: ["mail", "No message selected", "Choose a message to read it."],
    channels: ["tag", "No channel selected", "Choose a channel to read what its agents tell each other."],
    tools: ["grid_view", "No tool selected", "Choose a tool to open it here."],
  };
  const LANES = { todo: "Todo", running: "Running", review: "Review", done: "Done" };
  const TOOL_PAGES = {
    machines: ["Machines", "computer", "#0A84FF"], terminals: ["Terminals", "terminal", "#3A3A3C"], screens: ["Screens", "desktop_windows", "#5E5CE6"],
    files: ["Files", "folder", "#0A9EF5"], changes: ["Changes", "difference", "#FF9F0A"], schedules: ["Schedules", "calendar_month", "#FF453A"],
    usage: ["Usage", "speed", "#30B158"], devices: ["Devices", "smartphone", "#32ADE6"], settings: ["Settings", "settings", "#8E8E93"],
  };
  const PALETTE = ["#d95c68", "#df7650", "#c9952f", "#7d9e45", "#3e9970", "#379799", "#478dc5", "#626fd0", "#8a62c3", "#c65f98"];
  function javaHash(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0; return h; }
  const stableColor = (id) => PALETTE[((javaHash(id || "") % 10) + 10) % 10];
  const HARNESS_NAMES = { "claude-code": "Claude Code", codex: "Codex", pi: "Pi", omp: "Oh My Pi", dsh: "DeepSeek Harness" };

  // ------------------------------------------------------------------ state
  const S = {
    data: null, tab: "projects", stacks: {}, modal: null, dialog: null, menu: null,
    conv: { id: null, kind: null, data: null, es: null, draft: "", following: true, expanded: {}, agent: null, files: [] },
    qa: {},        // answers being composed for the agents' questions, by question id
    files: [],     // attachments picked for the New task/chat form
    seen: load("seen", {}), pins: load("pins", {}), lanePage: {}, search: {}, inboxFilter: "all", collapsed: load("collapsed", {}),
    boardSort: {}, creation: null, busy: false, scroll: {},
  };
  TABS.forEach(([t]) => (S.stacks[t] = [{ kind: "root", tab: t }]));
  function load(k, d) { try { return JSON.parse(localStorage.getItem("dieter-" + k)) ?? d; } catch (e) { return d; } }
  function save(k, v) { try { localStorage.setItem("dieter-" + k, JSON.stringify(v)); } catch (e) { /* storage blocked */ } }
  // most recently used project: first in the list, preselected for new tasks and when the Projects tab is tapped
  const mruProject = () => { const id = load("mru-project", ""); return (S.data?.projects || []).find((p) => p.id === id) || null; };
  const touchProject = (id) => { if (id && load("mru-project", "") !== id) save("mru-project", id); };

  // theme: the app follows the system unless overridden (?theme=dark|light)
  function applyTheme() {
    const pref = q.get("theme") || load("theme", "system");
    const dark = pref === "dark" || (pref !== "light" && matchMedia("(prefers-color-scheme: dark)").matches);
    document.documentElement.dataset.theme = dark ? "dark" : "light";
  }
  applyTheme();
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", applyTheme);

  // ------------------------------------------------------------------ formatting (Ages, CardAges, Counts, TokenCounts, Runtimes, Activity)
  const now = () => Date.now();
  function spanOf(ms, weeks = false) {
    const s = Math.floor(ms / 1000);
    if (s < 60) return null;
    if (s < 3600) return [Math.floor(s / 60), "m"];
    if (s < 86400) return [Math.floor(s / 3600), "h"];
    if (!weeks || s < 604800) return [Math.floor(s / 86400), "d"];
    return [Math.floor(s / 604800), "w"];
  }
  function cardAge(c) {
    const at = Math.max(Date.parse(c.updated_at || 0) || 0, Date.parse(c.last_activity_at || 0) || 0);
    if (!at) return "";
    const sp = spanOf(now() - at, true);
    if (!sp) return "now";
    return sp[1] === "m" ? `${sp[0]}min` : `${sp[0]}${sp[1]}`;
  }
  function activityAge(iso) {
    const at = Date.parse(iso || 0);
    if (!at) return "Time unavailable";
    const sp = spanOf(now() - at);
    return sp ? `${sp[0]}${sp[1]}` : "Just now";
  }
  const counts = (n, noun, plural = noun + "s") => `${n} ${n === 1 ? noun : plural}`;
  function compactTokens(v) {
    if (v >= 1e6) return (Math.round(v / 1e5) / 10).toFixed(1) + "M";
    if (v >= 1e5) return Math.round(v / 1e3) + "k";
    if (v >= 1e3) return (Math.round(v / 100) / 10).toFixed(1) + "k";
    return String(v);
  }
  const ACTIVE = new Set(["starting", "running", "working", "streaming", "active"]);
  const NEEDS = new Set(["waiting", "waiting_for_user", "needs_input"]);
  const FAILED = new Set(["failed", "error"]);
  function rtState(r) { r = (r || "").trim().toLowerCase(); if (r === "cancelling") return "stopping"; if (ACTIVE.has(r)) return "active"; if (NEEDS.has(r)) return "needs"; if (FAILED.has(r)) return "failed"; return "idle"; }
  const isActive = (r) => ["active", "stopping"].includes(rtState(r));
  function rtBadge(r) { const v = (r || "").trim().toLowerCase(); if (v === "starting") return "Starting…"; if (rtState(v) === "stopping") return "Stopping…"; if (rtState(v) === "active") return "Running"; return null; }
  function rtTone(r) { const v = (r || "").trim().toLowerCase(); const st = rtState(v); if (st === "active" || st === "stopping") return "active"; if (st === "needs") return "attention"; if (st === "failed") return "failed"; if (["completed", "done"].includes(v)) return "done"; if (["cancelled", "canceled"].includes(v)) return "failed"; return "idle"; }
  function rtLabel(r) { const b = rtBadge(r); if (b) return b; const st = rtState(r); if (st === "needs") return "Waiting for you"; if (st === "failed") return "Failed"; const v = (r || "").trim(); return v ? v.replace(/_/g, " ").toLowerCase().replace(/^./, (c) => c.toUpperCase()) : "Idle"; }
  const toneColor = (t) => ({ active: "var(--info)", attention: "var(--warning)", failed: "var(--destructive)", done: "var(--success)" }[t] || "var(--secondary-label)");
  const cardTitle = (c) => c.title || (c.kind === "chat" ? "Untitled chat" : "Untitled card");
  const agentLine = (c) => [c.provider || "agent", c.model].filter(Boolean).join(" · ");
  const tokensLabel = (c) => (c.tokens && c.tokens.totalTokens > 0 ? compactTokens(c.tokens.totalTokens) + " tokens" : null);
  function isUnread(c) {
    if (c.response_seq != null && c.seen_response_seq != null) return Number(c.response_seq) > Number(c.seen_response_seq);
    return !!c.last_activity_at && rtState(c.runtime) === "idle" && S.seen[c.id] !== c.last_activity_at && !!c.initial_prompt_sent;
  }
  function workspaceBadge(c) { if (c.branch) return c.branch; return c.workspace_mode === "worktree" ? "Worktree" : "Project"; }
  function cardMachine(c) { const m = (S.data.machines || []).find((x) => x.id === c.owner_daemon_id); return m ? m.name : null; }
  function project(id) { return (S.data?.projects || []).find((p) => p.id === id); }
  function board(id) { return (S.data?.boards || []).find((b) => b.id === id); }
  function allConvs() { return [...(S.data?.cards || []), ...(S.data?.chats || [])]; }
  function conv(id) { return allConvs().find((c) => c.id === id); }

  // Activity.classify / detail
  function classify(c) {
    const st = rtState(c.runtime);
    if (st === "needs" || (S.data.questions || []).some((q) => q.session === c.id)) return "ANSWER";
    if (st === "active" || st === "stopping") return "RUNNING";
    if (isUnread(c)) return "UNREAD";
    if (c.kind !== "chat" && c.lane === "review") return "REVIEW";
    if (st === "failed") return "FAILED";
    if ((c.runtime || "").trim() && c.runtime !== "pending" && c.initial_prompt_sent && c.last_activity_at) return "RECENT";
    return null;
  }
  function detailOf(c, kind) {
    const r = (c.runtime || "").trim().toLowerCase();
    if (r === "cancelling") return "Stopping…";
    if (kind === "RUNNING") return c.live || c.summary || "Working on your request";
    if (kind === "UNREAD") return "New reply";
    if (kind === "ANSWER") return "Waiting for your answer";
    if (kind === "REVIEW") return "Ready for review";
    if (kind === "FAILED") return "Agent failed";
    if (["cancelled", "canceled", "stopped", "interrupted"].includes(r)) return "Stopped";
    return c.kind === "chat" ? "Replied" : "Finished";
  }
  function activityItems() {
    return allConvs().filter((c) => !c.archived).map((c) => {
      const kind = classify(c); if (!kind) return null;
      const needsYou = kind === "ANSWER" || kind === "UNREAD";
      return { card: c, kind, needsYou, running: kind === "RUNNING", detail: detailOf(c, kind), at: c.last_activity_at,
        section: needsYou ? "attention" : kind === "RUNNING" ? "running" : "recent", chat: c.kind === "chat",
        projectName: project(c.project_id)?.name, boardName: c.kind === "chat" ? null : board(c.board_id)?.name,
        canFinish: c.kind !== "chat" && c.lane === "review" && kind !== "RUNNING" && kind !== "ANSWER", pinned: !!S.pins[c.id] };
    }).filter(Boolean).sort((a, b) => (Date.parse(b.at || 0) || 0) - (Date.parse(a.at || 0) || 0));
  }

  // ------------------------------------------------------------------ api
  async function api(path, body, method) {
    const r = await fetch(path, body !== undefined ? { method: method || "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) } : undefined);
    const j = await r.json().catch(() => ({ error: r.statusText }));
    if (!r.ok) throw new Error(j.error || r.statusText);
    return j;
  }
  const SNAP = q.get("snap") === "1"; // screenshots: load once, no streams
  function connect() {
    if (SNAP) { api("api/state").then((d) => { S.data = d; render(); }); return; }
    if (S.es) S.es.close();
    S.es = new EventSource("api/events");
    S.es.addEventListener("state", (e) => { S.data = JSON.parse(e.data); render(); });
  }

  // ------------------------------------------------------------------ navigation (MobileNavigation)
  const isList = (r) => ["root", "project", "board"].includes(r.kind);
  const isImmersive = (r) => ["conversation", "pane", "terminal"].includes(r.kind);
  const stack = () => S.stacks[S.tab];
  const top = () => stack()[stack().length - 1];
  function push(route) { S.stacks[S.tab] = [...stack(), route]; render(); }
  function pop() { if (stack().length > 1) { S.stacks[S.tab] = stack().slice(0, -1); render(); } }
  function showFrom(base, route) { // store.showFrom: stack = [root, base?, route]
    const root = { kind: "root", tab: S.tab };
    S.stacks[S.tab] = base && base.kind !== "root" ? [root, base, route] : [root, route];
    render();
  }
  function selectTab(t) {
    if (S.tab === t) { S.stacks[t] = [{ kind: "root", tab: t }]; }
    if (t === "projects" && (S.stacks[t] || []).length <= 1) { const m = mruProject(); if (m) S.stacks[t] = [{ kind: "root", tab: t }, { kind: "project", id: m.id }]; }
    S.tab = t; render();
  }
  function openConversation(id) {
    const c = conv(id); const kind = c?.kind === "chat" ? "chat" : "card"; touchProject(c?.project_id);
    const st = stack(); const listIdx = st.map(isList).lastIndexOf(true);
    S.stacks[S.tab] = [...st.slice(0, listIdx + 1), { kind: "conversation", id, convKind: kind }];
    render();
  }
  const twoPane = () => innerWidth >= 840;
  const hasRail = () => innerWidth >= 600;

  // ------------------------------------------------------------------ rendering shell
  const shell = $("#shell");
  function render() {
    const st = S.data;
    // a live update must not steal the focus from a field being typed in (Android closes the keyboard)
    const active = document.activeElement;
    const keep = active && active.matches("input, textarea") && (active.id || active.dataset.field) ? { el: active, id: active.id, field: active.dataset.field, start: active.selectionStart, end: active.selectionEnd } : null;
    renderAll();
    if (keep && document.activeElement !== keep.el) {
      const n = (keep.id && document.getElementById(keep.id)) || (keep.field && document.querySelector(`[data-field="${keep.field}"]`));
      if (n) { n.focus({ preventScroll: true }); try { n.setSelectionRange(keep.start, keep.end); } catch (e) { /* not a text field */ } }
    }
  }
  function renderAll() {
    const st = S.data;
    document.body.classList.toggle("has-bottombar", !hasRail());
    const badges = tabBadges();
    const navItems = TABS.map(([t, title, icon]) => `<button class="nav-item ${S.tab === t ? "on" : ""}" data-act="tab" data-tab="${t}">
        <span class="ind">${ico(icon, 24, S.tab === t ? "fill" : "")}</span><span class="lbl">${title}</span>${badges[t] ? `<span class="badge">${badges[t]}</span>` : ""}</button>`).join("");
    const railHtml = `<button class="fab" data-act="new" title="${S.tab === "chats" ? "New chat" : "New task"}">${ico("edit", 24)}</button>${navItems}`;
    setHTML($("#rail"), railHtml);
    setHTML($("#bottombar"), navItems);
    const stk = stack();
    const listIdx = stk.map(isList).lastIndexOf(true);
    let listHtml, detailHtml = null;
    if (twoPane() && listIdx >= 0) {
      const list = stk[listIdx], details = stk.slice(listIdx + 1), detail = details[details.length - 1];
      listHtml = screen(list, { back: listIdx > 0, inList: true });
      detailHtml = detail ? screen(detail, { back: details.length > 1, inDetail: true }) : emptyDetail();
      document.body.classList.remove("immersive");
    } else {
      const r = top();
      listHtml = screen(r, { back: stk.length > 1 });
      document.body.classList.toggle("immersive", !twoPane() && isImmersive(r));
    }
    setHTML($("#list"), listHtml);
    setHTML($("#detail"), detailHtml || "");
    // The forms are diffed and their typed values live outside the markup: a live update must not
    // replace the field being typed in (Android closes the keyboard when the element is swapped).
    setHTML($("#modal"), S.modal === "repo" ? repoScreen() : S.modal ? creationScreen() : "");
    $("#modal").classList.toggle("hidden", !S.modal);
    const formState = S.modal === "repo" ? S.repo : S.creation;
    if (formState) $("#modal").querySelectorAll("[data-field]").forEach((f) => { const v = formState[f.dataset.field] ?? ""; if (f.value !== v) f.value = v; });
    if (S.sch?.form) document.querySelectorAll("[data-sfield]").forEach((f) => { const v = S.sch.form[f.dataset.sfield] ?? ""; if (f.value !== v) f.value = v; });
    if (S.dev) document.querySelectorAll("[data-dfield]").forEach((f) => { const v = S.dev[f.dataset.dfield] ?? ""; if (f.value !== v) f.value = v; });
    if (S.modal === "creation" && S.creation) { const c = S.creation; const ok = !!(c.prompt.trim() && c.project_id && (c.chat || c.board_id) && !S.busy); $("#modal").querySelectorAll('[data-act="submit-creation"]').forEach((b) => (b.disabled = !ok)); }
    if (S.modal === "repo" && S.repo) $("#modal").querySelectorAll('[data-act="submit-repo"]').forEach((b) => (b.disabled = !repoReady(S.repo) || S.busy));
    syncConversation();
    document.title = "Dieter";
  }
  function setHTML(el, html) {
    if (el._html === html) return;
    const scrolls = {}; el.querySelectorAll("[data-scroll]").forEach((s) => (scrolls[s.dataset.scroll] = s.scrollTop));
    el.innerHTML = html; el._html = html;
    el.querySelectorAll("[data-scroll]").forEach((s) => { if (scrolls[s.dataset.scroll] != null) s.scrollTop = scrolls[s.dataset.scroll]; });
    el.querySelectorAll(".content").forEach((c) => c.addEventListener("scroll", onScroll, { passive: true }));
  }
  function onScroll(e) {
    const c = e.currentTarget, bar = c.parentElement.querySelector(".appbar");
    if (!bar) return;
    bar.classList.toggle("scrolled", c.scrollTop > 2);
    if (bar.classList.contains("large")) bar.classList.toggle("collapsed", c.scrollTop > 34);
  }
  function tabBadges() {
    const items = activityItems(); const attention = items.filter((i) => i.needsYou).length;
    return { inbox: attention > 0 ? String(attention) : "" };
  }

  // ------------------------------------------------------------------ building blocks
  function appbar({ title, subtitle = "", large = false, back = false, cancel = false, actions = "", confirm = "" }) {
    const nav = cancel ? `<button class="iconbtn" data-act="cancel-modal">${ico("close")}</button>` : back ? `<button class="iconbtn" data-act="back">${ico("arrow_back")}</button>` : "";
    const sub = subtitle ? `<div class="subtitle ellipsis">${esc(subtitle)}</div>` : "";
    if (large) return `<div class="appbar large"><div class="row">${nav}<div class="ctitle ellipsis">${esc(title)}</div><div class="spacer"></div><div class="actions">${confirm}${actions}</div></div>
      <div class="big"><div class="title ellipsis">${esc(title)}</div>${sub}</div></div>`;
    return `<div class="appbar">${nav}<div class="titles ${nav ? "" : "noicon"}"><div class="title ellipsis">${esc(title)}</div>${sub}</div><div class="actions">${confirm}${actions}</div></div>`;
  }
  const iconAction = (icon, act, extra = "", title = "") => `<button class="iconbtn" data-act="${act}" ${extra} title="${esc(title)}">${ico(icon)}</button>`;
  const searchField = (key, placeholder) => `<div class="search">${ico("search")}<input data-search="${key}" value="${esc(S.search[key] || "")}" placeholder="${esc(placeholder)}">${S.search[key] ? `<button class="iconbtn small" data-act="clear-search" data-key="${key}">${ico("close", 20)}</button>` : ""}</div>`;
  const chip = (label, on, act, extra = "", lead = "") => `<button class="chip ${on ? "on" : ""}" data-act="${act}" ${extra}>${lead || (on ? ico("check", 18) : "")}<span>${esc(label)}</span></button>`;
  const sectionHeader = (text, { prominent = false, trailing = "" } = {}) => `<div class="section-header ${prominent ? "prominent" : ""}"><span class="txt ellipsis">${esc(text)}</span>${trailing}</div>`;
  const sectionFooter = (text) => `<div class="section-footer">${esc(text)}</div>`;
  const pos = (i, n) => (n <= 1 ? "single" : i === 0 ? "first" : i === n - 1 ? "last" : "middle");
  function listRow({ title, subtitle, value, glyph, tile, glyphTint, position = "single", act, extra = "", trailing = "", selected = false, disabled = false, leading = "" }) {
    let lead = "";
    if (leading) lead = leading;
    else if (tile && glyph) lead = `<span class="tile" style="background:color-mix(in srgb, var(--cell) ${document.documentElement.dataset.theme === "dark" ? 70 : 84}%, ${tile})"><span class="ms s22" style="color:${tile}">${glyph}</span></span>`;
    else if (glyph) lead = `<span class="lead"><span class="ms s24" style="${glyphTint ? "color:" + glyphTint : ""}">${glyph}</span></span>`;
    return `<div class="gitem ${position} ${act ? "clickable" : ""} ${selected ? "selected" : ""}" ${act ? `data-act="${act}" ${extra}` : ""}>
      <div class="lrow ${subtitle ? "sub" : ""} ${disabled ? "disabled" : ""}">${lead}<div class="main"><div class="title ellipsis">${esc(title)}</div>${subtitle ? `<div class="subtitle clamp2">${esc(subtitle)}</div>` : ""}</div>
      ${value != null && value !== "" ? `<div class="value ellipsis">${esc(value)}</div>` : ""}${trailing ? `<div class="trail">${trailing}</div>` : ""}</div></div>`;
  }
  const avatar = (id, name, size = 36) => `<span class="avatar" style="width:${size}px;height:${size}px;background:${stableColor(id)}">${esc((name || "?").trim().slice(0, 1).toUpperCase())}</span>`;
  const emptyState = (glyph, title, msg, action = "") => `<div class="empty"><div class="circle">${ico(glyph, 32)}</div><div class="title">${esc(title)}</div><div class="msg">${esc(msg)}</div>${action ? `<div class="act">${action}</div>` : ""}</div>`;
  const emptyDetail = () => { const [g, t, m] = EMPTY_DETAIL[S.tab]; return `<div class="screen"><div class="center-fill">${emptyState(g, t, m)}</div></div>`; };
  const progress = (frac, color) => `<div class="progress"><i style="width:${Math.round(Math.max(0, Math.min(1, frac)) * 100)}%;background:${color || "var(--accent)"}"></i></div>`;
  const countBadge = (n, color) => (n > 0 ? `<span class="count-badge" style="background:${color}">${n > 99 ? "99+" : n}</span>` : "");
  const fab = (label, act, inList) => (hasRail() ? "" : `<button class="fab-ext" data-act="${act}">${ico("edit")}<span class="lbl">${label}</span></button>`);
  const spinnerCenter = () => `<div class="center-fill"><div class="spinner"></div></div>`;

  // ------------------------------------------------------------------ screens
  function screen(route, opts) {
    if (!S.data) return `<div class="screen">${spinnerCenter()}</div>`;
    if (!S.data.ok && route.kind === "root") return `<div class="screen">${appbar({ title: TABS.find((t) => t[0] === S.tab)[1] })}<div class="content"><div class="banner warning" style="margin-top:8px">${ico("cloud_off", 22)}<div><div class="t">Bridge offline</div><div class="m">${esc(S.data.error || "")}</div></div></div></div></div>`;
    switch (route.kind) {
      case "root": return { inbox: inboxScreen, projects: projectsScreen, chats: chatsScreen, mail: mailScreen, tools: toolsScreen, channels: channelsScreen }[route.tab](opts);
      case "project": return projectScreen(route, opts);
      case "board": return boardScreen(route, opts);
      case "conversation": return conversationScreen(route, opts);
      case "pane": return paneScreen(route, opts);
      case "tool": return toolScreen(route, opts);
      case "machine": return machineScreen(route, opts);
    }
    return "";
  }

  // ---- Inbox (InboxScreen.kt)
  function inboxScreen(opts) {
    const items = activityItems();
    const query = (S.search.inbox || "").trim().toLowerCase();
    const scoped = items.filter((i) => !query || [cardTitle(i.card), i.projectName, i.boardName].some((x) => (x || "").toLowerCase().includes(query)));
    const match = (i, f) => f === "all" || (f === "needs" ? i.needsYou : f === "running" ? i.kind === "RUNNING" : i.kind === "REVIEW");
    const count = (f) => scoped.filter((i) => match(i, f)).length;
    const rows = scoped.filter((i) => match(i, S.inboxFilter));
    const running = items.filter((i) => i.running).length, attention = items.filter((i) => i.needsYou).length;
    const subtitle = attention > 0 && running > 0 ? `${attention} need you · ${running} running` : attention > 0 ? `${attention} need you` : running > 0 ? `${running} running` : "All caught up";
    const filters = [["all", "All"], ["needs", "Needs you"], ["running", "Running"], ["review", "Review"]].map(([f, t]) => chip(f === "all" || count(f) === 0 ? t : `${t} ${count(f)}`, S.inboxFilter === f, "inbox-filter", `data-filter="${f}"`)).join("");
    // one bar per configured LLM backend (Claude Code, Antigravity, OpenAI): the tightest window, named
    const quota = (S.data.quota || []).filter((a) => a.windows.length);
    const backends = (S.data.backends || []).length ? S.data.backends : quota.map((a) => ({ id: a.provider, name: providerName(a.provider) }));
    const usage = backends.length ? `<div class="usage-strip" data-act="tool" data-page="usage">${ico("speed", 20)}<div class="cols">${backends.slice(0, 3).map((b) => {
      const a = quota.find((q) => q.provider === b.id);
      if (!a) return `<div class="col"><div class="nm">${esc(b.name)}</div>${progress(0, "var(--outline)")}<div class="lf c-secondary">no quota data</div></div>`;
      const low = a.windows.reduce((m, w) => ((w.remaining ?? 100) < (m.remaining ?? 100) ? w : m), a.windows[0]);
      const r = low.remaining ?? 100;
      return `<div class="col"><div class="nm">${esc(b.name)}</div>${progress(r / 100, r < 20 ? "var(--warning)" : "var(--success)")}<div class="lf">${r}% left${a.windows.length > 1 ? ` <span class="c-secondary">· ${esc(String(low.label || "").replace(/ Limit Remaining| Limit/g, ""))}</span>` : ""}</div></div>`;
    }).join("")}</div></div>` : "";
    const sections = [["pinned", "Pinned"], ["attention", "Needs you"], ["running", "Running"], ["recent", "Recent"]].map(([sec, title]) => {
      const list = rows.filter((i) => (sec === "pinned" ? i.pinned : !i.pinned && i.section === sec));
      if (!list.length) return "";
      return sectionHeader(title, { prominent: true, trailing: `<span class="t-footnote c-secondary">${list.length}</span>` }) + list.map((i, idx) => inboxRow(i, idx === 0)).join("");
    }).join("");
    const empty = rows.length ? "" : emptyState(query ? "search" : "inbox", query ? "No results" : S.inboxFilter !== "all" ? "Nothing here" : "All quiet",
      query ? `No conversations match “${S.search.inbox}”.` : S.inboxFilter === "needs" ? "Nothing needs your attention right now." : "Running work, replies and reviews appear here.");
    return `<div class="screen">${appbar({ title: "Inbox", subtitle, back: opts.back, actions: iconAction("filter_list", "menu", `data-menu="inbox-filter"`, "Filter") })}
      <div class="content ${hasRail() ? "" : "fab-space"}" data-scroll="inbox">${searchField("inbox", "Search activity")}<div class="chips">${filters}</div>${usage}${empty}${sections}</div>${fab("New task", "new")}</div>`;
  }
  function inboxRow(i, first) {
    const c = i.card, sel = isSelected(c.id);
    const tint = i.kind === "FAILED" ? "var(--destructive)" : i.needsYou ? "var(--warning)" : i.kind === "RUNNING" ? "var(--info)" : i.kind === "REVIEW" ? "var(--purple)" : "var(--tertiary-label)";
    const active = i.kind !== "RECENT" && !sel;
    const badgeIcon = i.kind === "FAILED" ? "close" : i.needsYou ? "" : i.kind === "REVIEW" ? "visibility" : "check";
    const av = i.projectName ? avatar(c.project_id, i.projectName, 40) : `<span class="chatav">${ico("chat_bubble", 20)}</span>`;
    const ctx = [i.projectName, i.boardName, (S.data.machines || []).length > 1 ? cardMachine(c) : null].filter(Boolean).join(" · ");
    const det = i.kind === "RUNNING" ? `<div class="txt running clamp2">${esc(i.detail)}</div>`
      : active ? `<span class="spill">${ico(i.kind === "FAILED" ? "warning" : i.needsYou ? "chat_bubble" : i.kind === "REVIEW" ? "visibility" : "check", 14)}<span class="ellipsis">${esc(i.detail)}</span></span>`
      : `<div class="txt clamp2">${esc(i.detail)}</div>`;
    return `<div class="inbox-row ${first ? "first" : ""} ${active ? "active" : ""} ${sel ? "selected" : ""}" style="--tint:${tint};--status:${tint};--ring:${sel ? "var(--accent-container)" : "var(--cell)"}" data-act="open" data-id="${c.id}">
      <div class="in"><div class="av">${av}<span class="ring">${i.kind === "RUNNING" ? `<span class="livedot s14" style="color:${tint}"></span>` : `<span class="bd">${badgeIcon ? ico(badgeIcon, 9) : ""}</span>`}</span></div>
      <div class="main"><div class="hd"><div class="ttl clamp2 ${i.kind === "RECENT" ? "recent" : ""}">${esc(cardTitle(c))}</div><div class="age ${i.needsYou ? "need" : ""}">${activityAge(i.at)}</div>
        <button class="pin ${i.pinned ? "on" : ""}" data-act="pin" data-id="${c.id}" title="${i.pinned ? "Unpin from inbox" : "Pin in inbox"}">${ico("push_pin", 16)}</button></div>
      <div class="ctx">${i.chat ? ico("chat_bubble", 13) : ""}<span class="ellipsis">${esc(ctx)}</span></div>
      <div class="det">${det}${i.canFinish ? `<button class="finish-chip" data-act="finish" data-id="${c.id}">${ico("check", 15)}Mark done</button>` : ""}</div></div></div>
      ${i.kind === "RUNNING" ? `<div class="sweep"><i></i></div>` : ""}</div>`;
  }
  const providerName = (p) => ({ anthropic_claude: "Claude Code", antigravity: "Antigravity", openai_codex: "OpenAI", openai: "OpenAI", codex: "Codex" }[p] || p);
  function isSelected(id) { const st = stack(); return st.some((r) => r.kind === "conversation" && r.id === id); }

  // ---- Projects (ProjectsScreen.kt)
  function projectsScreen(opts) {
    const projects = S.data.projects || [];
    const query = (S.search.projects || "").trim().toLowerCase();
    const matches = (p) => !query || p.name.toLowerCase().includes(query) || (S.data.boards || []).some((b) => b.project_id === p.id && b.name.toLowerCase().includes(query));
    const mru = mruProject(); const list = projects.filter(matches).sort((a, b) => (b.id === mru?.id) - (a.id === mru?.id));
    const body = list.length ? list.map(projectGroup).join("") : projects.length ? emptyState("search", "No results", `No projects or boards match “${S.search.projects}”.`)
      : emptyState("create_new_folder", "No projects yet", "Add a Git repository with the + button; it is cloned on this machine.");
    return `<div class="screen">${appbar({ title: "Projects", subtitle: projects.length ? counts(projects.length, "project") : "", large: true, back: opts.back, actions: iconAction("add", "menu", `data-menu="projects-add"`, "Add") })}
      <div class="content ${hasRail() ? "" : "fab-space"}" data-scroll="projects">${searchField("projects", "Search projects and boards")}${body}</div>${fab("New task", "new")}</div>`;
  }
  function projectGroup(p) {
    const boards = (S.data.boards || []).filter((b) => b.project_id === p.id);
    const n = boards.length + 1;
    const machines = [...new Set(p.checkouts.map((c) => cardMachineName(c)))];
    const head = `<div class="gitem ${pos(0, n)} clickable" data-act="project" data-id="${p.id}"><div style="display:flex;align-items:center;padding:11px 14px;gap:14px">${avatar(p.id, p.name, 38)}
      <div style="flex:1;min-width:0"><div class="t-headline ellipsis">${esc(p.name)}</div><div class="t-subheadline c-secondary ellipsis">${esc(machines.length ? machines.join(" · ") : "No checkouts")}</div></div>
      <button class="iconbtn" data-act="menu" data-menu="project" data-id="${p.id}">${ico("more_vert")}</button></div></div>`;
    const rows = boards.map((b, i) => {
      const cards = (S.data.cards || []).filter((c) => c.board_id === b.id && !c.archived);
      const attention = cards.filter((c) => c.lane === "review" || isActive(c.runtime)).length;
      return listRow({ title: b.name, glyph: "view_kanban", glyphTint: stableColor(b.id), value: String(cards.length), position: pos(i + 1, n), act: "board", extra: `data-id="${b.id}"`, trailing: countBadge(attention, "var(--warning)") });
    }).join("");
    return `<div class="group" style="padding-top:10px">${head}${rows}</div>`;
  }
  function cardMachineName(checkout) { const m = (S.data.machines || []).find((x) => x.id === checkout.daemon_id); return m ? m.name : checkout.name; }

  function projectScreen(route, opts) {
    const p = project(route.id);
    if (!p) return `<div class="screen">${appbar({ title: "Project", back: opts.back })}<div class="center-fill t-body-em c-secondary">This project is no longer available.</div></div>`;
    const boards = (S.data.boards || []).filter((b) => b.project_id === p.id);
    const rows = boards.map((b, i) => {
      const cards = (S.data.cards || []).filter((c) => c.board_id === b.id && !c.archived); const running = cards.filter((c) => c.lane === "running").length;
      const attention = cards.filter((c) => c.lane === "review" || isActive(c.runtime)).length;
      return listRow({ title: b.name, subtitle: [counts(cards.length, "card"), running ? `${running} running` : null].filter(Boolean).join(" · "), glyph: "view_kanban", tile: stableColor(b.id), position: pos(i, boards.length), act: "board", extra: `data-id="${b.id}"`, trailing: countBadge(attention, "var(--warning)") });
    }).join("");
    const tools = ["files", "changes", "schedules"].map((t, i) => listRow({ title: TOOL_PAGES[t][0], glyph: TOOL_PAGES[t][1], tile: TOOL_PAGES[t][2], position: pos(i, 3), act: "tool", extra: `data-page="${t}" data-project="${p.id}"` })).join("");
    const checkouts = p.checkouts.map((c, i) => listRow({ title: cardMachineName(c), subtitle: compactPath(c.path), glyph: "computer", position: pos(i, p.checkouts.length), trailing: `<span class="dot" style="background:${machineOnline(c.daemon_id) ? "var(--success)" : "var(--tertiary-label)"}"></span>` })).join("");
    return `<div class="screen">${appbar({ title: p.name, subtitle: compactPath(p.path), back: opts.back, actions: iconAction("more_vert", "menu", `data-menu="project" data-id="${p.id}"`) })}
      <div class="content ${hasRail() ? "" : "fab-space"}" data-scroll="project-${p.id}">${sectionHeader("Boards")}<div class="group">${rows || listRow({ title: "New board", glyph: "add" })}</div>
      ${sectionHeader("Workspace")}<div class="group">${tools}</div>${sectionHeader("Checkouts")}<div class="group">${checkouts}</div></div>${fab("New task", "new")}</div>`;
  }
  const compactPath = (p) => (p || "").replace(/^\/(?:Users|home)\/(?!Shared)[^/]+/, "~");
  const machineOnline = (id) => !!(S.data.machines || []).find((m) => m.id === id)?.online;

  // ---- Board (BoardScreen.kt / WorkCard.kt)
  function boardScreen(route, opts) {
    const b = board(route.id);
    if (!b) return `<div class="screen">${appbar({ title: "Board", back: opts.back })}${spinnerCenter()}</div>`;
    const p = project(b.project_id);
    const query = (S.search["board-" + b.id] || "").trim().toLowerCase();
    const cards = (S.data.cards || []).filter((c) => c.board_id === b.id && !c.archived && (!query || cardTitle(c).toLowerCase().includes(query) || (c.summary || "").toLowerCase().includes(query)));
    const lanes = (b.lanes && b.lanes.length ? b.lanes : Object.keys(LANES)).map((id) => ({ id, name: LANES[id] || id, cards: sortCards(cards.filter((c) => c.lane === id), b.id, id) }));
    const page = S.lanePage[b.id] || "running";
    const wide = (opts.inList ? 400 : innerWidth - (hasRail() ? 80 : 0)) >= 700;
    let lanesHtml;
    if (wide) lanesHtml = `<div class="lanes-parallel">${lanes.map((l) => `<div class="lane"><div class="lh"><span class="nm">${esc(l.name)}</span><span class="ct">${l.cards.length}</span></div>${laneList(l, b)}</div>`).join("")}</div>`;
    else {
      const tabs = lanes.map((l) => `<button class="tab ${page === l.id ? "on" : ""}" data-act="lane" data-board="${b.id}" data-lane="${l.id}">${esc(l.name)}${l.cards.length ? `<span class="cnt">${l.cards.length}</span>` : ""}</button>`).join("");
      const lane = lanes.find((l) => l.id === page) || lanes[0];
      lanesHtml = `<div class="tabs">${tabs}</div><div class="content ${hasRail() ? "" : "fab-space"}" data-scroll="board-${b.id}-${page}">${laneList(lane, b)}</div>`;
    }
    return `<div class="screen">${appbar({ title: b.name, subtitle: [p?.name, `${cards.length} cards`].filter(Boolean).join(" · "), back: opts.back, actions: iconAction("more_vert", "menu", `data-menu="board" data-id="${b.id}"`) })}
      ${searchField("board-" + b.id, "Search cards")}${lanesHtml}${fab("New task", "new")}</div>`;
  }
  function sortCards(list, boardId, laneId) {
    const desc = (S.boardSort[boardId + ":" + laneId] ?? true);
    const key = (c) => Math.max(Date.parse(c.updated_at || 0) || 0, Date.parse(c.last_activity_at || 0) || 0);
    return list.sort((a, b) => (desc ? key(b) - key(a) : key(a) - key(b)));
  }
  function laneList(l, b) {
    const desc = S.boardSort[b.id + ":" + l.id] ?? true;
    const head = `<div class="lane-head"><span class="n">${counts(l.cards.length, "card")}</span><button class="sort" data-act="sort" data-board="${b.id}" data-lane="${l.id}">${ico("sort", 14)}${desc ? "Newest first" : "Oldest first"}</button></div>`;
    const body = l.cards.length ? l.cards.map((c) => workCard(c)).join("") : emptyState("view_kanban", `No ${l.name.toLowerCase()} cards`, "Cards you move here appear in this lane.");
    return `<div class="lane-list">${head}${body}</div>`;
  }
  function workCard(c) {
    const tone = rtTone(c.runtime), color = toneColor(tone), badge = rtBadge(c.runtime);
    const status = tone !== "idle" || badge ? rtLabel(c.runtime) : "";
    const agent = [agentLine(c), tokensLabel(c)].filter(Boolean).join(" · ");
    const canStart = c.lane === "todo" && !c.initial_prompt_sent;
    const metas = [(S.data.machines || []).length > 1 ? mpill("computer", cardMachine(c) || "Unassigned") : "", mpill("account_tree", workspaceBadge(c))].join("");
    return `<div class="ccard workcard clickable ${isSelected(c.id) ? "selected" : ""}" data-act="open" data-id="${c.id}">
      <div class="body"><div class="top"><div class="ttl clamp2">${esc(cardTitle(c))}</div>${cardAge(c) ? `<div class="age">${cardAge(c)}</div>` : ""}</div>
      ${c.summary ? `<div class="summary clamp2">${esc(c.summary)}</div>` : ""}<div class="metas">${metas}</div></div>
      <div class="foot"><div class="col">${status ? `<div class="status" style="color:${color}">${tone === "active" ? `<span class="livedot"></span>` : `<span class="sdot"></span>`}<span>${esc(status)}</span></div>` : ""}<div class="agent">${esc(agent)}</div></div>
      ${canStart ? `<button class="btn filled with-icon" style="margin-left:8px" data-act="start" data-id="${c.id}">${ico("play_arrow", 18)}Start</button>` : ""}
      <button class="iconbtn" data-act="menu" data-menu="card" data-id="${c.id}">${ico("more_vert")}</button></div></div>`;
  }
  const mpill = (icon, text) => `<span class="mpill">${ico(icon, 12)}<span class="txt">${esc(text)}</span></span>`;

  // ---- Chats (ChatsScreen.kt)
  function chatsScreen(opts) {
    const chats = (S.data.chats || []).filter((c) => !c.archived);
    const query = (S.search.chats || "").trim().toLowerCase();
    const visible = chats.filter((c) => !query || cardTitle(c).toLowerCase().includes(query));
    const pinned = visible.filter((c) => S.pins[c.id]);
    const byProject = {};
    visible.filter((c) => !S.pins[c.id]).forEach((c) => (byProject[c.project_id || ""] ||= []).push(c));
    const rowsOf = (list) => list.map((c, i) => chatRow(c, pos(i, list.length))).join("");
    let body = "";
    if (pinned.length) body += sectionHeader("Pinned", { prominent: true }) + `<div class="group">${rowsOf(pinned)}</div>`;
    Object.entries(byProject).forEach(([pid, list]) => {
      const p = project(pid); const name = p ? p.name : "Other"; const open = !S.collapsed["chat-" + pid];
      body += `<div class="folder-header" data-act="collapse" data-key="chat-${pid}">${p ? avatar(pid, name, 24) : ico("folder", 20, "c-secondary")}<span class="t">${esc(name)}</span><span class="c">${list.length}</span>${ico("chevron_right", 13, "chev-disc " + (open ? "open" : ""))}</div>`;
      if (open) body += `<div class="group">${rowsOf(list)}</div>`;
    });
    if (!visible.length) body = emptyState("forum", query ? "No results" : "No chats yet", "Start a conversation about any of your projects.", query ? "" : `<button class="btn filled with-icon" data-act="new-chat">${ico("edit", 18)}New chat</button>`);
    return `<div class="screen">${appbar({ title: "Chats", large: true, back: opts.back, actions: iconAction("more_vert", "menu", `data-menu="chats"`) })}
      <div class="content ${hasRail() ? "" : "fab-space"}" data-scroll="chats">${searchField("chats", "Search chats")}${body}</div>${fab("New chat", "new-chat")}</div>`;
  }
  function chatRow(c, position) {
    const running = c.lane === "running" || isActive(c.runtime);
    return `<div class="gitem ${position} clickable ${isSelected(c.id) ? "selected" : ""}" data-act="open" data-id="${c.id}"><div style="display:flex;padding:11px 14px;gap:14px">
      <span style="position:relative;flex:none"><span style="width:36px;height:36px;border-radius:50%;background:var(--secondary-container);color:var(--on-secondary-container);display:flex;align-items:center;justify-content:center">${ico("chat_bubble", 18)}</span>
      ${running ? `<span style="position:absolute;right:-3px;bottom:-3px;width:14px;height:14px;border-radius:50%;background:var(--cell);display:flex;align-items:center;justify-content:center"><span class="livedot s9" style="color:var(--info)"></span></span>` : ""}</span>
      <div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:2px"><div style="display:flex;align-items:center"><span class="t-headline ellipsis" style="flex:1">${S.pins[c.id] ? ico("push_pin", 13, "c-secondary") + " " : ""}${esc(cardTitle(c))}</span><span class="t-footnote c-secondary" style="padding-left:8px">${cardAge(c)}</span></div>
      <div class="t-subheadline c-secondary clamp2">${esc(c.summary || project(c.project_id)?.name || "")}</div></div></div></div>`;
  }

  // ---- Mail / Channels (not exposed by the bridge yet)
  const mailScreen = (opts) => `<div class="screen">${appbar({ title: "Mail", large: true, back: opts.back })}<div class="content" data-scroll="mail">${emptyState("mail", "No mail", "Reports agents send you appear here.")}</div></div>`;
  const channelsScreen = (opts) => `<div class="screen">${appbar({ title: "Channels", large: true, back: opts.back })}<div class="content" data-scroll="channels">${emptyState("tag", "No channels", "Agent channels are off for every project.")}</div></div>`;

  // ---- Tools (ToolsScreen.kt)
  function toolsScreen(opts) {
    const machines = S.data.machines || []; const online = machines.filter((m) => m.online).length;
    const quota = S.data.quota || []; const lowest = quota.length ? Math.min(...quota.flatMap((a) => a.windows.map((w) => w.remaining ?? 100))) : null;
    const grp = (pages, values = {}) => `<div class="group">${pages.map((t, i) => listRow({ title: TOOL_PAGES[t][0], glyph: TOOL_PAGES[t][1], tile: TOOL_PAGES[t][2], value: values[t], position: pos(i, pages.length), act: "tool", extra: `data-page="${t}"` })).join("")}</div>`;
    const proj = S.data.projects?.[0];
    return `<div class="screen">${appbar({ title: "Tools", large: true, back: opts.back })}<div class="content" data-scroll="tools">
      ${sectionHeader("Machines")}${grp(["machines", "terminals", "screens"], { machines: machines.length ? `${online} of ${machines.length} online` : "" })}
      ${sectionHeader("Workspace", { trailing: proj ? `<span class="t-footnote c-secondary">${esc(proj.name)}</span>` : "" })}${grp(["files", "changes", "schedules"])}
      ${sectionHeader("Account")}${grp(["usage", "devices", "settings"], { usage: lowest != null ? `${lowest}% left` : null })}</div></div>`;
  }
  function toolScreen(route, opts) {
    const page = route.page; const [title, glyph] = TOOL_PAGES[page] || [page, "grid_view"];
    if (page === "machines") {
      const ms = S.data.machines || []; const online = ms.filter((m) => m.online).length;
      const rows = ms.map((m, i) => listRow({ title: m.name, subtitle: [m.online ? "Online" : "Offline", m.route].filter(Boolean).join(" · "), glyph: "dns", tile: m.online ? "#0A84FF" : "#8E8E93", position: pos(i, ms.length), act: "machine", extra: `data-id="${m.id}"`, trailing: `<span class="dot" style="width:9px;height:9px;background:${m.online ? "var(--success)" : "var(--tertiary-label)"}"></span>` })).join("");
      return `<div class="screen">${appbar({ title, subtitle: ms.length ? `${online} of ${ms.length} online` : "", back: opts.back, actions: iconAction("refresh", "refresh", "", "Reconnect") })}<div class="content" data-scroll="machines"><div style="height:8px"></div><div class="group">${rows || emptyState("computer", "No machines", "Enroll a computer with the Dieter daemon to run agents on it.")}</div>${sshKeySection()}</div></div>`;
    }
    if (page === "usage") {
      const quota = S.data.quota || [];
      const gauges = quota.map((a) => sectionHeader(`${providerName(a.provider)}${a.plan ? " · " + a.plan : ""}`) + `<div class="gauges">${a.windows.map((w) => `<div class="gauge"><div class="hd">${esc(w.label)}<b>${w.remaining}% left</b></div>${progress((w.remaining ?? 0) / 100, (w.remaining ?? 100) < 20 ? "var(--warning)" : "var(--success)")}<div class="t-footnote c-secondary" style="margin-top:4px">Resets ${esc(resetsAt(w.resets_at))}</div></div>`).join("")}
        ${a.email ? `<div class="kv">Account<b>${esc(a.email)}</b></div>` : ""}</div>`).join("");
      const body = (gauges || emptyState("speed", "No quota data", "Quotas appear once a provider account is discovered.")) + claudeAccountSection();
      return `<div class="screen">${appbar({ title, back: opts.back })}<div class="content" data-scroll="usage">${body}</div></div>`;
    }
    if (page === "changes") {
      // from the Tools tab there is no project in the route: take the only project, else let the user choose one
      const projects = S.data.projects || [];
      const pid = route.projectId || (projects.length === 1 ? projects[0].id : null);
      if (!pid) {
        const rows = projects.map((p, i) => listRow({ title: p.name, subtitle: compactPath(p.path), glyph: "folder", tile: stableColor(p.id), position: pos(i, projects.length), act: "tool", extra: `data-page="changes" data-project="${p.id}"` })).join("");
        return `<div class="screen">${appbar({ title, subtitle: "Choose a project", back: opts.back })}<div class="content" data-scroll="changes-pick">${rows ? `<div class="group">${rows}</div>` : emptyState("difference", "No projects", "Add a repository under Projects first.")}</div></div>`;
      }
      return changesScreen({ projectId: pid }, opts);
    }
    if (page === "schedules") return schedulesScreen(route, opts);
    if (page === "devices") return devicesScreen(route, opts);
    if (page === "files") {
      const projects = S.data.projects || [];
      const pid = route.projectId || (projects.length === 1 ? projects[0].id : null);
      if (!pid && !route.cardId) {
        const rows = projects.map((p, i) => listRow({ title: p.name, subtitle: compactPath(p.path), glyph: "folder", tile: stableColor(p.id), position: pos(i, projects.length), act: "tool", extra: `data-page="files" data-project="${p.id}"` })).join("");
        return `<div class="screen">${appbar({ title, subtitle: "Choose a project", back: opts.back })}<div class="content" data-scroll="files-pick">${rows ? `<div class="group">${rows}</div>` : emptyState("folder", "No projects", "Add a repository under Projects first.")}</div></div>`;
      }
      return filesScreen({ ...route, projectId: pid }, opts);
    }
    const copy = { terminals: ["terminal", "No terminals", "Open a shell on a machine from the Dieter app."], screens: ["desktop_windows", "No screens", "Screen sharing is unavailable on this machine."],
      files: ["folder", "Files", "Browse a project's checkout in the Dieter app."], schedules: ["calendar_month", "No schedules", "Scheduled tasks run on a cron and appear here."],
      devices: ["smartphone", "Devices", "Pair and approve devices in the Dieter app."], settings: ["settings", "Settings", "Prompt and daemon settings live in the Dieter app."] }[page] || [glyph, title, ""];
    return `<div class="screen">${appbar({ title, back: opts.back })}<div class="content">${emptyState(...copy)}</div></div>`;
  }
  const resetsAt = (iso) => { const d = new Date(iso); return isNaN(d) ? "" : d.toLocaleString(undefined, { weekday: "short", hour: "2-digit", minute: "2-digit" }); };
  const gb = (b) => { const v = Math.floor((b || 0) / 107374182.4) / 10; return v >= 100 ? String(Math.floor(v)) : v.toFixed(1); };
  function machineScreen(route, opts) {
    const m = (S.data.machines || []).find((x) => x.id === route.id); const info = S.data.machine;
    if (!m) return `<div class="screen">${appbar({ title: "Machine", back: opts.back })}<div class="center-fill t-body-em c-secondary">This machine is no longer enrolled.</div></div>`;
    const kv = (pairs) => `<div class="group">${pairs.map(([t, v], i) => listRow({ title: t, value: v, position: pos(i, pairs.length) })).join("")}</div>`;
    const load = info && !info.error ? `${sectionHeader("Load")}<div class="gauges">
      <div class="gauge"><div class="hd">CPU<b>${Math.round(info.cpu_percent || 0)}%</b></div>${progress((info.cpu_percent || 0) / 100, (info.cpu_percent || 0) > 85 ? "var(--warning)" : "var(--info)")}</div>
      <div class="gauge"><div class="hd">Memory<b>${gb(info.mem_used)} of ${gb(info.mem_total)} GB</b></div>${progress(info.mem_used / (info.mem_total || 1), info.mem_used / (info.mem_total || 1) > .85 ? "var(--warning)" : "var(--info)")}</div>
      ${info.gpu ? `<div class="gauge"><div class="hd">${esc(info.gpu.name)}<b>${info.gpu.util ?? 0}% · ${info.gpu.temp ?? "?"} °C</b></div>${progress((info.gpu.util || 0) / 100, "var(--info)")}</div>
      <div class="gauge"><div class="hd">VRAM<b>${gb(info.gpu.mem_used)} of ${gb(info.gpu.mem_total)} GB</b></div>${progress(info.gpu.mem_used / (info.gpu.mem_total || 1), "var(--info)")}</div>` : ""}
      <div class="kv">Free storage<b>${gb(info.disk_free)} GB</b></div></div>` : "";
    return `<div class="screen">${appbar({ title: m.name, subtitle: m.online ? "Online" : "Offline", back: opts.back })}<div class="content" data-scroll="machine">
      ${sectionHeader("Connection")}${kv([["Status", m.online ? "Online" : "Offline"], ["Route", m.route || "direct"], ["Dieter", m.version || ""]])}
      ${info && !info.error ? sectionHeader("System") + kv([["System", info.os], ["Model", info.cpu], ["Cores", String(info.cores || "")], ["Uptime", `${Math.floor((info.uptime_s || 0) / 86400)}d ${Math.floor(((info.uptime_s || 0) % 86400) / 3600)}h`]]) : ""}${load}</div></div>`;
  }

  // ---- Devices: the account's trusted devices (dieter device …); approve a new app or machine by its code
  const DEVICE_KIND_ICON = { daemon: "computer", client: "smartphone", recovery: "key" };
  const TRUST_COPY = {
    TRUSTED: ["verified", "Trusted", "This add-on's daemon is a trusted device of your Dieter account."],
    PENDING: ["hourglass_top", "Waiting for approval", "Type the code below on a device you already trust (the Dieter app, or `dieter device approve CODE` on a machine)."],
    NO_ROSTER: ["key_off", "No roster", "Your account has no device roster yet; run `dieter device init` on the first machine."],
    REVOKED: ["block", "Removed", "Another device removed this one. Re-enroll the add-on to pair again."],
    CONFLICT: ["warning", "Conflict", "This daemon's roster conflicts with the gateway's. Re-enroll the add-on to pair again."],
    NOT_ENROLLED: ["cloud_off", "Not enrolled", "The add-on's daemon is not enrolled in a Dieter account (it serves the page locally). Pairing codes of your apps are approved on an enrolled machine: `dieter device approve CODE` there, or Devices in the Dieter app."],
    UNAVAILABLE: ["error", "Unavailable", "The daemon did not answer the device status."],
  };
  function devicesScreen(route, opts) {
    S.dev ||= { data: undefined, code: "" };
    const d = S.dev.data; if (d === undefined) loadDevices();
    const actions = iconAction("refresh", "dev-refresh", "", "Reload");
    let body;
    if (!d) body = spinnerCenter();
    else {
      const [glyph, title, msg] = TRUST_COPY[d.state] || ["devices", d.state || "Unknown", d.error || ""];
      const can = d.state === "TRUSTED";
      const banner = `<div class="banner ${can ? "" : "warning"}" style="margin-top:12px" data-dev-state="${esc(d.state)}">${ico(glyph, 22)}<div><div class="t">${esc(title)}</div><div class="m">${esc(msg)}${d.error && d.state !== "NOT_ENROLLED" ? " " + esc(d.error) : ""}</div></div></div>`;
      const code = d.state === "PENDING" && d.code ? `<div class="m16" style="padding-top:12px"><div class="ccard" style="text-align:center"><div class="t-footnote c-secondary">THIS DAEMON'S CODE</div><div class="t-mono" style="font-size:22px;letter-spacing:.12em;margin-top:6px" data-dev-code>${esc(d.code)}</div></div></div>` : "";
      const approve = `${sectionHeader("Add a device")}<div class="m16"><div class="ccard" style="border-radius:22px;padding:12px;display:flex;flex-direction:column;gap:10px">
        <div class="outlined-field"><label>Code shown on the new device</label><input id="dev-code" data-dfield="code" placeholder="ABCD-EFGH-JKMN-PQRS" autocomplete="off" autocapitalize="characters" spellcheck="false" maxlength="24" ${can ? "" : "disabled"}></div>
        <button class="btn filled with-icon" style="align-self:flex-end" data-act="dev-approve" ${can && S.dev.code.replace(/[\s-]/g, "").length === 16 && !S.busy ? "" : "disabled"}>${S.busy ? `<span class="spin"></span>` : ico("verified_user", 18)}Approve</button></div></div>
        ${sectionFooter("Only type a code you see on your own new device: whoever's code you approve gets full access to your machines.")}`;
      const reqs = d.requests || [];
      const waiting = reqs.length ? sectionHeader("Waiting for approval") + `<div class="group">${reqs.map((r, i) => listRow({ title: r.name || r.id, subtitle: [r.kind, r.platform, r.expires_at ? "expires " + fmtWhen(r.expires_at) : ""].filter(Boolean).join(" · "), glyph: DEVICE_KIND_ICON[r.kind] || "devices", glyphTint: "var(--warning)", position: pos(i, reqs.length) })).join("")}</div>${sectionFooter("Codes are never listed here; the new device shows its own.")}` : "";
      const devs = d.devices || [];
      const list = devs.length ? sectionHeader("Devices") + `<div class="group">${devs.map((x, i) => listRow({ title: x.name || x.id, subtitle: [x.kind === "daemon" ? "machine" : x.kind === "client" ? "app" : x.kind, x.platform, x.this ? "this daemon" : ""].filter(Boolean).join(" · "), glyph: DEVICE_KIND_ICON[x.kind] || "devices", position: pos(i, devs.length), trailing: can && !x.this && x.kind !== "recovery" ? `<button class="iconbtn" data-act="dev-revoke" data-id="${esc(x.id)}" data-name="${esc(x.name)}" title="Remove">${ico("delete", 22)}</button>` : "" })).join("")}</div>` : "";
      body = banner + code + approve + waiting + list;
    }
    return `<div class="screen">${appbar({ title: "Devices", subtitle: d?.synced_at ? "Synced " + fmtWhen(d.synced_at) : "", back: opts.back, actions })}<div class="content" data-scroll="devices">${body}</div></div>`;
  }
  async function loadDevices() {
    S.dev.data = null;
    try { S.dev.data = await api("api/devices"); } catch (e) { S.dev.data = { state: "UNAVAILABLE", error: e.message, devices: [], requests: [] }; }
    render();
  }
  async function approveDevice() {
    const code = (S.dev?.code || "").replace(/[\s-]/g, "").toUpperCase(); if (code.length !== 16 || S.busy) return;
    S.busy = true; render();
    try { const r = await api("api/devices/approve", { code }); S.busy = false; S.dev.code = ""; snackbar(`Approved ${r.device?.name || "the device"}`); S.dev.data = undefined; render(); }
    catch (e) { S.busy = false; snackbar("Not approved: " + e.message); render(); }
  }
  async function revokeDevice(id, name) {
    if (!confirm(`Remove "${name || id}" from your account's devices? It stops being accepted at once.`)) return;
    S.busy = true; render();
    try { await api(`api/devices/${encodeURIComponent(id)}/revoke`, {}); S.busy = false; snackbar("Device removed"); S.dev.data = undefined; render(); }
    catch (e) { S.busy = false; snackbar("Not removed: " + e.message); render(); }
  }

  // ---- Schedules: recurring tasks of the daemon's scheduler (dieter schedule …)
  const CRON_PRESETS = [["0 7 * * 1-5", "Weekdays at 07:00"], ["0 8 * * *", "Every day at 08:00"], ["0 20 * * *", "Every day at 20:00"], ["0 9 * * 1", "Mondays at 09:00"], ["0 * * * *", "Every hour"], ["*/30 * * * *", "Every 30 minutes"]];
  const fmtWhen = (iso) => { if (!iso) return ""; const d = new Date(iso); return isNaN(d) ? String(iso) : d.toLocaleString(undefined, { weekday: "short", day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" }); };
  const cronHuman = (cron) => CRON_PRESETS.find(([c]) => c === cron)?.[1] || cron;
  const browserTz = () => { try { return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC"; } catch { return "UTC"; } };
  function schedulesScreen(route, opts) {
    S.sch ||= { list: {}, detail: {}, form: null, preview: {} };
    if (route.scheduleId) return scheduleDetail(route, opts);
    if (route.create) return scheduleForm(route, opts);
    const key = route.projectId || "*"; const data = S.sch.list[key]; const p = project(route.projectId);
    if (data === undefined) loadSchedules(key, route.projectId);
    const actions = iconAction("add", "sched-new", `data-project="${route.projectId || ""}"`, "New schedule") + iconAction("refresh", "sched-refresh", `data-key="${esc(key)}"`, "Reload");
    let body;
    if (!data) body = spinnerCenter();
    else if (data.error) body = `<div class="banner warning" style="margin-top:12px">${ico("warning", 22)}<div><div class="t">Schedules unavailable</div><div class="m">${esc(data.error)}</div></div></div>`;
    else {
      const list = data.schedules || [];
      body = list.length ? `<div style="height:8px"></div><div class="group">${list.map((s, i) => listRow({ title: s.name, subtitle: s.enabled ? [cronHuman(s.cron), s.next_runs?.[0] ? "next " + fmtWhen(s.next_runs[0]) : ""].filter(Boolean).join(" · ") : `Paused · ${cronHuman(s.cron)}`, glyph: "calendar_month", glyphTint: s.enabled ? "#FF453A" : undefined, position: pos(i, list.length), act: "schedule", extra: `data-id="${s.id}"`, trailing: (!route.projectId && project(s.project_id)) ? `<span class="t-footnote c-secondary">${esc(project(s.project_id).name)}</span>` : "" })).join("")}</div>`
        : emptyState("calendar_month", "No schedules", "Scheduled tasks run on a cron and appear here.", `<button class="btn filled with-icon" data-act="sched-new" data-project="${route.projectId || ""}">${ico("add", 18)}New schedule</button>`);
    }
    return `<div class="screen">${appbar({ title: "Schedules", subtitle: p?.name || "", back: opts.back, actions })}<div class="content" data-scroll="schedules-${esc(key)}">${body}</div></div>`;
  }
  async function loadSchedules(key, projectId) {
    S.sch.list[key] = null;
    try { S.sch.list[key] = await api(`api/schedules${projectId ? "?project=" + encodeURIComponent(projectId) : ""}`); } catch (e) { S.sch.list[key] = { error: e.message }; }
    render();
  }
  async function loadSchedule(id) {
    S.sch.detail[id] = null;
    try { S.sch.detail[id] = await api(`api/schedules/${encodeURIComponent(id)}`); } catch (e) { S.sch.detail[id] = { error: e.message }; }
    render();
  }
  const invalidateSchedules = (id) => { S.sch.list = {}; if (id) delete S.sch.detail[id]; };
  function scheduleDetail(route, opts) {
    const d = S.sch.detail[route.scheduleId];
    if (d === undefined) loadSchedule(route.scheduleId);
    if (!d) return `<div class="screen">${appbar({ title: "Schedule", back: opts.back })}${spinnerCenter()}</div>`;
    if (d.error) return `<div class="screen">${appbar({ title: "Schedule", back: opts.back })}<div class="content"><div class="banner warning" style="margin-top:12px">${ico("warning", 22)}<div><div class="t">Schedule unavailable</div><div class="m">${esc(d.error)}</div></div></div></div></div>`;
    const s = d.schedule; const p = project(s.project_id); const b = board(s.board_id);
    const harness = (S.data.harnesses || []).find((h) => h.id === s.provider);
    const agent = [HARNESS_NAMES[s.provider] || harness?.name || s.provider, harness?.models.find((m) => m.id === s.model)?.name || s.model, s.effort && s.effort[0].toUpperCase() + s.effort.slice(1)].filter(Boolean).join(" · ");
    const kv = (pairs) => `<div class="group">${pairs.map(([t, v], i) => listRow({ title: t, value: v, position: pos(i, pairs.length) })).join("")}</div>`;
    const actions = iconAction("play_arrow", "sched-run", `data-id="${s.id}"`, "Run now") + iconAction(s.enabled ? "pause" : "play_circle", "sched-toggle", `data-id="${s.id}" data-enabled="${s.enabled ? 1 : 0}"`, s.enabled ? "Pause" : "Resume") + iconAction("more_vert", "menu", `data-menu="schedule" data-id="${s.id}"`);
    const runs = d.runs || [];
    const runRows = runs.map((r, i) => listRow({ title: `${fmtWhen(r.scheduled_for)}${r.manual ? " · manual" : ""}`, subtitle: [r.status, r.message].filter(Boolean).join(" · "), glyph: r.status === "completed" ? "check_circle" : /fail|error/.test(r.status) ? "error" : "schedule", glyphTint: r.status === "completed" ? "var(--success)" : /fail|error/.test(r.status) ? "var(--destructive)" : undefined, position: pos(i, runs.length), act: r.card_id ? "sched-card" : "", extra: `data-id="${esc(r.card_id)}"`, trailing: r.card_id ? ico("chevron_right", 18, "c-secondary") : "" })).join("");
    return `<div class="screen">${appbar({ title: s.name, subtitle: [p?.name, b?.name].filter(Boolean).join(" · "), back: opts.back, actions })}<div class="content" data-scroll="schedule-${s.id}">
      ${s.enabled ? "" : `<div class="banner" style="margin-top:12px" data-sched-paused>${ico("pause_circle", 22)}<div><div class="t">Paused</div><div class="m">No automatic runs until resumed. Run now still works.</div></div></div>`}
      ${sectionHeader("When")}${kv([["Cron", s.cron], ["Timezone", s.timezone], ["Next run", s.enabled ? fmtWhen(s.next_runs?.[0]) || "—" : "—"], ["Then", s.enabled ? (s.next_runs || []).slice(1, 3).map(fmtWhen).join(", ") || "—" : "—"], ["Last run", fmtWhen(s.last_run_at) || "never"]])}
      ${sectionHeader("Task")}${kv([["Card title", s.title || s.name], ["Agent", agent || "Project default"], ["Action", s.action === "run" ? "Start the agent" : "Add to Todo"], ["Workspace", s.workspace === "worktree" ? "New worktree" : "Project directory"], ["Access", [s.vault ? "vault" : "", s.mail ? "mail" : ""].filter(Boolean).join(", ") || "none"]])}
      <div class="m16" style="padding-top:8px"><div class="ccard"><div class="t-footnote c-secondary" style="margin-bottom:6px">PROMPT</div><div class="rich">${richText(s.prompt || "")}</div></div></div>
      ${sectionHeader("History")}<div class="group">${runRows || listRow({ title: "No runs yet", subtitle: "Run now creates the first card", glyph: "history" })}</div></div></div>`;
  }
  function openScheduleForm(projectId) {
    S.sch ||= { list: {}, detail: {}, form: null, preview: {} };
    const p = project(projectId) || mruProject() || (S.data.projects || [])[0]; const harness = (S.data.harnesses || []).find((h) => h.id === load("provider", "claude-code")) || (S.data.harnesses || [])[0];
    S.sch.form = { project_id: p?.id || "", board_id: (S.data.boards || []).find((b) => b.project_id === p?.id)?.id || "", name: "", cron: "0 7 * * 1-5", timezone: browserTz(), title: "", prompt: "",
      action: "run", workspace: "project", provider: harness?.id || "", model: harness?.default_model || harness?.models?.[0]?.id || "", effort: "", enabled: true, vault: false, mail: false };
    push({ kind: "tool", page: "schedules", create: true, projectId: p?.id });
    schedPreview();
    setTimeout(() => $("[data-sfield=name]")?.focus(), 50);
  }
  const schedReady = (f) => !!(f && f.name.trim() && f.cron.trim() && f.prompt.trim() && f.project_id && f.board_id && !S.busy);
  function schedFormSync() { const ok = schedReady(S.sch?.form); document.querySelectorAll('[data-act="sched-submit"]').forEach((b) => (b.disabled = !ok)); }
  let schedPreviewTimer = null;
  function schedPreviewDebounced() { clearTimeout(schedPreviewTimer); schedPreviewTimer = setTimeout(schedPreview, 500); }
  async function schedPreview() {
    const f = S.sch?.form; if (!f) return; const key = `${f.cron}|${f.timezone}`;
    if (S.sch.preview[key] === undefined) {
      S.sch.preview[key] = null;
      try { S.sch.preview[key] = (await api(`api/schedules/preview?cron=${encodeURIComponent(f.cron)}&tz=${encodeURIComponent(f.timezone)}`)).times || []; } catch (e) { S.sch.preview[key] = { error: e.message }; }
    }
    const el = $("[data-sched-preview]"); if (el) el.textContent = schedPreviewText(f);
  }
  function schedPreviewText(f) {
    const v = S.sch.preview[`${f.cron}|${f.timezone}`];
    if (v === undefined || v === null) return "Checking the cron expression…";
    if (v.error) return "Invalid cron expression: " + v.error;
    return v.length ? "Next: " + v.slice(0, 3).map(fmtWhen).join(" · ") : "This expression never fires.";
  }
  function scheduleForm(route, opts) {
    const f = S.sch.form; if (!f) return `<div class="screen">${appbar({ title: "New schedule", back: opts.back })}${spinnerCenter()}</div>`;
    const p = project(f.project_id); const boards = (S.data.boards || []).filter((b) => b.project_id === f.project_id); const b = boards.find((x) => x.id === f.board_id);
    const harness = (S.data.harnesses || []).find((h) => h.id === f.provider); const model = harness?.models.find((m) => m.id === f.model); const efforts = model?.efforts || [];
    const picker = (title, value, key, glyph, position, enabled = true) => listRow({ title, value: value || "Choose", glyph, position, act: enabled ? "sched-pick" : "", extra: `data-key="${key}"`, trailing: ico("expand_more", 20, "c-secondary"), disabled: !enabled });
    const seg = (opts2, on, field) => `<div class="seg">${opts2.map((o, i) => `<button class="${on === i ? "on" : ""}" data-act="sched-seg" data-field="${field}" data-i="${i}">${on === i ? ico("check", 18) : ""}${esc(o)}</button>`).join("")}</div>`;
    const sw = (field) => `<button class="switch ${f[field] ? "on" : ""}" data-act="sched-toggle-field" data-field="${field}"></button>`;
    return `<div class="screen">${appbar({ title: "New schedule", subtitle: p?.name || "", back: opts.back, actions: `<button class="btn filled with-icon" style="margin-right:8px" data-act="sched-submit" ${schedReady(f) ? "" : "disabled"}>${ico("check", 18)}Create</button>` })}
      <div class="content" data-scroll="schedule-form" style="padding-top:8px;padding-bottom:24px">
      <div class="compose-card"><input data-sfield="name" placeholder="Name of the schedule"><div class="hairline" style="margin:0 16px"></div>
        <textarea data-sfield="prompt" placeholder="What should the agent do each time?"></textarea><div class="hairline" style="margin:0 16px"></div>
        <input data-sfield="title" placeholder="Card title (optional, defaults to the name)"></div>
      ${sectionHeader("When")}<div class="m16"><div class="ccard" style="border-radius:22px;padding:12px;display:flex;flex-direction:column;gap:10px">
        <div class="outlined-field"><label>Cron expression</label><input data-sfield="cron" placeholder="minute hour day month weekday" spellcheck="false" autocapitalize="off"></div></div></div>
      <div class="group" style="margin-top:8px">${picker("Preset", cronHuman(f.cron) !== f.cron ? cronHuman(f.cron) : "Custom", "preset", "schedule", "first")}${picker("Timezone", f.timezone, "timezone", "public", "last")}</div>
      <div class="section-footer" data-sched-preview>${esc(schedPreviewText(f))}</div>
      ${sectionHeader("Destination")}<div class="group">${picker("Project", p?.name, "project", "folder", "first")}${picker("Board", b?.name, "board", "view_kanban", "last", boards.length > 0)}</div>
      ${sectionHeader("Agent")}<div class="group">${picker("Agent", HARNESS_NAMES[f.provider] || harness?.name || f.provider, "provider", "auto_awesome", "first")}${picker("Model", model?.name || f.model, "model", "memory", efforts.length ? "middle" : "last")}${efforts.length ? picker("Effort", f.effort ? f.effort[0].toUpperCase() + f.effort.slice(1) : "Default", "effort", "bolt", "last") : ""}</div>
      ${sectionHeader("Each occurrence")}<div class="m16"><div class="ccard" style="border-radius:22px;padding:12px;display:flex;flex-direction:column;gap:10px">${seg(["Start the agent", "Add to Todo"], f.action === "run" ? 0 : 1, "action")}${seg(["New worktree", "Project directory"], f.workspace === "worktree" ? 0 : 1, "workspace")}</div></div>
      ${sectionFooter(f.action === "run" ? "Every occurrence creates a card and starts the agent at once." : "Every occurrence adds a card to the board's Todo lane; you start it.")}
      ${sectionHeader("Access")}<div class="group">${listRow({ title: "Enabled", subtitle: "Automatic occurrences on the cron", position: "first", trailing: sw("enabled") })}${listRow({ title: "Allow vault access", subtitle: "Cards may use the account's passwords and TOTP codes", position: "middle", trailing: sw("vault") })}${listRow({ title: "Allow mail to you", subtitle: "Cards may send you read-only reports in Mail", position: "last", trailing: sw("mail") })}</div>
      <div class="m16" style="padding:16px 16px"><button class="btn filled large" style="width:100%;margin-top:8px" data-act="sched-submit" ${schedReady(f) ? "" : "disabled"}>${S.busy ? `<span class="spin"></span>` : ""}Create schedule</button></div></div></div>`;
  }
  function schedPick(key) {
    const f = S.sch.form; let title, options, selected;
    if (key === "preset") { title = "Preset"; options = CRON_PRESETS.map(([c, l]) => [c, l]); selected = f.cron; }
    else if (key === "timezone") { title = "Timezone"; const tz = browserTz(); options = [...new Set([tz, "Europe/Berlin", "Europe/London", "UTC", "America/New_York", "Asia/Tokyo"])].map((z) => [z, z]); selected = f.timezone; }
    else if (key === "project") { title = "Project"; options = (S.data.projects || []).map((p) => [p.id, p.name]); selected = f.project_id; }
    else if (key === "board") { title = "Board"; options = (S.data.boards || []).filter((b) => b.project_id === f.project_id).map((b) => [b.id, b.name]); selected = f.board_id; }
    else if (key === "provider") { title = "Agent"; options = (S.data.harnesses || []).map((h) => [h.id, h.name]); selected = f.provider; }
    else if (key === "model") { title = "Model"; options = ((S.data.harnesses || []).find((h) => h.id === f.provider)?.models || []).map((m) => [m.id, m.name]); selected = f.model; }
    else if (key === "effort") { title = "Effort"; const m = (S.data.harnesses || []).find((h) => h.id === f.provider)?.models.find((x) => x.id === f.model); options = [["", "Default"], ...(m?.efforts || []).map((e) => [e, e[0].toUpperCase() + e.slice(1)])]; selected = f.effort; }
    else return;
    showDialog(title, options, selected, (v) => {
      if (key === "preset") { f.cron = v; schedPreview(); }
      else if (key === "timezone") { f.timezone = v; schedPreview(); }
      else if (key === "project") { f.project_id = v; f.board_id = (S.data.boards || []).find((b) => b.project_id === v)?.id || ""; }
      else if (key === "provider") { f.provider = v; const h = (S.data.harnesses || []).find((x) => x.id === v); f.model = h?.default_model || h?.models?.[0]?.id || ""; f.effort = ""; }
      else if (key === "model") { f.model = v; f.effort = ""; }
      else f[key === "board" ? "board_id" : key] = v;
      render();
    });
  }
  async function submitSchedule() {
    const f = S.sch?.form; if (!schedReady(f)) return;
    S.busy = true; render();
    try {
      const r = await api("api/schedules", { project: f.project_id, board: f.board_id, name: f.name.trim(), cron: f.cron.trim(), timezone: f.timezone, title: f.title.trim(), prompt: f.prompt, action: f.action, workspace: f.workspace, provider: f.provider, model: f.model, effort: f.effort, enabled: f.enabled, vault: f.vault, mail: f.mail });
      S.busy = false; S.sch.form = null; invalidateSchedules();
      const st = stack(); S.stacks[S.tab] = [...st.slice(0, -1), { kind: "tool", page: "schedules", scheduleId: r.schedule.id, projectId: f.project_id }];
      snackbar("Schedule created"); render();
    } catch (e) { S.busy = false; snackbar("Could not create: " + e.message); render(); }
  }
  async function scheduleAction(id, action, done) {
    S.busy = true; render();
    try { const r = await api(`api/schedules/${encodeURIComponent(id)}/${action}`, {}); S.busy = false; invalidateSchedules(id); done?.(r); render(); }
    catch (e) { S.busy = false; snackbar(`${action} failed: ${e.message}`); render(); }
  }

  // ---- Files: browse a checkout (project or card scope) and view one file
  const fmtSize = (n) => n < 1024 ? `${n} B` : n < 1048576 ? `${(n / 1024).toFixed(n < 10240 ? 1 : 0)} KB` : `${(n / 1048576).toFixed(1)} MB`;
  const fileGlyph = (name) => { const x = (name.split(".").pop() || "").toLowerCase();
    if (/^(png|jpe?g|gif|webp|svg|bmp|ico|heic)$/.test(x)) return "image"; if (/^(md|txt|rst|adoc)$/.test(x)) return "article";
    if (/^(json|ya?ml|toml|ini|cfg|csv|xml|env)$/.test(x)) return "data_object"; if (/^(js|mjs|ts|py|go|rs|sh|c|h|cpp|java|kt|rb|php|css|html|sql)$/.test(x)) return "code";
    if (/^(zip|gz|tar|xz|7z|pdf|mp4|mp3|wav)$/.test(x)) return "draft"; return "description"; };
  const filesKey = (route) => `${route.cardId ? "card:" + route.cardId : "project:" + route.projectId}:${route.file ? "f:" + route.file : "d:" + (route.path || "")}:${S.showHidden ? 1 : 0}`;
  const filesApi = (route) => route.cardId ? `api/${conv(route.cardId)?.kind === "chat" ? "chat" : "card"}/${route.cardId}` : `api/project/${route.projectId}`;
  function filesScreen(route, opts) {
    const c = route.cardId ? conv(route.cardId) : null; const p = project(route.projectId || c?.project_id);
    const key = filesKey(route); const data = S.files?.[key];
    if (data === undefined) loadFiles(key, route);
    const scope = [p?.name, c ? (c.branch || workspaceBadge(c)) : ""].filter(Boolean).join(" · ");
    const dirTitle = route.path ? route.path.split("/").pop() : (p?.name || "Files");
    let body, title, subtitle, actions;
    if (route.file) {
      title = route.file.split("/").pop(); subtitle = data && !data.error ? [fmtSize(data.size || 0), data.mime].filter(Boolean).join(" · ") : scope;
      actions = data && !data.error && !data.too_large ? iconAction("download", "file-download", `data-key="${esc(key)}"`, "Download") : "";
      if (!data) body = spinnerCenter();
      else if (data.error) body = `<div class="banner warning" style="margin-top:12px">${ico("warning", 22)}<div><div class="t">File unavailable</div><div class="m">${esc(data.error)}</div></div></div>`;
      else if (data.too_large) body = emptyState("draft", "Too large to show", `${fmtSize(data.size)} · open it in the checkout.`);
      else if (data.data != null && /^image\//.test(data.mime || "")) body = `<img class="filev-img" alt="${esc(title)}" src="data:${esc(data.mime)};base64,${data.data}">`;
      else if (data.data != null) body = emptyState("draft", "Binary file", `${fmtSize(data.size)}${data.mime ? " · " + data.mime : ""}`);
      else body = data.content ? `<pre class="filev t-mono" data-file-content>${esc(data.content)}</pre>` : emptyState("description", "Empty file", "");
    } else {
      title = dirTitle; subtitle = [scope, route.path].filter(Boolean).join(" · ");
      actions = iconAction(S.showHidden ? "visibility_off" : "visibility", "files-hidden", "", S.showHidden ? "Hide dotfiles" : "Show dotfiles") + iconAction("refresh", "files-refresh", `data-key="${esc(key)}"`, "Reload");
      if (!data) body = spinnerCenter();
      else if (data.error) body = `<div class="banner warning" style="margin-top:12px">${ico("warning", 22)}<div><div class="t">Files unavailable</div><div class="m">${esc(data.error)}</div></div></div>`;
      else {
        const es = data.entries || [];
        body = es.length ? `<div style="height:8px"></div><div class="group">${es.map((e, i) => listRow({ title: e.name, subtitle: e.kind === "dir" ? "Folder" : fmtSize(e.size), glyph: e.kind === "dir" ? "folder" : fileGlyph(e.name), glyphTint: e.kind === "dir" ? "#0A9EF5" : undefined, position: pos(i, es.length), act: "file-entry", extra: `data-path="${esc(e.path)}" data-kind="${e.kind}"`, trailing: e.kind === "dir" ? ico("chevron_right", 18, "c-secondary") : "" })).join("")}</div>`
          : emptyState("folder_open", "Empty folder", "");
      }
    }
    return `<div class="screen">${appbar({ title, subtitle, back: opts.back, actions })}<div class="content" data-scroll="files-${esc(key)}">${body}</div></div>`;
  }
  async function loadFiles(key, route) {
    S.files ||= {}; S.files[key] = null;
    try {
      const qs = `path=${encodeURIComponent(route.file || route.path || "")}`;
      S.files[key] = route.file ? await api(`${filesApi(route)}/file?${qs}`) : await api(`${filesApi(route)}/files?${qs}&hidden=${S.showHidden ? 1 : 0}`);
    } catch (e) { S.files[key] = { error: e.message }; }
    render();
  }
  function downloadFile(key) {
    const d = S.files?.[key]; if (!d || d.error) return;
    const blob = d.data != null ? new Blob([Uint8Array.from(atob(d.data), (ch) => ch.charCodeAt(0))], { type: d.mime || "application/octet-stream" }) : new Blob([d.content || ""], { type: d.mime || "text/plain" });
    const a = document.createElement("a"); a.href = URL.createObjectURL(blob); a.download = d.name || "file"; document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  }

  // ---- Changes pane / project changes
  function paneScreen(route, opts) {
    if (route.pane === "changes") return changesScreen({ cardId: route.id }, opts);
    return `<div class="screen">${appbar({ title: route.pane, back: opts.back })}<div class="content">${emptyState("group", "Not available", "Open the Dieter app for this pane.")}</div></div>`;
  }
  function changesScreen(scope, opts) {
    const c = scope.cardId ? conv(scope.cardId) : null; const p = project(scope.projectId || c?.project_id);
    const key = scope.cardId ? "card:" + scope.cardId : "project:" + (p?.id || "");
    const data = S.changes?.[key];
    if (data === undefined) loadChanges(key, scope, p);
    let body;
    if (!data) body = spinnerCenter();
    else if (data.error) body = `<div class="banner warning" style="margin-top:12px">${ico("warning", 22)}<div><div class="t">Changes unavailable</div><div class="m">${esc(data.error)}</div></div></div>`;
    else {
      const files = data.files || [];
      body = files.length ? `<div class="section-header"><span class="txt" style="color:var(--secondary-label);text-transform:uppercase;letter-spacing:.06em">${counts(files.length, "changed file")}</span></div><div class="group">${files.map((f, i) => `<div class="gitem ${pos(i, files.length)}"><div class="file">${avatar(f.status, f.status, 36)}<div style="flex:1;min-width:0"><div class="nm ellipsis">${esc(f.name)}</div><div class="t-subheadline c-secondary ellipsis">${esc(f.dir)}</div></div>${f.added == null && f.removed == null ? "" : `<span class="st c-success">+${f.added ?? 0}</span><span class="st c-destructive">−${f.removed ?? 0}</span>`}</div></div>`).join("")}</div>`
        : emptyState("difference", "No changes", "The checkout is clean.");
      if (data.raw && !files.length) body += `<pre class="t-mono m16" style="white-space:pre-wrap;color:var(--secondary-label)">${esc(data.raw)}</pre>`;
    }
    return `<div class="screen">${appbar({ title: "Changes", subtitle: [p?.name, c?.branch || (c ? workspaceBadge(c) : "")].filter(Boolean).join(" · "), back: opts.back })}<div class="content" data-scroll="changes">${body}</div></div>`;
  }
  async function loadChanges(key, scope, p) {
    S.changes ||= {}; S.changes[key] = null;
    try {
      const r = await api(scope.cardId ? `api/card/${scope.cardId}/changes` : `api/project/${p?.id}/changes`);
      S.changes[key] = normalizeChanges(r.raw);
    } catch (e) { S.changes[key] = { error: e.message }; }
    render();
  }
  function normalizeChanges(raw) {
    if (typeof raw === "string") return { raw };
    const list = raw?.files || raw?.changes || raw?.entries || (Array.isArray(raw) ? raw : null);
    if (!Array.isArray(list)) return { raw: JSON.stringify(raw, null, 2) };
    return { files: list.map((f) => { const path = f.path || f.file || f.name || ""; const i = path.lastIndexOf("/"); return { name: i >= 0 ? path.slice(i + 1) : path, dir: i >= 0 ? path.slice(0, i) : "", status: (f.status || f.kind || f.state || "M").slice(0, 1).toUpperCase(), added: f.additions ?? f.added, removed: f.deletions ?? f.removed }; }) };
  }

  // ---- Conversation (ConversationScreen.kt, Timeline.kt, Composer.kt)
  function conversationScreen(route, opts) {
    const c = conv(route.id); const cv = S.conv.id === route.id ? S.conv.data : null;
    const chat = (c?.kind || route.convKind) === "chat";
    const title = c ? cardTitle(c) : chat ? "Chat" : "Conversation";
    const subtitle = [project(c?.project_id)?.name, chat ? "Chat" : board(c?.board_id)?.name].filter(Boolean).join(" · ");
    const actions = (chat ? "" : iconAction("difference", "pane", `data-id="${route.id}" data-pane="changes"`, "Changes")) + iconAction("more_vert", "menu", `data-menu="conversation" data-id="${route.id}"`);
    if (!cv) return `<div class="screen">${appbar({ title, subtitle, back: opts.back, actions })}${spinnerCenter()}</div>`;
    const status = cv.status; const working = isActive(status) || isActive(c?.runtime) || S.conv.awaiting;
    const runtime = isActive(status) ? status : c?.runtime || status || "idle";
    const canStart = !!c && c.lane === "todo" && !c.initial_prompt_sent && !cv.messages.some((m) => m.role === "user");
    const canHalt = working && rtState(runtime) !== "stopping";
    const reviewable = !!c && c.kind !== "chat" && !canHalt && c.lane !== "review" && (board(c.board_id)?.lanes || Object.keys(LANES)).includes("review");
    const harness = (S.data.harnesses || []).find((h) => h.id === c?.provider);
    const sel = S.conv.agent || {};
    const modelName = harness?.models.find((m) => m.id === (sel.model || c?.model))?.name || sel.model || c?.model || "";
    const effortName = sel.effort || c?.effort || "";
    const agentChip = c ? `<button class="cchip" data-act="agent-sheet">${ico("auto_awesome", 13)}${esc([HARNESS_NAMES[c.provider] || c.provider, modelName, effortName && effortName[0].toUpperCase() + effortName.slice(1)].filter(Boolean).join(" · "))}${ico("expand_more", 10, "chev")}</button>` : "";
    const chips = agentChip + (canStart ? `<button class="cchip prominent" data-act="start" data-id="${route.id}">${ico("play_arrow", 13)}Start</button>` : "")
      + (reviewable ? `<button class="cchip" data-act="move" data-id="${route.id}" data-lane="review">${ico("checklist", 13)}Move to Review</button>` : "")
      + (cv.pending_tools_summary ? `<span class="cchip">${ico("hourglass_empty", 13)}${esc(cv.pending_tools_summary)}</span>` : "");
    const draft = S.conv.draft; const showStop = canHalt && !draft.trim();
    const sendBtn = showStop ? `<button class="sendbtn stop" data-act="cancel" data-id="${route.id}" title="Stop agent">${ico("stop", 17)}</button>`
      : `<button class="sendbtn" data-act="send" ${(draft.trim() || S.conv.files.length) && !S.busy ? "" : "disabled"} title="Send message">${ico("arrow_upward", 22)}</button>`;
    const composer = `<div class="composer"><div class="chips">${chips}</div>${filesRow("conv")}<div class="row"><button class="attach" title="Attach images or files" data-act="attach" data-target="conv">${ico("add")}</button>
      <div class="field"><textarea id="draft" rows="1" placeholder="${working ? "Queue a message…" : "Message"}">${esc(draft)}</textarea>${sendBtn}</div></div></div>`;
    return `<div class="screen">${appbar({ title, subtitle, back: opts.back, actions })}<div class="timeline" data-scroll="conv-${route.id}" id="timeline">${timeline(cv, c, canStart, working, runtime)}</div>${!S.conv.following ? `<button class="fab-small" style="bottom:${hasRail() ? 120 : 120}px" data-act="jump">${ico("arrow_downward")}</button>` : ""}${composer}</div>`;
  }
  function timeline(cv, c, canStart, working, runtime) {
    const out = [];
    const unsent = c && !c.initial_prompt_sent && c.initial_prompt && !cv.messages.some((m) => m.role === "user") ? c.initial_prompt.trim() : "";
    if (unsent) out.push(`<div class="ccard" style="margin:0 16px"><div style="display:flex;align-items:center;gap:8px">${ico("article", 18, "c-secondary")}<span class="t-headline" style="flex:1">Task</span>${canStart ? `<button class="btn filled with-icon" data-act="start" data-id="${c.id}">${ico("play_arrow", 18)}Start</button>` : ""}</div><div class="rich" style="margin-top:8px">${richText(unsent)}</div></div>`);
    const rows = buildTimeline(cv.messages);
    rows.forEach((row) => {
      if (row.type === "activity") { const open = !!S.conv.expanded[row.id]; out.push(`<div class="msg-assistant"><button class="activity-row ${open ? "open" : ""}" data-act="expand" data-id="${row.id}">${ico("auto_awesome", 15)}<span class="ellipsis">${esc(row.summary)}</span>${ico("chevron_right", 13, "chev")}</button>${open ? row.steps.map(stepHtml).join("") : ""}</div>`); return; }
      if (row.user) { out.push(`<div class="msg-user">${(row.files || []).map(attachmentHtml).join("")}${row.text ? `<div class="bubble rich">${richText(row.text)}</div>` : ""}${row.delivery ? `<div class="t-caption c-secondary" style="padding:4px 6px 0">${esc(row.delivery)}</div>` : ""}</div>`); return; }
      const groups = row.groups.map((g) => {
        if (g.activity) { const open = !!S.conv.expanded[g.id]; return `<button class="activity-row ${open ? "open" : ""}" data-act="expand" data-id="${g.id}">${ico("auto_awesome", 15)}<span class="ellipsis">${esc(g.summary)}</span>${ico("chevron_right", 13, "chev")}</button>${open ? g.steps.map(stepHtml).join("") : ""}`; }
        return g.steps.map(stepHtml).join("");
      }).join("");
      out.push(`<div class="msg-assistant">${groups}${(row.plans || []).map(planCard).join("")}</div>`);
    });
    (cv.task_plans || []).filter((p) => !rows.some((r) => r.plans?.includes(p))).forEach((p) => out.push(planCard(p)));
    const asks = c ? pendingQuestions(c.id) : [];
    asks.forEach((q) => out.push(questionCard(q)));
    if (working && !asks.length) out.push(`<div class="working"><span class="spinner s16"></span><span class="clamp2">${esc(liveActivity(cv, runtime))}</span></div>`);
    (cv.queue || []).forEach((qm) => out.push(`<div class="queued"><div class="bx t-body clamp2">${esc(qm.text || "")}</div><div class="t-footnote c-secondary" style="display:flex;align-items:center;gap:4px;padding-top:4px">${ico("schedule", 13)}Queued</div></div>`));
    return out.join("") + `<div style="height:1px"></div>`;
  }
  // TimelineBuilder: adjacent tool-only assistant messages fold into one activity summary
  const TOOL_CATS = [["edit", /^(edit|apply_patch|patch|multi_edit|multiedit|str_replace_editor|edit_file)$/, /apply.?patch|edit|replace/], ["command", /^(bash|shell|command|exec|exec_command|write_stdin|terminal)$/, /bash|shell|terminal|exec|command/],
    ["write", /^(write|write_file|create_file)$/, /write|create.?file/], ["read", /^(read|read_file|readfile|view_file)$/, /read|view.?file/], ["search", /^(grep|glob|search|find|websearch|web_search)$/, /grep|glob|search|find/], ["browser", /^$/, /browser|navigate|click|screenshot/]];
  const NOUNS = { edit: ["edit", "edits"], command: ["command", "commands"], write: ["write", "writes"], read: ["read", "reads"], search: ["search", "searches"], browser: ["browser action", "browser actions"], other: ["tool call", "tool calls"] };
  function toolKind(name) { return (name || "").toLowerCase().split("__").pop().split(".").pop().split("/").pop(); }
  function toolCategory(name) { const k = toolKind(name); for (const [cat, exact] of TOOL_CATS) if (exact.test(k)) return cat; if (/browser|navigate|click|screenshot/.test(k)) return "browser"; for (const [cat, , fuzzy] of TOOL_CATS) if (fuzzy.test(k)) return cat; return "other"; }
  const toolName = (p) => (p.type || "").startsWith("tool-") ? p.type.slice(5) : p.toolName || p.tool_name || p.name || "";
  const isToolPart = (p) => (p.type || "").startsWith("tool-") || p.type === "tool" || p.type === "dynamic-tool" || !!p.toolCallId || !!p.tool_call_id;
  const isReasoningPart = (p) => p.type === "reasoning" || p.type === "thinking";
  function stepOf(p, i, mid) {
    if (isToolPart(p)) return { id: `${mid}:${i}`, kind: "tool", part: p, routine: !/approval/.test(p.state || "") };
    if (isReasoningPart(p)) return { id: `${mid}:${i}`, kind: "reasoning", text: p.text || "", routine: true };
    if (p.type === "text") return { id: `${mid}:${i}`, kind: "text", text: p.text || "", routine: false };
    if (["file", "attachment", "image"].includes(p.type)) return { id: `${mid}:${i}`, kind: "attachment", part: p, routine: false };
    if (p.type === "step-start" || p.type === "step") return null;
    return { id: `${mid}:${i}`, kind: "other", text: p.text || p.summary || "", routine: false };
  }
  function summaryOf(steps) {
    const reasoning = steps.filter((s) => s.kind === "reasoning").length; const tools = {};
    steps.filter((s) => s.kind === "tool").forEach((s) => { const c = toolCategory(toolName(s.part)); tools[c] = (tools[c] || 0) + 1; });
    const parts = []; if (reasoning) parts.push("Reasoning");
    ["edit", "command", "write", "read", "search", "browser", "other"].forEach((c) => { if (tools[c]) parts.push(`${tools[c]} ${NOUNS[c][tools[c] === 1 ? 0 : 1]}`); });
    return parts.join(" · ") || "Activity";
  }
  function buildTimeline(messages) {
    const rows = []; let run = [];
    const flush = () => { if (!run.length) return; const steps = run.flatMap((r) => r.steps); rows.push({ type: "activity", id: "tools:" + run[0].id, steps, summary: summaryOf(steps) }); run = []; };
    (messages || []).forEach((m, mi) => {
      const mid = m.id || "m" + mi; const user = m.role === "user";
      let steps = (m.parts || []).map((p, i) => stepOf(p, i, mid)).filter(Boolean);
      // coalesce adjacent text
      steps = steps.reduce((acc, s) => { const last = acc[acc.length - 1]; if (s.kind === "text" && last?.kind === "text") last.text += "\n\n" + s.text; else acc.push({ ...s }); return acc; }, []);
      if (!user && !steps.length && !(m.plans || []).length) return;
      if (!user && steps.length && steps.every((s) => s.routine)) { run.push({ id: mid, steps }); return; }
      flush();
      if (user) { rows.push({ type: "message", user: true, id: mid, text: steps.filter((s) => s.kind !== "attachment").map((s) => s.text || "").join("\n\n").trim(), files: steps.filter((s) => s.kind === "attachment").map((s) => s.part), delivery: m.delivery }); return; }
      const groups = []; steps.forEach((s) => { const last = groups[groups.length - 1]; if (s.routine && last?.activity) last.steps.push(s); else groups.push({ id: s.id, steps: [s], activity: s.routine }); });
      groups.forEach((g) => { if (g.activity) g.summary = summaryOf(g.steps); });
      rows.push({ type: "message", user: false, id: mid, groups, plans: m.plans || [] });
    });
    flush();
    // merge adjacent activity rows
    return rows.reduce((acc, r) => { const last = acc[acc.length - 1]; if (r.type === "activity" && last?.type === "activity") { last.steps = last.steps.concat(r.steps); last.summary = summaryOf(last.steps); } else acc.push(r); return acc; }, []);
  }
  function stepHtml(s) {
    if (s.kind === "tool") {
      const p = s.part; const name = toolName(p); const title = (name.split("__").pop() || "tool").replace(/_/g, " ");
      const state = (p.state || "").toLowerCase(); const failed = /error|failed/.test(state) || !!(p.errorText || p.error_text); const approval = /approval/.test(state);
      const status = failed ? "Tool failed" : approval ? "Approval requested" : /input-available|running|executing/.test(state) ? "running" : /completed|success|done|output-available/.test(state) || p.hasOutput || p.has_output ? "completed" : "";
      const preview = (p.inputPreview || p.input_preview || p.input || p.summary || "").toString().replace(/\s+/g, " ").slice(0, 140);
      return `<button class="tool-row" data-act="tool" data-preview="${esc(preview)}"><span class="ms s15 c-secondary">terminal</span><span class="ttl">${esc(title)}${preview ? " · " + esc(preview) : ""}</span><span class="st ${failed || approval ? "attn" : ""}">${esc(status)}</span></button>`;
    }
    if (s.kind === "reasoning") return s.text.trim() ? `<div class="reasoning"><span class="bar"></span><div class="txt clamp2" style="-webkit-line-clamp:12">${esc(s.text)}</div></div>` : "";
    if (s.kind === "attachment") return `<div class="mpill" style="height:auto;padding:8px 10px">${ico("insert_drive_file", 18)}<span>${esc(s.part.filename || "Attachment")}</span></div>`;
    return s.text ? `<div class="rich">${richText(s.text)}</div>` : "";
  }
  function attachmentHtml(p) {
    const name = p.filename || p.name || "Attachment"; const url = p.url || "";
    if (url.startsWith("data:image/")) return `<img class="att-img" src="${esc(url)}" alt="${esc(name)}" title="${esc(name)}">`;
    return `<div class="mpill att-pill" title="${esc(name)}">${ico((p.mediaType || p.media_type || "").startsWith("image/") ? "image" : "insert_drive_file", 18)}<span class="txt">${esc(name)}</span></div>`;
  }
  // ---- questions the agent asks (Claude Code's AskUserQuestion, relayed by the harness worker)
  const pendingQuestions = (cardId) => (S.data.questions || []).filter((q) => q.session === cardId);
  function answerState(q) {
    if (!S.qa[q.id]) S.qa[q.id] = {};
    (q.input.questions || []).forEach((x) => { if (!S.qa[q.id][x.id]) S.qa[q.id][x.id] = { optionIds: [], freeform: "" }; });
    return S.qa[q.id];
  }
  function questionCard(q) {
    const st = answerState(q); const qs = q.input.questions || [];
    const blocks = qs.map((x) => {
      const a = st[x.id]; const multi = !!x.allowMultiple;
      const opts = (x.options || []).map((o) => `<button class="qopt ${a.optionIds.includes(o.id) ? "on" : ""}" data-act="q-opt" data-q="${esc(q.id)}" data-x="${esc(x.id)}" data-o="${esc(o.id)}">${ico(multi ? (a.optionIds.includes(o.id) ? "check_box" : "check_box_outline_blank") : (a.optionIds.includes(o.id) ? "radio_button_checked" : "radio_button_unchecked"), 18)}<span><span class="t-body-em">${esc(o.label)}</span>${o.description ? `<span class="t-footnote c-secondary">${esc(o.description)}</span>` : ""}</span></button>`).join("");
      const free = x.allowFreeForm ? `<div class="outlined-field" style="margin-top:8px"><label>${(x.options || []).length ? "Or your own answer" : "Your answer"}</label><input data-qfree="${esc(q.id)}" data-x="${esc(x.id)}" id="qf-${esc(q.id)}-${esc(x.id)}" value="${esc(a.freeform)}" autocomplete="off"></div>` : "";
      return `<div class="qblock">${x.header ? `<div class="t-footnote c-secondary" style="text-transform:uppercase;letter-spacing:.06em">${esc(x.header)}</div>` : ""}<div class="t-headline" style="margin:2px 0 8px">${esc(x.question)}</div><div class="qopts">${opts}</div>${free}</div>`;
    }).join("");
    const answered = qs.filter((x) => st[x.id].optionIds.length || st[x.id].freeform.trim()).length;
    const ready = answered === qs.length || (q.input.allowPartialAnswers && answered > 0);
    return `<div class="ccard question" data-question="${esc(q.id)}"><div class="hd">${ico("help", 18, "c-secondary")}<span class="t">The agent asks</span></div>${blocks}
      <div class="acts" style="display:flex;justify-content:flex-end;gap:8px;margin-top:12px"><button class="btn text" data-act="q-decline" data-q="${esc(q.id)}">Decline</button><button class="btn filled" data-act="q-answer" data-q="${esc(q.id)}" ${ready ? "" : "disabled"}>Answer</button></div></div>`;
  }
  function questionToggle(qid, xid, oid) {
    const q = (S.data.questions || []).find((z) => z.id === qid); if (!q) return;
    const x = (q.input.questions || []).find((z) => z.id === xid); const a = answerState(q)[xid]; if (!x || !a) return;
    if (x.allowMultiple) a.optionIds = a.optionIds.includes(oid) ? a.optionIds.filter((z) => z !== oid) : [...a.optionIds, oid];
    else a.optionIds = a.optionIds.includes(oid) ? [] : [oid];
    render();
  }
  async function questionSubmit(qid, decline) {
    const q = (S.data.questions || []).find((z) => z.id === qid); if (!q || S.busy) return;
    const st = answerState(q); const qs = q.input.questions || [];
    let body;
    if (decline) body = { action: "declined" };
    else {
      const answers = {};
      qs.forEach((x) => { const a = st[x.id]; if (a.optionIds.length || a.freeform.trim()) answers[x.id] = { optionIds: a.optionIds, ...(a.freeform.trim() ? { freeform: a.freeform.trim() } : {}) }; });
      body = { action: Object.keys(answers).length === qs.length ? "answered" : "partially-answered", answers };
    }
    S.busy = true; render();
    try { await api(`api/questions/${encodeURIComponent(qid)}/answer`, body); delete S.qa[qid]; S.conv.awaiting = true; snackbar(decline ? "Declined. The agent continues." : "Answer sent. The agent continues."); }
    catch (e) { snackbar(e.message); }
    finally { S.busy = false; await refreshState(); render(); }
  }
  // ---- attachments (uploaded to the bridge, handed to the CLI with --attach)
  const MAX_FILES = 4, MAX_FILE_BYTES = 20 * 1024 * 1024;
  function pickFiles(target) {
    const input = document.createElement("input"); input.type = "file"; input.multiple = true; input.id = "file-in";
    input.accept = "image/*,.pdf,.txt,.md,.csv,.json,.log,.yaml,.yml,.py,.js,.ts,.html,.css,.sh";
    input.style.display = "none"; document.body.appendChild(input);
    input.addEventListener("change", () => { addFiles(target, [...input.files]); input.remove(); });
    input.click();
  }
  function fileList(target) { return target === "conv" ? S.conv.files : S.files; }
  function addFiles(target, files) {
    const list = fileList(target);
    for (const f of files) {
      if (list.length >= MAX_FILES) { snackbar(`At most ${MAX_FILES} attachments.`); break; }
      if (f.size > MAX_FILE_BYTES) { snackbar(`${f.name} is larger than 20 MB.`); continue; }
      list.push({ file: f, name: f.name, size: f.size, type: f.type || "application/octet-stream", preview: f.type.startsWith("image/") ? URL.createObjectURL(f) : "" });
    }
    render();
  }
  function removeFile(target, i) { const list = fileList(target); const [f] = list.splice(i, 1); if (f?.preview) URL.revokeObjectURL(f.preview); render(); }
  function filesRow(target) {
    const list = fileList(target); if (!list.length) return "";
    return `<div class="files-row" data-files="${target}">${list.map((f, i) => `<span class="fchip" title="${esc(f.name)}">${f.preview ? `<img src="${esc(f.preview)}" alt="">` : ico("insert_drive_file", 16)}<span class="txt">${esc(f.name)}</span><button class="x" data-act="rm-file" data-target="${target}" data-i="${i}" title="Remove">${ico("close", 14)}</button></span>`).join("")}</div>`;
  }
  async function uploadFiles(target) {
    const ids = [];
    for (const f of fileList(target)) {
      const r = await fetch("api/upload", { method: "POST", headers: { "Content-Type": f.type, "X-Filename": encodeURIComponent(f.name) }, body: f.file });
      const j = await r.json().catch(() => ({ error: r.statusText }));
      if (!r.ok) throw new Error(j.error || `upload failed: ${r.statusText}`);
      ids.push(j.id);
    }
    return ids;
  }
  function clearFiles(target) { fileList(target).forEach((f) => { if (f.preview) URL.revokeObjectURL(f.preview); }); fileList(target).length = 0; }
  function planCard(plan) {
    const tasks = (plan.phases || []).flatMap((ph) => ph.tasks || []); const done = tasks.filter((t) => ["completed", "abandoned"].includes(t.status)).length;
    return `<div class="ccard plan"><div class="hd">${ico("checklist", 18, "c-secondary")}<span class="t">Plan</span><span class="t-footnote c-secondary">${done} of ${tasks.length}</span></div><div style="height:10px"></div>${progress(tasks.length ? done / tasks.length : 0, "var(--success)")}<div style="height:6px"></div>
      ${(plan.phases || []).map((ph) => (ph.name ? `<div class="t-subheadline sem" style="padding:8px 0 2px">${esc(ph.name)}</div>` : "") + (ph.tasks || []).map((t) => { const d = ["completed", "abandoned"].includes(t.status); return `<div class="task"><span class="ms s18 ${d ? "fill c-success" : "c-tertiary"}">${d ? "check_circle" : "radio_button_unchecked"}</span><span class="${d ? "c-secondary" : ""}">${esc(t.status === "in_progress" && t.activeForm ? t.activeForm : t.content || t.text || "")}</span></div>`; }).join("")).join("")}</div>`;
  }
  function liveActivity(cv, runtime) {
    if ((runtime || "").toLowerCase() === "cancelling") return "Stopping…";
    const msgs = cv.messages || []; const lastUser = msgs.map((m) => m.role).lastIndexOf("user");
    const parts = msgs.slice(lastUser + 1).filter((m) => m.role === "assistant").flatMap((m) => m.parts || []);
    const running = parts.filter((p) => isToolPart(p) && /input-available|running|executing/.test((p.state || "").toLowerCase()) && !(p.hasOutput || p.has_output));
    const last = running[running.length - 1];
    if (last) {
      const kind = toolKind(toolName(last)); const raw = (last.inputPreview || last.input_preview || "").toString();
      let fields = null; try { fields = JSON.parse(last.input || last.inputJson || raw); } catch (e) { fields = null; }
      const pick = (keys) => fields ? keys.map((k) => fields[k]).find((v) => typeof v === "string" && v.trim()) : null;
      const compact = (t) => { const c = (t || "").slice(0, 1024).replace(/\s+/g, " ").trim(); return c.length > 120 ? c.slice(0, 119) + "…" : c; };
      const desc = fields?.description; if (desc) return compact(desc);
      let verb, target;
      if (/^(read|read_file|readfile)$/.test(kind)) { verb = "Reading"; target = pick(["path", "file_path", "filePath"]); }
      else if (/^(edit|write|write_file|edit_file|multiedit|multi_edit|apply_patch|patch)$/.test(kind)) { verb = "Editing"; target = pick(["path", "file_path", "filePath"]); }
      else if (/^(bash|shell|command|exec|exec_command|terminal)$/.test(kind)) { verb = "Running"; target = pick(["command", "cmd", "argv"]); }
      else if (/^(grep|glob|search|websearch|web_search)$/.test(kind)) { verb = "Searching"; target = pick(["query", "pattern"]); }
      else if (/^(webfetch|web_fetch|fetch)$/.test(kind)) { verb = "Fetching"; target = pick(["url"]); }
      else return `Using ${kind.replace(/_/g, " ")}…` + (running.length > 1 ? ` · +${running.length - 1} ${running.length - 1 === 1 ? "tool" : "tools"}` : "");
      if (!target && !fields && raw && !/^[\[{]/.test(raw.trim())) target = raw;
      if (target && (verb === "Reading" || verb === "Editing")) target = target.replace(/\/+$/, "").split("/").pop();
      const text = target ? compact(`${verb} ${target}`) : { Reading: "Reading file…", Editing: "Editing files…", Running: "Running command…", Searching: "Searching…", Fetching: "Fetching page…" }[verb];
      return text + (running.length > 1 ? ` · +${running.length - 1} ${running.length - 1 === 1 ? "tool" : "tools"}` : "");
    }
    const lastPart = parts.filter((p) => p.type !== "step-start").pop();
    if (lastPart && lastPart.type === "text" && lastPart.state === "streaming") return "Writing response…";
    if ((runtime || "").toLowerCase() === "starting" && !parts.length) return "Starting agent…";
    return "Thinking…";
  }

  // minimal markdown (RichText)
  function richText(md) {
    const lines = String(md || "").replace(/\r/g, "").split("\n"); const out = []; let i = 0;
    const inline = (t) => esc(t).replace(/`([^`]+)`/g, "<code>$1</code>").replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>").replace(/(^|[^*])\*([^*\n]+)\*/g, "$1<em>$2</em>").replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    while (i < lines.length) {
      const l = lines[i];
      if (/^```/.test(l)) { const buf = []; i++; while (i < lines.length && !/^```/.test(lines[i])) buf.push(lines[i++]); i++; out.push(`<pre><code>${esc(buf.join("\n"))}</code></pre>`); continue; }
      const h = /^(#{1,4})\s+(.*)$/.exec(l); if (h) { out.push(`<h${h[1].length}>${inline(h[2])}</h${h[1].length}>`); i++; continue; }
      if (/^\s*[-*]\s+/.test(l)) { const items = []; while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) items.push(lines[i++].replace(/^\s*[-*]\s+/, "")); out.push(`<ul>${items.map((x) => `<li>${inline(x)}</li>`).join("")}</ul>`); continue; }
      if (/^\s*\d+[.)]\s+/.test(l)) { const items = []; while (i < lines.length && /^\s*\d+[.)]\s+/.test(lines[i])) items.push(lines[i++].replace(/^\s*\d+[.)]\s+/, "")); out.push(`<ol>${items.map((x) => `<li>${inline(x)}</li>`).join("")}</ol>`); continue; }
      if (/^\|/.test(l) && /^\|?\s*:?-+/.test(lines[i + 1] || "")) { const rows = []; while (i < lines.length && /^\|/.test(lines[i])) rows.push(lines[i++]); const cells = (r) => r.replace(/^\||\|$/g, "").split("|").map((c) => c.trim()); const head = cells(rows[0]); const body = rows.slice(2).map(cells); out.push(`<table><thead><tr>${head.map((c) => `<th>${inline(c)}</th>`).join("")}</tr></thead><tbody>${body.map((r) => `<tr>${r.map((c) => `<td>${inline(c)}</td>`).join("")}</tr>`).join("")}</tbody></table>`); continue; }
      if (/^>\s?/.test(l)) { const buf = []; while (i < lines.length && /^>\s?/.test(lines[i])) buf.push(lines[i++].replace(/^>\s?/, "")); out.push(`<blockquote>${inline(buf.join(" "))}</blockquote>`); continue; }
      if (!l.trim()) { i++; continue; }
      const buf = []; while (i < lines.length && lines[i].trim() && !/^(```|#{1,4}\s|\s*[-*]\s|\s*\d+[.)]\s|\||>)/.test(lines[i])) buf.push(lines[i++]);
      if (buf.length) out.push(`<p>${inline(buf.join("\n")).replace(/\n/g, "<br>")}</p>`); else i++;
    }
    return out.join("");
  }

  // conversation data + stream
  function syncConversation() {
    const r = stack().find((x) => x.kind === "conversation") || (twoPane() ? null : null);
    const route = twoPane() ? stack().slice().reverse().find((x) => x.kind === "conversation" || x.kind === "pane") : (top().kind === "conversation" || top().kind === "pane" ? top() : null);
    const id = route?.id || null;
    if (!id) { if (S.conv.es) { S.conv.es.close(); S.conv.es = null; } return; }
    if (S.conv.id === id) { bindComposer(); return; }
    if (S.conv.es) S.conv.es.close();
    const kind = route.convKind || (conv(id)?.kind === "chat" ? "chat" : "card");
    clearFiles("conv");
    S.conv = { ...S.conv, id, kind, data: null, draft: load("draft-" + id, ""), following: true, expanded: {}, agent: null, awaiting: false, files: [] };
    api(`api/${kind}/${id}`).then((d) => { if (S.conv.id !== id) return; S.conv.data = d; markSeen(id); render(); scrollToEnd(); })
      .catch((e) => { if (S.conv.id === id) { S.conv.data = { messages: [], error: e.message }; render(); } });
    if (SNAP) return;
    openConversationStream(id, kind);
  }
  // The watch stream ends when a turn finishes or fails; reconnect after a moment while keeping
  // the messages on screen (resetting the conversation here blanked the pane on failures).
  function openConversationStream(id, kind) {
    const es = new EventSource(`api/${kind}/${id}/stream`);
    es.addEventListener("conversation", (e) => { if (S.conv.id !== id) return; const d = JSON.parse(e.data); if (!d || (!d.status && !(d.messages || []).length)) return; S.conv.data = d; S.conv.awaiting = false; markSeen(id); render(); if (S.conv.following) scrollToEnd(); });
    es.addEventListener("end", () => {
      if (S.conv.id !== id || S.conv.es !== es) return;
      es.close(); S.conv.es = null;
      setTimeout(() => {
        if (S.conv.id !== id || S.conv.es) return;
        api(`api/${kind}/${id}`).then((d) => { if (S.conv.id === id) { S.conv.data = d; markSeen(id); render(); } }).catch(() => {});
        openConversationStream(id, kind);
      }, 3000);
    });
    S.conv.es = es;
  }
  function markSeen(id) { const c = conv(id); if (c?.last_activity_at && !isActive(c.runtime)) { S.seen[id] = c.last_activity_at; save("seen", S.seen); } if (c?.response_seq != null && Number(c.response_seq) > Number(c.seen_response_seq || 0)) api(`api/card/${id}/read`, { seq: c.response_seq }).catch(() => {}); }
  function scrollToEnd() { const t = $("#timeline"); if (t) t.scrollTop = t.scrollHeight; }
  function bindComposer() {
    const ta = $("#draft"); if (!ta || ta._bound) return; ta._bound = true;
    const grow = () => { ta.style.height = "auto"; ta.style.height = Math.min(156, ta.scrollHeight) + "px"; };
    grow();
    ta.addEventListener("input", () => { S.conv.draft = ta.value; save("draft-" + S.conv.id, ta.value); grow(); const b = $(".sendbtn"); if (b && !b.classList.contains("stop")) b.disabled = !ta.value.trim(); if (!ta.value.trim() || ta.value.trim().length === 1) { render(); const n = $("#draft"); if (n && n !== ta) { n.focus(); n.setSelectionRange(n.value.length, n.value.length); } } });
    ta.addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.shiftKey && !e.isComposing && innerWidth >= 600) { e.preventDefault(); send(); } });
    const t = $("#timeline"); if (t && !t._bound) { t._bound = true; t.addEventListener("scroll", () => { const atEnd = t.scrollHeight - t.scrollTop - t.clientHeight < 40; if (S.conv.following !== atEnd) { S.conv.following = atEnd; const b = $(".fab-small"); if (!atEnd && !b) render(); if (atEnd && b) b.remove(); } }, { passive: true }); }
  }
  async function send() {
    const text = (S.conv.draft || "").trim(); if ((!text && !S.conv.files.length) || !S.conv.id || S.busy) return;
    S.busy = true; render();
    try {
      const body = { message: text }; if (S.conv.agent?.model) body.model = S.conv.agent.model; if (S.conv.agent?.effort) body.effort = S.conv.agent.effort;
      if (S.conv.files.length) body.attachments = await uploadFiles("conv");
      await api(`api/${S.conv.kind}/${S.conv.id}/send`, body);
      clearFiles("conv");
      S.conv.draft = ""; save("draft-" + S.conv.id, ""); S.conv.following = true; S.conv.awaiting = true;
    } catch (e) { snackbar(e.message); } finally { S.busy = false; render(); scrollToEnd(); }
  }

  // ---- Creation (CreationScreen.kt)
  function newConversation(chat) {
    const projects = S.data?.projects || []; const first = projects.find((p) => p.id === S.creation?.project_id) || mruProject() || projects[0];
    const harness = (S.data?.harnesses || []).find((h) => h.id === "claude-code") || S.data?.harnesses?.[0];
    S.creation = { chat, title: "", prompt: "", runNow: true, project_id: first?.id || "", board_id: (S.data?.boards || []).find((b) => b.project_id === first?.id)?.id || "",
      checkout_id: first?.checkouts?.[0]?.id || "", provider: load("provider", harness?.id || "claude-code"), model: "", effort: "", workspace: "project", branch: "", base: "", vault: false, mail: false };
    const h = (S.data?.harnesses || []).find((x) => x.id === S.creation.provider); S.creation.model = load("model-" + S.creation.provider, h?.default_model || h?.models?.[0]?.id || "");
    S.modal = "creation"; render(); setTimeout(() => $("#c-prompt")?.focus(), 50);
  }
  function creationScreen() {
    const c = S.creation; const chat = c.chat; const p = project(c.project_id);
    const boards = (S.data.boards || []).filter((b) => b.project_id === c.project_id); const b = boards.find((x) => x.id === c.board_id);
    const harness = (S.data.harnesses || []).find((h) => h.id === c.provider); const model = harness?.models.find((m) => m.id === c.model);
    const efforts = model?.efforts || [];
    const ready = false; // set on the DOM after rendering (see render); keeps the markup stable while typing
    const picker = (title, value, key, glyph, position, enabled = true) => listRow({ title, value: value || "Choose", glyph, position, act: enabled ? "picker" : "", extra: `data-key="${key}"`, trailing: ico("expand_more", 20, "c-secondary"), disabled: !enabled });
    const seg = (opts, on, act) => `<div class="seg">${opts.map((o, i) => `<button class="${on === i ? "on" : ""}" data-act="${act}" data-i="${i}">${on === i ? ico("check", 18) : ""}${esc(o)}</button>`).join("")}</div>`;
    const checkouts = p?.checkouts || []; const co = checkouts.find((x) => x.id === c.checkout_id);
    return `<div class="screen">${appbar({ title: chat ? "New chat" : "New task", cancel: true, confirm: `<button class="btn filled with-icon" style="margin-right:8px" data-act="submit-creation" ${ready ? "" : "disabled"}>${ico(chat || c.runNow ? "arrow_upward" : "check", 18)}${chat || c.runNow ? "Start" : "Add"}</button>` })}
      <div class="content" data-scroll="creation" style="padding-top:8px;padding-bottom:24px">
      <div class="compose-card"><input id="c-title" placeholder="Title (optional)" data-field="title"><div class="hairline" style="margin:0 16px"></div>
        <textarea id="c-prompt" placeholder="${chat ? "What would you like to talk about?" : "What should we do?"}" data-field="prompt"></textarea><div class="hairline" style="margin:0 16px"></div>
        ${filesRow("form")}<div class="attach-row"><button class="btn text with-icon" data-act="attach" data-target="form">${ico("attach_file", 18)}Attach</button><span class="hint">Up to 4 · 20 MB each</span></div></div>
      ${chat ? "" : `<div class="m16" style="padding-top:16px">${seg(["Start now", "Add to Todo"], c.runNow ? 0 : 1, "when")}</div>`}
      ${sectionHeader("Destination")}<div class="group">${picker("Project", p?.name, "project", "folder", chat ? "first" : "first")}${chat ? "" : picker("Board", b?.name, "board", "view_kanban", "middle", boards.length > 0)}${picker("Run on", co ? cardMachineName(co) : "", "checkout", "computer", "last", checkouts.length > 0)}</div>
      ${co ? sectionFooter("Checkout: " + co.name) : ""}
      ${sectionHeader("Agent")}<div class="group">${picker("Agent", HARNESS_NAMES[c.provider] || harness?.name || c.provider, "provider", "auto_awesome", "first")}${picker("Model", model?.name || c.model, "model", "memory", efforts.length ? "middle" : "last")}${efforts.length ? picker("Effort", c.effort ? c.effort[0].toUpperCase() + c.effort.slice(1) : "Default", "effort", "bolt", "last") : ""}</div>
      ${sectionHeader("Workspace")}<div class="m16"><div class="ccard" style="border-radius:22px;padding:12px;display:flex;flex-direction:column;gap:10px">${seg(["New worktree", "Project directory"], c.workspace === "worktree" ? 0 : 1, "workspace")}
        ${c.workspace === "worktree" ? `<div class="outlined-field"><label>Branch</label><input data-field="branch" placeholder="Branch (automatic)"></div><div class="outlined-field"><label>Base branch</label><input data-field="base" placeholder="Base branch"></div>` : ""}</div></div>
      ${sectionFooter(c.workspace === "worktree" ? "The agent works on its own branch in a separate worktree." : "The agent edits the project directory in place.")}
      ${sectionHeader("Access")}<div class="group">${listRow({ title: "Allow vault access", subtitle: "The agent may use the account's passwords and TOTP codes", position: "first", trailing: `<button class="switch ${c.vault ? "on" : ""}" data-act="toggle" data-field="vault"></button>` })}
        ${listRow({ title: "Allow mail to you", subtitle: "The agent may send you read-only reports in Mail", position: "last", trailing: `<button class="switch ${c.mail ? "on" : ""}" data-act="toggle" data-field="mail"></button>` })}</div>${sectionFooter("Set only when creating.")}
      <div class="m16" style="padding:16px 16px"><button class="btn filled large" style="width:100%;margin-top:8px" data-act="submit-creation" ${ready ? "" : "disabled"}>${S.busy ? `<span class="spin"></span>` : ""}${chat || c.runNow ? "Start working" : "Add to Todo"}</button></div></div></div>`;
  }
  async function submitCreation() {
    const c = S.creation; if (!c || S.busy) return;
    S.busy = true; render();
    try {
      const body = { project: c.project_id, title: c.title.trim(), prompt: c.prompt.trim(), provider: c.provider, model: c.model, effort: c.effort, workspace: c.workspace, start: c.chat || c.runNow, checkout: c.checkout_id };
      if (!c.chat) body.board = c.board_id; if (c.workspace === "worktree" && c.branch) body.branch = c.branch;
      save("provider", c.provider); save("model-" + c.provider, c.model); touchProject(c.project_id);
      if (S.files.length) body.attachments = await uploadFiles("form");
      const r = await api(c.chat ? "api/chat" : "api/card", body);
      clearFiles("form");
      S.modal = null; S.creation = null; S.busy = false;
      if (r.id) { await refreshState(); openConversation(r.id); } else render();
    } catch (e) { S.busy = false; snackbar(e.message); render(); }
  }
  // ---- add a repository (clone + register on this machine; any number of repositories)
  function addRepository() { S.repo = { git_url: "", name: "", branch: "" }; S.modal = "repo"; render(); setTimeout(() => $("#r-url")?.focus(), 50); }
  function addFolder() { S.repo = { kind: "folder", name: "" }; S.modal = "repo"; render(); setTimeout(() => $("#r-name")?.focus(), 50); }
  const repoReady = (r) => !!r && (r.kind === "folder" ? r.name.trim() : r.git_url.trim());
  function repoScreen() {
    const r = S.repo; const ready = false; // toggled on the DOM after rendering
    if (r.kind === "folder") return `<div class="screen">${appbar({ title: "New folder", cancel: true, confirm: `<button class="btn filled with-icon" style="margin-right:8px" data-act="submit-repo" ${ready ? "" : "disabled"}>${ico("create_new_folder", 18)}Create</button>` })}
      <div class="content" data-scroll="repo" style="padding-top:8px;padding-bottom:24px">
      <div class="m16" style="display:flex;flex-direction:column;gap:12px;padding-top:8px">
        <div class="outlined-field"><label>Name</label><input id="r-name" data-field="name" placeholder="Folder name" autocapitalize="off" spellcheck="false"></div>
      </div>
      ${sectionFooter("Creates an empty Git repository in /data/projects/<name> on this machine and registers it as a project. A folder that already exists there is adopted. Letters, digits, . _ - only.")}
      <div class="m16" style="padding:16px 16px"><button class="btn filled large" style="width:100%" data-act="submit-repo" ${ready ? "" : "disabled"}>${S.busy ? `<span class="spin"></span>` : ""}${S.busy ? "Creating…" : "Create folder"}</button></div></div></div>`;
    return `<div class="screen">${appbar({ title: "Add repository", cancel: true, confirm: `<button class="btn filled with-icon" style="margin-right:8px" data-act="submit-repo" ${ready ? "" : "disabled"}>${ico("download", 18)}Clone</button>` })}
      <div class="content" data-scroll="repo" style="padding-top:8px;padding-bottom:24px">
      <div class="m16" style="display:flex;flex-direction:column;gap:12px;padding-top:8px">
        <div class="outlined-field"><label>Git URL</label><input id="r-url" data-field="git_url" placeholder="https://github.com/owner/repo.git" autocapitalize="off" spellcheck="false"></div>
        <div class="outlined-field"><label>Name</label><input data-field="name" placeholder="Project name (from the URL)"></div>
        <div class="outlined-field"><label>Branch</label><input data-field="branch" placeholder="Base branch (default of the repository)"></div>
      </div>
      ${sectionFooter("The repository is cloned into /data/projects on this machine and registered as a project. Private GitHub repositories use the add-on's GitHub token.")}
      <div class="m16" style="padding:16px 16px"><button class="btn filled large" style="width:100%" data-act="submit-repo" ${ready ? "" : "disabled"}>${S.busy ? `<span class="spin"></span>` : ""}${S.busy ? "Cloning…" : "Clone and add"}</button></div></div></div>`;
  }
  async function submitRepository() {
    const r = S.repo; if (!r || S.busy || !repoReady(r)) return;
    S.busy = true; render();
    try {
      const res = await api("api/project", r.kind === "folder" ? { folder: r.name.trim() } : { git_url: r.git_url.trim(), name: r.name.trim(), branch: r.branch.trim() });
      S.modal = null; S.repo = null; S.busy = false;
      await refreshState(); render(); snackbar(res.existed ? `${res.name} was already registered.` : `${res.name} added.`);
    } catch (e) { S.busy = false; snackbar(e.message); render(); }
  }
  async function refreshState() { try { const d = await api("api/state"); S.data = d; } catch (e) { /* stream will catch up */ } }

  // ---- dialogs, menus, snackbar
  function openPicker(key) {
    const c = S.creation; let title, options, selected;
    if (key === "project") { title = "Project"; options = (S.data.projects || []).map((p) => [p.id, p.name]); selected = c.project_id; }
    else if (key === "board") { title = "Board"; options = (S.data.boards || []).filter((b) => b.project_id === c.project_id).map((b) => [b.id, b.name]); selected = c.board_id; }
    else if (key === "checkout") { title = "Run on"; options = (project(c.project_id)?.checkouts || []).map((x) => [x.id, cardMachineName(x) + ((project(c.project_id)?.checkouts || []).length > 1 ? " · " + x.name : "")]); selected = c.checkout_id; }
    else if (key === "provider") { title = "Agent"; options = (S.data.harnesses || []).map((h) => [h.id, h.name]); selected = c.provider; }
    else if (key === "model") { title = "Model"; options = ((S.data.harnesses || []).find((h) => h.id === c.provider)?.models || []).map((m) => [m.id, m.name]); selected = c.model; }
    else if (key === "effort") { title = "Effort"; const m = (S.data.harnesses || []).find((h) => h.id === c.provider)?.models.find((x) => x.id === c.model); options = [["", "Default"], ...(m?.efforts || []).map((e) => [e, e[0].toUpperCase() + e.slice(1)])]; selected = c.effort; }
    showDialog(title, options, selected, (v) => {
      if (key === "project") { c.project_id = v; c.board_id = (S.data.boards || []).find((b) => b.project_id === v)?.id || ""; c.checkout_id = project(v)?.checkouts?.[0]?.id || ""; }
      else if (key === "provider") { c.provider = v; const h = (S.data.harnesses || []).find((x) => x.id === v); c.model = load("model-" + v, h?.default_model || h?.models?.[0]?.id || ""); c.effort = ""; }
      else if (key === "model") { c.model = v; c.effort = ""; }
      else c[key === "checkout" ? "checkout_id" : key === "board" ? "board_id" : key] = v;
      render();
    });
  }
  function agentSheet() {
    const c = conv(S.conv.id); if (!c) return;
    const h = (S.data.harnesses || []).find((x) => x.id === c.provider); const cur = S.conv.agent?.model || c.model;
    showDialog("Model", (h?.models || []).map((m) => [m.id, m.name]), cur, (v) => {
      S.conv.agent = { ...(S.conv.agent || {}), model: v }; const m = h?.models.find((x) => x.id === v);
      if (m?.efforts?.length) showDialog("Effort", [["", "Default"], ...m.efforts.map((e) => [e, e[0].toUpperCase() + e.slice(1)])], S.conv.agent.effort || c.effort || "", (ef) => { S.conv.agent.effort = ef; render(); }); else render();
    });
  }
  // ---- Claude Code sign-in (add-on): the bridge runs `claude auth login`, the user opens the link and pastes the code
  // ---- the add-on's own SSH key (generated in /data/.ssh on first start; authorize it on other machines)
  function sshKeySection() {
    if (!S.data.addon) return "";
    if (!S.ssh) { S.ssh = { loading: true }; api("api/ssh").then((r) => { S.ssh = r; render(); }).catch((e) => { S.ssh = { error: e.message }; render(); }); }
    const k = S.ssh;
    let body;
    if (k.loading) body = `<div class="t-body c-secondary" style="padding:12px 16px"><span class="spin"></span> Loading…</div>`;
    else if (k.error || !k.public_key) body = `<div class="t-body c-secondary" style="padding:12px 16px">${esc(k.error || "No key yet: the add-on generates one at its next start.")}</div>`;
    else body = `<div style="padding:12px 16px;display:flex;flex-direction:column;gap:10px">
        <div class="t-footnote c-secondary">Fingerprint ${esc(k.fingerprint || "")}</div>
        <code data-ssh-key style="font:12px/16px ui-monospace,monospace;word-break:break-all;user-select:all">${esc(k.public_key)}</code>
        <div style="display:flex;gap:8px;flex-wrap:wrap"><button class="btn text with-icon" data-act="ssh-copy">${ico("content_copy", 18)}Copy key</button></div>
        <div class="outlined-field"><label>Test connection</label><input id="ssh-target" placeholder="user@host" autocapitalize="off" spellcheck="false" autocomplete="off"></div>
        <div style="display:flex;align-items:center;gap:12px"><button class="btn filled" data-act="ssh-test">Test</button><span class="t-footnote" data-ssh-result></span></div></div>`;
    return sectionHeader("SSH key of this add-on") + `<div class="group" data-ssh>${body}</div>` +
      sectionFooter("Add the public key to ~/.ssh/authorized_keys on the computers Dieter should reach (Git remotes over SSH, other machines). The private key stays in /data of the add-on.");
  }
  async function sshCopy() {
    const key = $("[data-ssh-key]")?.textContent || "";
    try { await navigator.clipboard.writeText(key); snackbar("Public key copied."); } catch (e) { snackbar("Copy failed: select the key and copy it by hand."); }
  }
  async function sshTest() {
    const inp = $("#ssh-target"), out = $("[data-ssh-result]"), btn = $('[data-act="ssh-test"]'); if (!inp || !out) return;
    const target = inp.value.trim(); if (!target) { inp.focus(); return; }
    btn.disabled = true; out.textContent = "Connecting…";
    try { const r = await api("api/ssh/test", { target }); out.textContent = r.ok ? `Connected: ${r.output}` : `Failed: ${r.error}`; out.style.color = r.ok ? "var(--success)" : "var(--error,#d33)"; }
    catch (e) { out.textContent = e.message; out.style.color = "var(--error,#d33)"; }
    btn.disabled = false;
  }
  function claudeAccountSection() {
    const auth = S.data.claude_auth; if (!auth) return "";
    const hasQuota = (S.data.quota || []).some((a) => a.provider === "anthropic_claude" && a.windows.length);
    let text, action;
    if (auth.method === "login") { text = `Signed in with your Claude account${auth.plan ? ` (${auth.plan})` : ""}.${hasQuota ? "" : " Quota appears after the next refresh."}`; action = `<button class="btn text" data-act="claude-logout">Sign out</button>`; }
    else if (auth.method === "token") { text = "Using the setup-token from the add-on options. Such tokens cannot read the quota, so nothing is shown for Claude Code. Sign in with your Claude account to see it."; action = `<button class="btn filled" data-act="claude-login">Sign in</button>`; }
    else { text = "Not signed in: the Claude Code agent cannot run. Sign in with your Claude account (subscription) here, or put a setup-token into the add-on options."; action = `<button class="btn filled" data-act="claude-login">Sign in</button>`; }
    return sectionHeader("Claude Code account") + `<div class="group" data-claude-account><div class="row" style="display:flex;align-items:center;gap:12px;padding:12px 16px"><div class="t-body" style="flex:1">${esc(text)}</div>${action}</div></div>`;
  }
  async function claudeLogin() {
    const el = $("#overlay"); S.dialog = { title: "Claude sign-in" };
    const box = (inner) => { el.innerHTML = `<div class="scrim" data-act="close-dialog"><div class="dialog" data-claude-login><div class="dt">Sign in to Claude</div>${inner}</div></div>`; el.classList.remove("hidden"); };
    box(`<div class="t-body" style="padding:8px 0"><span class="spin"></span> Starting the sign-in…</div><div class="acts"><button class="btn text" data-act="claude-login-cancel">Cancel</button></div>`);
    let st;
    try { st = await api("api/claude/login", {}); } catch (e) { box(`<div class="t-body" style="color:var(--error,#d33)" style="padding:8px 0">${esc(e.message)}</div><div class="acts"><button class="btn text" data-act="claude-login-cancel">Close</button></div>`); return; }
    if (!st.url) { box(`<div class="t-body" style="color:var(--error,#d33)" style="padding:8px 0">${esc(st.done?.error || "claude did not print a sign-in link")}</div><div class="acts"><button class="btn text" data-act="claude-login-cancel">Close</button></div>`); return; }
    box(`<div class="t-body" style="padding:8px 0;display:flex;flex-direction:column;gap:12px">
        <div>1. Open the sign-in page and allow Claude Code. You get a code.</div>
        <a class="btn filled with-icon" href="${esc(st.url)}" target="_blank" rel="noopener" data-claude-link style="text-decoration:none;justify-content:center">${ico("open_in_new", 18)}Open claude.com</a>
        <div>2. Paste the code here.</div>
        <div class="outlined-field"><label>Code</label><input id="claude-code" autocapitalize="off" spellcheck="false" autocomplete="off"></div>
        <div class="t-footnote" style="color:var(--error,#d33)" data-claude-error></div></div>
      <div class="acts"><button class="btn text" data-act="claude-login-cancel">Cancel</button><button class="btn filled" data-act="claude-login-code">Sign in</button></div>`);
    setTimeout(() => $("#claude-code")?.focus(), 50);
  }
  async function claudeLoginCode() {
    const inp = $("#claude-code"), err = $("[data-claude-error]"), btn = $('[data-act="claude-login-code"]'); if (!inp) return;
    const code = inp.value.trim(); if (!code) { inp.focus(); return; }
    btn.disabled = true; btn.innerHTML = `<span class="spin"></span>`; if (err) err.textContent = "";
    try {
      const r = await api("api/claude/login/code", { code });
      if (!r.ok) throw new Error(r.error || "sign-in failed");
      closeDialog(); snackbar("Signed in to Claude. The agent restarts with the new login."); await refreshState(); render();
    } catch (e) { if (err) err.textContent = e.message; btn.disabled = false; btn.textContent = "Sign in"; if (/start again|no sign-in/.test(e.message)) { closeDialog(); } }
  }
  async function claudeLoginCancel() { closeDialog(); try { await api("api/claude/login/cancel", {}); } catch (e) { /* ignore */ } }
  async function claudeLogout() { try { await api("api/claude/logout", {}); snackbar("Signed out of Claude."); await refreshState(); render(); } catch (e) { snackbar(e.message); } }
  function showDialog(title, options, selected, onPick) {
    S.dialog = { title, options, selected, onPick }; const el = $("#overlay");
    el.innerHTML = `<div class="scrim" data-act="close-dialog"><div class="dialog"><div class="dt">${esc(title)}</div><div>${options.map(([v, l]) => `<div class="opt" data-act="pick" data-value="${esc(v)}"><span class="radio ${v === selected ? "on" : ""}"></span><span>${esc(l)}</span></div>`).join("")}</div><div class="acts"><button class="btn text" data-act="close-dialog">Cancel</button></div></div></div>`;
    el.classList.remove("hidden");
  }
  function closeDialog() { S.dialog = null; $("#overlay").innerHTML = ""; $("#overlay").classList.add("hidden"); }
  function showMenu(anchor, items) {
    closeMenu();
    const m = document.createElement("div"); m.className = "menu";
    m.innerHTML = items.map((it) => it === "-" ? `<div class="sep"></div>` : it.title ? `<div class="mt">${esc(it.title)}</div>` : `<div class="mi ${it.destructive ? "destructive" : ""}" data-mi="${it.id}">${it.icon ? ico(it.icon, 22) : it.checked ? ico("check", 22) : `<span style="width:22px"></span>`}<div><div>${esc(it.label)}</div>${it.sub ? `<div class="sub">${esc(it.sub)}</div>` : ""}</div></div>`).join("");
    document.body.appendChild(m);
    const r = anchor.getBoundingClientRect(); const w = m.offsetWidth, h = m.offsetHeight;
    m.style.left = Math.max(8, Math.min(innerWidth - w - 8, r.right - w)) + "px"; m.style.top = (r.bottom + h > innerHeight - 8 ? Math.max(8, r.top - h) : r.bottom) + "px";
    m.addEventListener("click", (e) => { const mi = e.target.closest("[data-mi]"); if (!mi) return; const it = items.find((x) => x.id === mi.dataset.mi); closeMenu(); it?.onClick?.(); });
    S.menu = m; setTimeout(() => document.addEventListener("click", closeMenu, { once: true }), 0);
  }
  function closeMenu() { if (S.menu) { S.menu.remove(); S.menu = null; } }
  function snackbar(text) { const s = $("#snackbar"); s.innerHTML = `<span>${esc(text)}</span><button class="iconbtn small x" data-act="close-snack">${ico("close", 20)}</button>`; s.classList.add("show"); clearTimeout(s._t); s._t = setTimeout(() => s.classList.remove("show"), 6000); }
  function cardMenu(c) {
    const items = [];
    if (c.kind !== "chat") { items.push({ title: "Move to" }); Object.entries(LANES).forEach(([id, name]) => items.push({ id: "lane-" + id, label: name, checked: c.lane === id, onClick: () => action(`api/card/${c.id}/move`, { lane: id }) })); items.push("-"); }
    if (isActive(c.runtime)) items.push({ id: "cancel", label: "Stop agent", icon: "stop", destructive: true, onClick: () => action(`api/${c.kind}/${c.id}/cancel`, {}) });
    if (c.lane === "todo" && !c.initial_prompt_sent) items.push({ id: "start", label: "Start agent", icon: "play_arrow", onClick: () => action(`api/card/${c.id}/start`, {}) });
    items.push({ id: "pin", label: S.pins[c.id] ? "Unpin" : "Pin", icon: "push_pin", onClick: () => { S.pins[c.id] = !S.pins[c.id]; if (!S.pins[c.id]) delete S.pins[c.id]; save("pins", S.pins); render(); } });
    items.push({ id: "copy", label: "Copy ID", icon: "content_copy", onClick: () => navigator.clipboard?.writeText(c.id) });
    items.push("-", { id: "archive", label: "Archive", icon: "archive", destructive: true, onClick: () => action(`api/${c.kind}/${c.id}/archive`, {}) });
    return items;
  }
  async function action(path, body, done) {
    try { await api(path, body); S.conv.awaiting = path.endsWith("/start"); await refreshState(); render(); done?.(); } catch (e) { snackbar(e.message); }
  }

  // ------------------------------------------------------------------ events
  document.addEventListener("click", (e) => {
    const el = e.target.closest("[data-act]"); if (!el) return;
    const act = el.dataset.act, id = el.dataset.id;
    switch (act) {
      case "tab": selectTab(el.dataset.tab); break;
      case "back": pop(); break;
      case "new": newConversation(S.tab === "chats"); break;
      case "new-chat": newConversation(true); break;
      case "cancel-modal": S.modal = null; S.creation = null; S.repo = null; clearFiles("form"); render(); break;
      case "open": openConversation(id); break;
      case "project": touchProject(id); showFrom(null, { kind: "project", id }); break;
      case "board": { touchProject(board(id)?.project_id); const base = top().kind === "project" ? top() : null; showFrom(base, { kind: "board", id }); break; }
      case "lane": S.lanePage[el.dataset.board] = el.dataset.lane; render(); break;
      case "sort": { const k = el.dataset.board + ":" + el.dataset.lane; S.boardSort[k] = !(S.boardSort[k] ?? true); render(); break; }
      case "inbox-filter": S.inboxFilter = el.dataset.filter; render(); break;
      case "clear-search": S.search[el.dataset.key] = ""; render(); break;
      case "collapse": S.collapsed[el.dataset.key] = !S.collapsed[el.dataset.key]; save("collapsed", S.collapsed); render(); break;
      case "pin": e.stopPropagation(); S.pins[id] = !S.pins[id]; if (!S.pins[id]) delete S.pins[id]; save("pins", S.pins); render(); break;
      case "finish": e.stopPropagation(); action(`api/card/${id}/move`, { lane: "done" }); break;
      case "start": e.stopPropagation(); action(`api/card/${id}/start`, {}); break;
      case "move": action(`api/card/${id}/move`, { lane: el.dataset.lane }); break;
      case "cancel": action(`api/${S.conv.kind || "card"}/${id}/cancel`, {}); break;
      case "send": send(); break;
      case "jump": S.conv.following = true; scrollToEnd(); render(); break;
      case "expand": S.conv.expanded[id] = !S.conv.expanded[id]; render(); break;
      case "tool": if (el.dataset.preview) snackbar(el.dataset.preview); break;
      case "pane": push({ kind: "pane", id, pane: el.dataset.pane }); break;
      case "tool-page": case "tool": break;
      case "machine": push({ kind: "machine", id }); break;
      case "file-entry": { const r = top(); if (r?.kind !== "tool") break; const dir = el.dataset.kind === "dir";
        push({ kind: "tool", page: "files", projectId: r.projectId, cardId: r.cardId, path: dir ? el.dataset.path : r.path, file: dir ? undefined : el.dataset.path }); break; }
      case "files-hidden": S.showHidden = !S.showHidden; render(); break;
      case "schedule": { const r = top(); push({ kind: "tool", page: "schedules", scheduleId: id, projectId: r?.projectId }); break; }
      case "dev-refresh": S.dev.data = undefined; render(); break;
      case "dev-approve": approveDevice(); break;
      case "dev-revoke": e.stopPropagation(); revokeDevice(id, el.dataset.name); break;
      case "sched-new": openScheduleForm(el.dataset.project || top()?.projectId); break;
      case "sched-refresh": S.sch.list = {}; render(); break;
      case "sched-pick": schedPick(el.dataset.key); break;
      case "sched-seg": { const f = S.sch.form; if (!f) break; f[el.dataset.field] = el.dataset.field === "action" ? (el.dataset.i === "0" ? "run" : "draft") : (el.dataset.i === "0" ? "worktree" : "project"); render(); break; }
      case "sched-toggle-field": { const f = S.sch.form; if (f) { f[el.dataset.field] = !f[el.dataset.field]; render(); } break; }
      case "sched-submit": submitSchedule(); break;
      case "sched-run": scheduleAction(id, "run", (r) => snackbar(r.run?.message ? `Run: ${r.run.message}` : "Run started")); break;
      case "sched-toggle": scheduleAction(id, el.dataset.enabled === "1" ? "pause" : "resume", () => snackbar(el.dataset.enabled === "1" ? "Paused" : "Resumed")); break;
      case "sched-card": refreshState().then(() => openConversation(id)); break;
      case "files-refresh": if (S.files) delete S.files[el.dataset.key]; render(); break;
      case "file-download": downloadFile(el.dataset.key); break;
      case "refresh": refreshState().then(render); break;
      case "agent-sheet": agentSheet(); break;
      case "attach": pickFiles(el.dataset.target || (S.modal ? "form" : "conv")); break;
      case "rm-file": removeFile(el.dataset.target, Number(el.dataset.i)); break;
      case "q-opt": questionToggle(el.dataset.q, el.dataset.x, el.dataset.o); break;
      case "q-answer": questionSubmit(el.dataset.q, false); break;
      case "q-decline": questionSubmit(el.dataset.q, true); break;
      case "picker": openPicker(el.dataset.key); break;
      case "pick": { const d = S.dialog; closeDialog(); d?.onPick(el.dataset.value); break; } // close first: onPick may open the next dialog (model → effort)
      case "close-dialog": if (el.tagName === "BUTTON" || e.target === el) { if ($("[data-claude-login]")) claudeLoginCancel(); else closeDialog(); } break; // the Cancel button or the scrim itself; clicks inside the dialog body do nothing
      case "close-snack": $("#snackbar").classList.remove("show"); break;
      case "when": S.creation.runNow = el.dataset.i === "0"; render(); break;
      case "workspace": S.creation.workspace = el.dataset.i === "0" ? "worktree" : "project"; render(); break;
      case "toggle": S.creation[el.dataset.field] = !S.creation[el.dataset.field]; render(); break;
      case "submit-creation": submitCreation(); break;
      case "submit-repo": submitRepository(); break;
      case "ssh-copy": sshCopy(); break;
      case "ssh-test": sshTest(); break;
      case "claude-login": claudeLogin(); break;
      case "claude-login-code": claudeLoginCode(); break;
      case "claude-login-cancel": claudeLoginCancel(); break;
      case "claude-logout": claudeLogout(); break;
      case "menu": e.stopPropagation(); openMenu(el); break;
    }
    if (act === "tool" && el.dataset.page) { const base = el.dataset.project ? { kind: "project", id: el.dataset.project } : null; if (S.tab === "tools" || !base) { S.tab = S.tab === "inbox" || S.tab === "tools" ? S.tab : S.tab; } showFrom(base && S.tab === "projects" ? base : null, { kind: "tool", page: el.dataset.page, projectId: el.dataset.project }); }
  });
  document.addEventListener("input", (e) => {
    const s = e.target.closest("[data-search]"); if (s) { S.search[s.dataset.search] = s.value; const f = e.target; render(); const n = $(`[data-search="${s.dataset.search}"]`); if (n && n !== f) { n.focus(); n.setSelectionRange(n.value.length, n.value.length); } return; }
    const qf = e.target.closest("[data-qfree]"); if (qf) { const q = (S.data.questions || []).find((z) => z.id === qf.dataset.qfree); if (q) { answerState(q)[qf.dataset.x].freeform = qf.value; const card = qf.closest("[data-question]"); const btn = card?.querySelector('[data-act="q-answer"]'); if (btn) btn.disabled = false; } return; }
    const df = e.target.closest("[data-dfield]"); if (df && S.dev) { S.dev[df.dataset.dfield] = df.value; const ok = df.value.replace(/[\s-]/g, "").length === 16 && !S.busy; document.querySelectorAll('[data-act="dev-approve"]').forEach((b) => (b.disabled = !ok)); return; }
    const sf = e.target.closest("[data-sfield]"); if (sf && S.sch?.form) { S.sch.form[sf.dataset.sfield] = sf.value; schedFormSync(); if (sf.dataset.sfield === "cron") schedPreviewDebounced(); return; }
    const f = e.target.closest("[data-field]"); if (f && S.modal === "repo" && S.repo) { S.repo[f.dataset.field] = f.value; document.querySelectorAll('[data-act="submit-repo"]').forEach((b) => (b.disabled = !repoReady(S.repo))); return; }
    if (f && S.creation) { S.creation[f.dataset.field] = f.value; const ready = S.creation.prompt.trim() && S.creation.project_id && (S.creation.chat || S.creation.board_id); document.querySelectorAll('[data-act="submit-creation"]').forEach((b) => (b.disabled = !ready)); }
  });
  function openMenu(el) {
    const menu = el.dataset.menu, id = el.dataset.id;
    if (menu === "card" || menu === "conversation") { const c = conv(id); if (!c) return; showMenu(el, [...(menu === "conversation" ? [{ id: "changes", label: "Changes", icon: "difference", onClick: () => push({ kind: "pane", id, pane: "changes" }) }, { id: "files", label: "Files", icon: "folder", onClick: () => push({ kind: "tool", page: "files", cardId: id, projectId: c.project_id }) }, { id: "refresh", label: "Refresh", icon: "refresh", onClick: () => { S.conv.id = null; syncConversation(); } }, "-"] : []), ...cardMenu(c)]); }
    else if (menu === "board") { const b = board(id); const boards = (S.data.boards || []); showMenu(el, [{ title: "Switch board" }, ...boards.map((x) => ({ id: "b-" + x.id, label: x.name, sub: project(x.project_id)?.name, checked: x.id === id, onClick: () => { const st = stack(); S.stacks[S.tab] = st.map((r) => (r.kind === "board" ? { kind: "board", id: x.id } : r)); render(); } })), "-", { id: "archived", label: "Archived cards", icon: "archive", onClick: () => snackbar("Archived cards are listed in the Dieter app.") }]); }
    else if (menu === "schedule") { const d = S.sch?.detail?.[id]; const s = d?.schedule; if (!s) return; showMenu(el, [{ id: "run", label: "Run now", icon: "play_arrow", onClick: () => scheduleAction(id, "run", (r) => snackbar(r.run?.message ? `Run: ${r.run.message}` : "Run started")) },
      { id: "toggle", label: s.enabled ? "Pause" : "Resume", icon: s.enabled ? "pause" : "play_circle", onClick: () => scheduleAction(id, s.enabled ? "pause" : "resume") }, "-",
      { id: "delete", label: "Delete schedule", icon: "delete", destructive: true, onClick: () => { if (!confirm(`Delete the schedule "${s.name}"? Its cards and history stay.`)) return; scheduleAction(id, "delete", () => { const st = stack(); S.stacks[S.tab] = st.slice(0, -1); snackbar("Schedule deleted"); }); } }]); }
    else if (menu === "project") showMenu(el, [{ id: "open", label: "Open project", icon: "folder_open", onClick: () => showFrom(null, { kind: "project", id }) }, { id: "copy", label: "Copy path", icon: "content_copy", onClick: () => navigator.clipboard?.writeText(project(id)?.path || "") }]);
    else if (menu === "inbox-filter") showMenu(el, [{ title: "Project" }, { id: "all", label: "All projects", checked: !S.inboxProject, onClick: () => { S.inboxProject = ""; render(); } }, ...(S.data.projects || []).map((p) => ({ id: "p-" + p.id, label: p.name, checked: S.inboxProject === p.id, onClick: () => { S.inboxProject = p.id; render(); } })), "-", { id: "theme", label: "Theme: " + (load("theme", "system")), icon: "brightness_6", onClick: () => { const n = { system: "dark", dark: "light", light: "system" }[load("theme", "system")]; save("theme", n); applyTheme(); } }]);
    else if (menu === "projects-add") showMenu(el, [{ id: "np", label: "Add repository", icon: "create_new_folder", onClick: () => addRepository() }, { id: "nf", label: "New folder", icon: "folder", onClick: () => addFolder() }]);
    else if (menu === "chats") showMenu(el, [{ id: "live", label: "Current chats", icon: "forum", checked: true }, { id: "arch", label: "Archived chats", icon: "archive", onClick: () => snackbar("Archived chats are listed in the Dieter app.") }]);
  }
  // Escape closes what is on top: menu, dialog, then the New task / Add repository form
  document.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && e.target.id === "claude-code") { e.preventDefault(); claudeLoginCode(); return; }
    if (e.key === "Enter" && e.target.id === "ssh-target") { e.preventDefault(); sshTest(); return; }
    if (e.key === "Enter" && e.target.id === "dev-code") { e.preventDefault(); approveDevice(); return; }
    if (e.key !== "Escape") return;
    if (S.menu) { closeMenu(); return; }
    if (S.dialog) { if ($("[data-claude-login]")) claudeLoginCancel(); else closeDialog(); return; }
    if (S.modal) { S.modal = null; S.creation = null; S.repo = null; render(); }
  });
  addEventListener("resize", render);
  document.addEventListener("visibilitychange", () => { if (!document.hidden && S.es && S.es.readyState === 2) connect(); });

  // boot
  const startTab = q.get("tab");
  if (startTab && TABS.some((t) => t[0] === startTab)) S.tab = startTab;
  connect();
  function openAtStart(id) { const once = setInterval(() => { if (S.data) { clearInterval(once); const c = conv(id); if (c) { S.tab = c.kind === "chat" ? "chats" : "projects"; if (c.kind !== "chat" && c.board_id) S.stacks.projects = [{ kind: "root", tab: "projects" }, { kind: "board", id: c.board_id }]; openConversation(id); } } }, 150); }
  // ?card=ID (PC page), else the card HA registered before its push (Ingress deep link: /api/focus)
  const startCard = q.get("card");
  if (startCard) openAtStart(startCard);
  else if (!startTab && !SNAP) api("api/focus?consume=1").then((f) => { if (f && f.id) openAtStart(f.id); }).catch(() => {});
  if (!startCard && !startTab && S.data === null) { const once = setInterval(() => { if (S.data) { clearInterval(once); const m = mruProject(); const b = S.data.boards?.[0]; if ((m || b) && twoPane() && (S.stacks.projects || []).length <= 1) { S.stacks.projects = [{ kind: "root", tab: "projects" }, m ? { kind: "project", id: m.id } : { kind: "board", id: b.id }]; render(); } } }, 150); } // default board on wide screens, unless something was opened already
})();
