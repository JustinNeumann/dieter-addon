package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"github.com/dbpprt/dieter/internal/model"
	"github.com/dbpprt/dieter/internal/platform/fsx"
	"github.com/dbpprt/dieter/internal/replication/crdt"
	"github.com/dbpprt/dieter/internal/store"
	"github.com/dbpprt/dieter/internal/store/home"
	"github.com/dbpprt/dieter/internal/store/kanban"
	"github.com/dbpprt/dieter/internal/store/kv"
	"github.com/dbpprt/dieter/internal/store/replica"
	"github.com/dbpprt/dieter/internal/store/schedules"
)

func writeTestDaemonCredential(t *testing.T, storeDir, daemonID, gatewayURL string) ed25519.PrivateKey {
	t.Helper()
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	pkcs8, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		t.Fatalf("marshal pkcs8: %v", err)
	}
	pemBytes := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: pkcs8})
	daemonDir := filepath.Join(storeDir, "daemon")
	if err := os.MkdirAll(daemonDir, 0o700); err != nil {
		t.Fatalf("mkdir daemon: %v", err)
	}
	if err := fsx.WriteFile(filepath.Join(daemonDir, "identity-key.pem"), pemBytes, 0o600); err != nil {
		t.Fatalf("write identity-key.pem: %v", err)
	}
	identJSON, _ := json.Marshal(map[string]any{
		"id":             daemonID,
		"name":           "test",
		"gatewayUrl":     gatewayURL,
		"certificatePem": []byte("dummy-cert"),
	})
	if err := fsx.WriteFile(filepath.Join(daemonDir, "identity.json"), identJSON, 0o600); err != nil {
		t.Fatalf("write identity.json: %v", err)
	}
	return priv
}

func initGitRepo(t *testing.T, dir string) {
	t.Helper()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatalf("mkdir repo: %v", err)
	}
	for _, args := range [][]string{
		{"init", "--quiet", "--initial-branch=main"},
		{"-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--quiet", "--allow-empty", "-m", "init"},
	} {
		cmd := exec.Command("git", args...)
		cmd.Dir = dir
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("git %v: %v (%s)", args, err, string(out))
		}
	}
}

func archiveModeState(t *testing.T, storeDir, mode string) {
	t.Helper()
	modeDir := filepath.Join(storeDir, "gateway-modes", mode)
	_ = os.RemoveAll(modeDir)
	for _, rel := range []string{
		filepath.Join("daemon", "identity.json"),
		filepath.Join("daemon", "identity-key.pem"),
		filepath.Join("peers", "identity.json"),
	} {
		src := filepath.Join(storeDir, rel)
		dst := filepath.Join(modeDir, rel)
		if raw, err := os.ReadFile(src); err == nil {
			_ = os.MkdirAll(filepath.Dir(dst), 0o700)
			if err := fsx.WriteFile(dst, raw, 0o600); err != nil {
				t.Fatalf("archive %s: %v", rel, err)
			}
			_ = os.Remove(src)
		}
	}
}

func restoreModeState(t *testing.T, storeDir, fromMode, toMode string) {
	t.Helper()
	archiveModeState(t, storeDir, fromMode)
	modeDir := filepath.Join(storeDir, "gateway-modes", toMode)
	for _, rel := range []string{
		filepath.Join("daemon", "identity.json"),
		filepath.Join("daemon", "identity-key.pem"),
		filepath.Join("peers", "identity.json"),
	} {
		src := filepath.Join(modeDir, rel)
		dst := filepath.Join(storeDir, rel)
		if raw, err := os.ReadFile(src); err == nil {
			_ = os.MkdirAll(filepath.Dir(dst), 0o700)
			if err := fsx.WriteFile(dst, raw, 0o600); err != nil {
				t.Fatalf("restore %s: %v", rel, err)
			}
		}
	}
	_ = os.RemoveAll(modeDir)
}

func TestAdoptStoreBetweenExternalAndEmbeddedModes(t *testing.T) {
	root := t.TempDir()
	storeDir := filepath.Join(root, "dieter")
	repoPath := filepath.Join(root, "projects", "demo-repo")
	otherRepoPath := filepath.Join(root, "projects", "dummy-repo")
	initGitRepo(t, repoPath)
	initGitRepo(t, otherRepoPath)

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	// 1. Setup external mode and create project, board, card, chat, schedule, and Dreamfall KV.
	writeTestDaemonCredential(t, storeDir, "d_ext", "https://gw.example:4443")
	s := store.New(storeDir)
	if err := s.Ensure(); err != nil {
		t.Fatalf("ensure store: %v", err)
	}
	extPeer, err := s.Replica.BindPeerAccount("acc_external", "github:42", "d_ext", "gw-ext")
	if err != nil {
		t.Fatalf("bind external account: %v", err)
	}

	proj, err := s.Kanban.CreateProject(kanban.CreateProjectInput{
		Name:             "demo-repo",
		Path:             repoPath,
		InitialBoardName: "Main",
	})
	if err != nil {
		t.Fatalf("create project: %v", err)
	}
	board, err := s.Kanban.InitialBoard(proj.ID)
	if err != nil {
		t.Fatalf("initial board: %v", err)
	}
	card1, err := s.Kanban.CreateCard(kanban.CreateCardInput{
		Project: proj.ID,
		Board:   board.ID,
		Title:   "External task 1",
		Prompt:  "Prompt for external task 1",
	})
	if err != nil {
		t.Fatalf("create card1: %v", err)
	}
	chat1, err := s.Kanban.CreateChat(kanban.CreateCardInput{
		Project: proj.ID,
		Title:   "External inbox chat",
		Prompt:  "Hello from inbox",
	})
	if err != nil {
		t.Fatalf("create chat1: %v", err)
	}
	sched1, err := s.Schedules.Create(schedules.Input{
		Project:        proj.ID,
		Board:          board.ID,
		Name:           "Nightly check",
		Cron:           "0 3 * * *",
		Timezone:       "UTC",
		Action:         "draft",
		TitleTemplate:  "Nightly",
		PromptTemplate: "Run check",
		Enabled:        true,
	})
	if err != nil {
		t.Fatalf("create schedule: %v", err)
	}
	if _, err := s.KV.Mutate(extPeer, kv.Mutation{
		Namespace:   "dreamfall",
		Key:         "pipeline.df_1",
		OperationID: "op_df_1",
		DaemonID:    extPeer.DaemonID,
		Value:       json.RawMessage(`{"id":"df_1","name":"Dreamfall Pipeline"}`),
	}); err != nil {
		t.Fatalf("mutate dreamfall kv: %v", err)
	}

	// Also simulate a historical card on disk owned by an older daemon ID ("d_old_pi") with a stale checkout ID ("co_stale").
	orphanID := "c_orphan123456"
	orphanCard := model.Card{
		ID:            orphanID,
		OwnerDaemonID: "d_old_pi",
		CheckoutID:    "co_stale",
		Scope:         model.ConversationScopeBoard,
		ProjectID:     proj.ID,
		BoardID:       board.ID,
		Lane:          model.LaneDone,
		OrderKey:      "a0",
		Title:         "Historical Pi Card",
		InitialPrompt: "Historical prompt",
		Runtime:       "idle",
		CreatedAt:     home.Timestamp(),
		UpdatedAt:     home.Timestamp(),
	}
	orphanMD, err := home.MarshalMarkdown(orphanCard, orphanCard.InitialPrompt)
	if err != nil {
		t.Fatalf("marshal orphan md: %v", err)
	}
	if err := fsx.WriteFile(filepath.Join(s.CardDir(), orphanID+".md"), orphanMD, 0o600); err != nil {
		t.Fatalf("write orphan md: %v", err)
	}
	extView, err := s.Replica.OpenPeerView(extPeer.Account)
	if err != nil {
		t.Fatalf("open ext view: %v", err)
	}
	extView.State = replica.ClonePeerState(extView.State)
	for field, val := range map[string]any{
		"identity":  map[string]any{"id": orphanID, "projectId": proj.ID, "ownerDaemonId": "d_old_pi", "checkoutId": "co_stale", "scope": "board", "createdAt": orphanCard.CreatedAt},
		"title":     orphanCard.Title,
		"placement": map[string]any{"boardId": board.ID, "lane": "done", "orderKey": "a0", "phaseChangedAt": orphanCard.CreatedAt},
		"archived":  false,
		"summary":   map[string]any{"runtime": "idle", "responseSeq": 5, "seenResponseSeq": 5},
	} {
		recKey := crdt.Key("item", orphanID+"."+field)
		rec, err := crdt.Put(crdt.Record{}, "item", orphanID+"."+field, extPeer.Actor, "", replica.RawValue(val), false)
		if err != nil {
			t.Fatalf("put orphan %s: %v", field, err)
		}
		if field == "identity" || field == "summary" {
			_, oldPriv, _ := ed25519.GenerateKey(rand.Reader)
			rec.Versions[0] = crdt.SignOwnerVersion(extPeer.Account, rec, rec.Versions[0], "d_old_pi", oldPriv)
		}
		extView.Records[recKey] = rec
		extView.Dirty[recKey] = true
	}
	staleCoRec, err := crdt.Put(crdt.Record{}, "checkout", "co_stale.registration", extPeer.Actor, "", replica.RawValue(map[string]any{
		"id": "co_stale", "projectId": proj.ID, "daemonId": "d_old_pi", "name": "demo-repo", "detached": false,
	}), false)
	if err != nil {
		t.Fatalf("put stale checkout: %v", err)
	}
	_, oldPriv, _ := ed25519.GenerateKey(rand.Reader)
	staleCoRec.Versions[0] = crdt.SignOwnerVersion(extPeer.Account, staleCoRec, staleCoRec.Versions[0], "d_old_pi", oldPriv)
	extView.Records[crdt.Key("checkout", "co_stale.registration")] = staleCoRec
	extView.Dirty[crdt.Key("checkout", "co_stale.registration")] = true
	if err := s.Replica.SavePeerData(extPeer.Account, extView); err != nil {
		t.Fatalf("save orphan to ext view: %v", err)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("close store: %v", err)
	}

	// 2. Simulate switching to embedded mode where 0.6.431.39 had created an empty replica with a duplicate project and 0 cards.
	archiveModeState(t, storeDir, "external")
	writeTestDaemonCredential(t, storeDir, "d_emb", "http://127.0.0.1:4243")
	s = store.New(storeDir)
	if err := s.Ensure(); err != nil {
		t.Fatalf("ensure store embedded: %v", err)
	}
	embPeer, err := s.Replica.BindPeerAccount("acc_embedded", "github:1", "d_emb", "gw-emb")
	if err != nil {
		t.Fatalf("bind embedded account: %v", err)
	}
	dummyProj, err := s.Kanban.CreateProject(kanban.CreateProjectInput{
		Name: "dummy-repo",
		Path: otherRepoPath,
	})
	if err != nil {
		t.Fatalf("create dummy project: %v", err)
	}

	// 3. Run adoptStore in embedded mode!
	if err := adoptStore(s, storeDir, embPeer, true, logger); err != nil {
		t.Fatalf("adoptStore to embedded: %v", err)
	}
	if _, err := s.Kanban.RehomeCheckouts(); err != nil {
		t.Fatalf("rehome checkouts in embedded: %v", err)
	}

	// Verify dummy project was wiped because embedded had 0 items while external had items.
	if _, err := s.Kanban.ResolveProject(dummyProj.ID); err == nil {
		t.Fatalf("expected dummy project %s to be wiped when resetting empty target replica", dummyProj.ID)
	}

	// Verify real project, board, cards, chat, orphan card, schedule, and Dreamfall KV exist and are owned by d_emb!
	gotProj, err := s.Kanban.ResolveProject(proj.ID)
	if err != nil || gotProj.Path != repoPath {
		t.Fatalf("resolve adopted project: proj=%+v err=%v", gotProj, err)
	}
	cards, err := s.Kanban.ListAllCards(true)
	if err != nil {
		t.Fatalf("list all cards: %v", err)
	}
	if len(cards) != 3 {
		t.Fatalf("expected 3 items (card1, chat1, orphanCard), got %d: %+v", len(cards), cards)
	}
	for _, c := range cards {
		if c.OwnerDaemonID != "d_emb" {
			t.Fatalf("card %s ownerDaemonId = %q, want d_emb", c.ID, c.OwnerDaemonID)
		}
		detail, err := s.Kanban.ReadCardDetail(c.ID)
		if err != nil {
			t.Fatalf("ReadCardDetail(%s) failed: %v", c.ID, err)
		}
		if detail.Project.Path != repoPath {
			t.Fatalf("ReadCardDetail(%s) project path = %q, want %q", c.ID, detail.Project.Path, repoPath)
		}
	}
	ownedCards, err := s.Kanban.OwnedCards(context.Background())
	if err != nil || len(ownedCards) != 3 {
		t.Fatalf("OwnedCards = %d (err=%v), want 3", len(ownedCards), err)
	}
	ownedCheckouts, err := s.Kanban.OwnedCheckouts()
	if err != nil || len(ownedCheckouts) != 1 || ownedCheckouts[0].DaemonID != "d_emb" {
		t.Fatalf("OwnedCheckouts = %+v (err=%v), want 1 checkout owned by d_emb", ownedCheckouts, err)
	}
	schedPage, err := s.Schedules.ListSharedPage(proj.ID, 50, "")
	if err != nil || len(schedPage.Items) != 1 || schedPage.Items[0].ID != sched1.ID || schedPage.Items[0].OwnerDaemonID != "d_emb" {
		t.Fatalf("Schedules = %+v (err=%v), want schedule %s owned by d_emb", schedPage, err, sched1.ID)
	}
	embView, err := s.Replica.OpenPeerView(embPeer.Account)
	if err != nil {
		t.Fatalf("open embView: %v", err)
	}
	dfSelected, ok := crdt.Selected(embView.Record("kv.dreamfall/pipeline.df_1"))
	if !ok || string(dfSelected) != `{"id":"df_1","name":"Dreamfall Pipeline"}` {
		t.Fatalf("dreamfall kv in embedded = %s (ok=%v)", string(dfSelected), ok)
	}

	// Verify second run is idempotent.
	beforeSeq := embView.Sequence
	if err := adoptStore(s, storeDir, embPeer, true, logger); err != nil {
		t.Fatalf("second adoptStore to embedded: %v", err)
	}
	embViewAfter, err := s.Replica.OpenPeerView(embPeer.Account)
	if err != nil {
		t.Fatalf("open embViewAfter: %v", err)
	}
	if embViewAfter.Sequence != beforeSeq {
		t.Fatalf("expected idempotent second run to keep sequence %d, got %d", beforeSeq, embViewAfter.Sequence)
	}

	// 4. Create a new card in embedded mode and switch back to external mode!
	card2, err := s.Kanban.CreateCard(kanban.CreateCardInput{
		Project: proj.ID,
		Board:   board.ID,
		Title:   "Created in embedded mode",
		Prompt:  "Embedded prompt",
	})
	if err != nil {
		t.Fatalf("create card2 in embedded: %v", err)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("close embedded store: %v", err)
	}

	restoreModeState(t, storeDir, "embedded", "external")
	if err := runAdoptStore(storeDir, logger); err != nil {
		t.Fatalf("runAdoptStore in external mode: %v", err)
	}

	s = store.New(storeDir)
	extCards, err := s.Kanban.ListAllCards(true)
	if err != nil {
		t.Fatalf("list all cards in external: %v", err)
	}
	if len(extCards) != 4 {
		t.Fatalf("expected 4 items in external mode (including card2 created in embedded), got %d", len(extCards))
	}
	for _, id := range []string{card1.ID, chat1.ID, card2.ID} {
		detail, err := s.Kanban.ReadCardDetail(id)
		if err != nil {
			t.Fatalf("ReadCardDetail(%s) in external mode failed: %v", id, err)
		}
		if detail.Card.OwnerDaemonID != "d_ext" {
			t.Fatalf("card %s owner in external = %q, want d_ext", id, detail.Card.OwnerDaemonID)
		}
	}

	// 5. Create card3 in external mode and switch back to embedded mode (non-empty target replica).
	card3, err := s.Kanban.CreateCard(kanban.CreateCardInput{
		Project: proj.ID,
		Board:   board.ID,
		Title:   "Created in external mode after round-trip",
		Prompt:  "External round-trip prompt",
	})
	if err != nil {
		t.Fatalf("create card3 in external: %v", err)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("close external store: %v", err)
	}

	restoreModeState(t, storeDir, "external", "embedded")
	s = store.New(storeDir)
	defer s.Close()
	if err := adoptStore(s, storeDir, embPeer, true, logger); err != nil {
		t.Fatalf("adoptStore back to embedded: %v", err)
	}
	embCards, err := s.Kanban.ListAllCards(true)
	if err != nil {
		t.Fatalf("list all cards in embedded round-trip: %v", err)
	}
	if len(embCards) != 5 {
		t.Fatalf("expected 5 items in embedded mode after round-trip, got %d", len(embCards))
	}
	for _, id := range []string{card1.ID, chat1.ID, orphanID, card2.ID, card3.ID} {
		detail, err := s.Kanban.ReadCardDetail(id)
		if err != nil {
			t.Fatalf("ReadCardDetail(%s) in embedded round-trip failed: %v", id, err)
		}
		if detail.Card.OwnerDaemonID != "d_emb" {
			t.Fatalf("card %s owner in embedded round-trip = %q, want d_emb", id, detail.Card.OwnerDaemonID)
		}
	}
}
