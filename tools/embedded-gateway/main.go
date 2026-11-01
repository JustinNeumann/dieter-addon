package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/dbpprt/dieter/internal/gateway"
	gatewayv1 "github.com/dbpprt/dieter/internal/gen/dieter/gateway/v1"
	dieterv1 "github.com/dbpprt/dieter/internal/gen/dieter/v1"
	"github.com/dbpprt/dieter/internal/link"
	"github.com/dbpprt/dieter/internal/model"
	"github.com/dbpprt/dieter/internal/platform/fsx"
	"github.com/dbpprt/dieter/internal/replication/crdt"
	"github.com/dbpprt/dieter/internal/security/roster"
	"github.com/dbpprt/dieter/internal/store"
	"github.com/dbpprt/dieter/internal/store/home"
	"github.com/dbpprt/dieter/internal/store/kanban"
	"github.com/dbpprt/dieter/internal/store/replica"
	"google.golang.org/protobuf/types/known/emptypb"
)

const (
	embeddedGitHubID = int64(1)
	embeddedLogin    = "local"
)

type Bootstrap struct {
	GatewayURL   string `json:"gatewayUrl"`
	AuthSecret   string `json:"authSecret"`
	SessionToken string `json:"sessionToken"`
	PairingCode  string `json:"pairingCode"`
}

func main() {
	var (
		storeDir      = flag.String("store", "", "gateway state directory (server mode)")
		addr          = flag.String("addr", "127.0.0.1:4243", "gateway listen address")
		prepareDaemon = flag.String("prepare-daemon", "", "daemon store directory to enroll and initialize against the embedded gateway")
		adoptStoreDir = flag.String("adopt-store", "", "daemon store directory to adopt records and local files into the active gateway mode")
		daemonName    = flag.String("daemon-name", "Home Assistant", "daemon display name for enrollment")
		gatewayURL    = flag.String("gateway", "http://127.0.0.1:4243", "gateway URL for -prepare-daemon")
	)
	flag.Parse()

	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))
	if *prepareDaemon != "" {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		if err := runPrepareDaemon(ctx, *prepareDaemon, *daemonName, *gatewayURL, logger); err != nil {
			logger.Error("prepare-daemon failed", "error", err)
			os.Exit(1)
		}
		return
	}
	if *adoptStoreDir != "" {
		if err := runAdoptStore(*adoptStoreDir, logger); err != nil {
			logger.Error("adopt-store failed", "error", err)
			os.Exit(1)
		}
		return
	}
	if *storeDir == "" {
		fmt.Fprintln(os.Stderr, "one of -store, -prepare-daemon, or -adopt-store is required")
		os.Exit(2)
	}
	if err := runServer(*storeDir, *addr, logger); err != nil {
		logger.Error("embedded gateway failed", "error", err)
		os.Exit(1)
	}
}

func randomHex(bytesLen int) (string, error) {
	buf := make([]byte, bytesLen)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

func loadOrCreateBootstrap(storeDir, gatewayURL string) (*Bootstrap, error) {
	if err := os.MkdirAll(storeDir, 0o700); err != nil {
		return nil, err
	}
	path := filepath.Join(storeDir, "bootstrap.json")
	var b Bootstrap
	if raw, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(raw, &b)
	}
	changed := false
	if b.GatewayURL != gatewayURL {
		b.GatewayURL = gatewayURL
		changed = true
	}
	if secretBytes, err := hex.DecodeString(b.AuthSecret); err != nil || len(secretBytes) < 32 {
		secret, err := randomHex(32)
		if err != nil {
			return nil, err
		}
		b.AuthSecret = secret
		changed = true
	}
	if b.SessionToken == "" {
		tok, err := randomHex(24)
		if err != nil {
			return nil, err
		}
		b.SessionToken = "embedded_" + tok
		changed = true
	}
	if _, err := roster.ParsePairingCode(b.PairingCode); err != nil {
		_, code, err := roster.NewPairingSecret()
		if err != nil {
			return nil, err
		}
		b.PairingCode = code
		changed = true
	}
	if changed {
		raw, err := json.MarshalIndent(&b, "", "  ")
		if err != nil {
			return nil, err
		}
		tmp := path + ".tmp"
		if err := os.WriteFile(tmp, append(raw, '\n'), 0o600); err != nil {
			return nil, err
		}
		if err := os.Rename(tmp, path); err != nil {
			return nil, err
		}
	}
	return &b, nil
}

func runServer(storeDir, addr string, logger *slog.Logger) error {
	gatewayURL := "http://" + addr
	publicURL, err := url.Parse(gatewayURL)
	if err != nil {
		return fmt.Errorf("parse gateway URL: %w", err)
	}
	bootstrap, err := loadOrCreateBootstrap(storeDir, gatewayURL)
	if err != nil {
		return fmt.Errorf("bootstrap: %w", err)
	}
	authSecret, err := hex.DecodeString(bootstrap.AuthSecret)
	if err != nil {
		return fmt.Errorf("decode auth secret: %w", err)
	}
	gatewayStore, err := gateway.OpenStore(storeDir)
	if err != nil {
		return fmt.Errorf("open store: %w", err)
	}
	defer gatewayStore.Close()

	mac := hmac.New(sha256.New, authSecret)
	_, _ = mac.Write([]byte(bootstrap.SessionToken))
	digest := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	now := time.Now().UTC()
	if err := gatewayStore.UpdateAuthState(func(state *gateway.AuthState) error {
		filtered := state.Sessions[:0]
		for _, s := range state.Sessions {
			if s.TokenHash != digest && s.ExpiresAt.After(now) {
				filtered = append(filtered, s)
			}
		}
		state.Sessions = append(filtered, gateway.Session{
			TokenHash: digest,
			GitHubID:  embeddedGitHubID,
			Login:     embeddedLogin,
			CreatedAt: now,
			ExpiresAt: now.Add(10 * 365 * 24 * time.Hour),
		})
		return nil
	}); err != nil {
		return fmt.Errorf("seed session: %w", err)
	}

	server, err := gateway.NewServer(gateway.Config{
		Root:                 storeDir,
		Address:              addr,
		PublicURL:            publicURL,
		GitHubClientID:       "embedded",
		GitHubSecret:         "embedded",
		GitHubBaseURL:        "https://github.invalid",
		GitHubAPIURL:         "https://api.github.invalid",
		AllowedUserIDs:       map[int64]struct{}{embeddedGitHubID: {}},
		AuthSecret:           authSecret,
		SessionTTL:           10 * 365 * 24 * time.Hour,
		NativeRedirects:      map[string]struct{}{},
		DevInsecure:          true,
		RTCTTL:               5 * time.Minute,
		MinimumClientVersion: "0.0.0-dev.0",
		MinimumDaemonVersion: "0.0.0-dev.0",
	}, gatewayStore, logger)
	if err != nil {
		return fmt.Errorf("new server: %w", err)
	}

	baseHandler := server.HTTPHandler
	server.HTTPHandler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/BeginDaemonEnrollment") || strings.HasSuffix(r.URL.Path, "/CompleteDaemonEnrollment") {
			var activeDaemons int
			if err := gatewayStore.DB.QueryRow(`SELECT COUNT(*) FROM daemons WHERE revoked = 0`).Scan(&activeDaemons); err == nil {
				if activeDaemons > 0 {
					http.Error(w, "embedded gateway already has an enrolled daemon", http.StatusForbidden)
					return
				}
				if strings.HasSuffix(r.URL.Path, "/CompleteDaemonEnrollment") {
					_, _ = gatewayStore.DB.Exec(
						`UPDATE enrollments SET approved = 1, github_id = ?, login = ? WHERE approved = 0`,
						embeddedGitHubID, embeddedLogin,
					)
				}
			}
		}
		baseHandler.ServeHTTP(w, r)
	})

	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("listen %s: %w", addr, err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		server.APIGRPC.GracefulStop()
		_ = listener.Close()
	}()

	logger.Info("embedded gateway listening", "addr", addr)
	if err := server.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) && ctx.Err() == nil {
		return err
	}
	return nil
}

func waitForGateway(ctx context.Context, gatewayURL string) error {
	healthURL := strings.TrimRight(gatewayURL, "/") + "/healthz"
	client := &http.Client{Timeout: 2 * time.Second}
	for {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, healthURL, nil)
		if err == nil {
			resp, err := client.Do(req)
			if err == nil {
				_ = resp.Body.Close()
				if resp.StatusCode == http.StatusOK {
					return nil
				}
			}
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("embedded gateway at %s did not become healthy: %w", gatewayURL, ctx.Err())
		case <-time.After(150 * time.Millisecond):
		}
	}
}

func clearDaemonEnrollmentFiles(storeDir string) {
	for _, rel := range []string{
		filepath.Join("daemon", "identity.json"),
		filepath.Join("daemon", "identity-key.pem"),
		filepath.Join("daemon", "roster.pb"),
		filepath.Join("daemon", "trust.json"),
		filepath.Join("peers", "identity.json"),
	} {
		_ = os.Remove(filepath.Join(storeDir, rel))
	}
}

func verifyIdentityAccount(ctx context.Context, identity *link.Identity) (*gatewayv1.Account, error) {
	conn, err := link.DialGateway(ctx, identity, false)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	client := gatewayv1.NewGatewayServiceClient(link.PossessionConn(conn, identity))
	account, err := client.GetAccount(ctx, &emptypb.Empty{})
	if err != nil {
		return nil, err
	}
	if account.GetGithubId() <= 0 {
		return nil, errors.New("gateway returned empty account")
	}
	return account, nil
}

func ensureTrustedRoster(ctx context.Context, identity *link.Identity, logger *slog.Logger) error {
	trustStore, err := link.OpenTrust(identity, logger)
	if err != nil {
		return fmt.Errorf("open trust: %w", err)
	}
	defer trustStore.Close()

	_ = trustStore.Refresh(ctx)
	switch trustStore.State() {
	case dieterv1.TrustState_TRUST_STATE_TRUSTED:
		return nil
	case dieterv1.TrustState_TRUST_STATE_CONFLICT, dieterv1.TrustState_TRUST_STATE_REVOKED:
		if _, _, err := trustStore.Initialize(ctx, true); err != nil {
			return fmt.Errorf("replace device roster: %w", err)
		}
		return nil
	default:
		if _, _, err := trustStore.Initialize(ctx, false); err != nil {
			if _, _, replaceErr := trustStore.Initialize(ctx, true); replaceErr != nil {
				return fmt.Errorf("initialize device roster: %v (replace: %w)", err, replaceErr)
			}
		}
		return nil
	}
}

func runPrepareDaemon(ctx context.Context, storeDir, daemonName, gatewayURL string, logger *slog.Logger) error {
	if err := waitForGateway(ctx, gatewayURL); err != nil {
		return err
	}
	if err := os.MkdirAll(storeDir, 0o755); err != nil {
		return err
	}

	var (
		identity *link.Identity
		account  *gatewayv1.Account
	)
	if existing, err := link.LoadIdentity(storeDir); err == nil {
		if existing.Enrolled() && strings.TrimRight(existing.GatewayURL, "/") == strings.TrimRight(gatewayURL, "/") && !existing.CertificateExpired(time.Now()) {
			if acc, err := verifyIdentityAccount(ctx, existing); err == nil {
				identity = existing
				account = acc
			}
		}
		if identity == nil {
			clearDaemonEnrollmentFiles(storeDir)
		}
	}

	if identity == nil {
		created, err := link.LoadOrCreateEnrollmentIdentity(storeDir, daemonName, gatewayURL)
		if err != nil {
			return fmt.Errorf("create enrollment identity: %w", err)
		}
		enrollment, err := link.BeginEnrollment(ctx, created)
		if err != nil {
			return fmt.Errorf("begin enrollment: %w", err)
		}
		credential, err := link.CompleteEnrollment(ctx, created, enrollment.GetEnrollmentId(), enrollment.GetEnrollmentSecret())
		if err != nil {
			return fmt.Errorf("complete enrollment: %w", err)
		}
		created.GatewayIssuer = credential.GetGatewayIssuer()
		if err := created.SaveCredential(
			credential.GetDaemonId(),
			credential.GetDaemonName(),
			credential.GetCertificatePem(),
			credential.GetGatewaySigningPublicKey(),
			credential.GetExpiresAt(),
			credential.GetGeneration(),
		); err != nil {
			return fmt.Errorf("save credential: %w", err)
		}
		acc, err := verifyIdentityAccount(ctx, created)
		if err != nil {
			return fmt.Errorf("verify enrolled account: %w", err)
		}
		identity = created
		account = acc
	}

	if err := ensureTrustedRoster(ctx, identity, logger); err != nil {
		return err
	}

	s := store.New(storeDir)
	defer s.Close()
	if err := s.Ensure(); err != nil {
		return fmt.Errorf("ensure store: %w", err)
	}
	subject := fmt.Sprintf("github:%d", account.GetGithubId())
	scope := crdt.Revision([]string{identity.Issuer(), subject})
	peerIdentity, err := s.Replica.BindPeerAccount(scope, subject, identity.ID, identity.Issuer())
	if err != nil {
		return fmt.Errorf("bind peer account: %w", err)
	}
	if err := adoptStore(s, storeDir, peerIdentity, true, logger); err != nil {
		return fmt.Errorf("adopt store: %w", err)
	}
	if _, err := s.Kanban.RehomeCheckouts(); err != nil {
		return fmt.Errorf("rehome checkouts: %w", err)
	}
	return nil
}

func runAdoptStore(storeDir string, logger *slog.Logger) error {
	if _, err := os.Stat(storeDir); errors.Is(err, os.ErrNotExist) {
		return nil
	}
	s := store.New(storeDir)
	defer s.Close()
	if err := s.Ensure(); err != nil {
		return fmt.Errorf("ensure store: %w", err)
	}
	peerIdentity, err := s.Replica.PeerIdentity()
	if errors.Is(err, os.ErrNotExist) || peerIdentity.Account == "" || peerIdentity.Account == "local" {
		return nil
	}
	if err != nil {
		return err
	}
	existing, err := link.LoadIdentity(storeDir)
	if err != nil || !existing.Enrolled() || existing.ID != peerIdentity.DaemonID {
		return nil
	}
	if err := adoptStore(s, storeDir, peerIdentity, false, logger); err != nil {
		return fmt.Errorf("adopt store: %w", err)
	}
	if _, err := s.Kanban.RehomeCheckouts(); err != nil {
		return fmt.Errorf("rehome checkouts: %w", err)
	}
	return nil
}

func peerSQLitePath(storeDir, account string) string {
	return filepath.Join(storeDir, "peers", crdt.Revision(account)+".sqlite")
}

func restoreArchivedCheckouts(storeDir string) {
	modesDir := filepath.Join(storeDir, "gateway-modes")
	entries, err := os.ReadDir(modesDir)
	if err != nil {
		return
	}
	dstDir := filepath.Join(storeDir, "checkouts")
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		savedDir := filepath.Join(modesDir, entry.Name(), "checkouts")
		files, err := os.ReadDir(savedDir)
		if err != nil {
			continue
		}
		_ = os.MkdirAll(dstDir, 0o700)
		for _, f := range files {
			if f.IsDir() || !strings.HasSuffix(f.Name(), ".json") {
				continue
			}
			src := filepath.Join(savedDir, f.Name())
			dst := filepath.Join(dstDir, f.Name())
			if _, err := os.Stat(dst); errors.Is(err, os.ErrNotExist) {
				if raw, err := os.ReadFile(src); err == nil {
					_ = fsx.WriteFile(dst, raw, 0o600)
				}
			}
		}
		_ = os.RemoveAll(savedDir)
	}
}

func findOtherModeIdentity(storeDir string, next replica.PeerIdentity) (replica.PeerIdentity, bool) {
	modesDir := filepath.Join(storeDir, "gateway-modes")
	entries, err := os.ReadDir(modesDir)
	if err != nil {
		return replica.PeerIdentity{}, false
	}
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		var candidate replica.PeerIdentity
		if err := home.ReadJSON(filepath.Join(modesDir, entry.Name(), "peers", "identity.json"), &candidate); err != nil {
			continue
		}
		if candidate.Account == "" || candidate.Account == next.Account {
			continue
		}
		if _, err := os.Stat(peerSQLitePath(storeDir, candidate.Account)); err == nil {
			return candidate, true
		}
	}
	return replica.PeerIdentity{}, false
}

func localCardExists(storeDir, cardID string) bool {
	for _, dir := range []string{"cards", "archived-cards"} {
		if _, err := os.Stat(filepath.Join(storeDir, dir, cardID+".md")); err == nil {
			return true
		}
	}
	return false
}

func localCheckoutExists(storeDir, checkoutID string) bool {
	if checkoutID == "" {
		return false
	}
	_, err := os.Stat(filepath.Join(storeDir, "checkouts", checkoutID+".json"))
	return err == nil
}

func stripProvenance(r crdt.Record) crdt.Record {
	versions := make([]crdt.Version, len(r.Versions))
	for i, v := range r.Versions {
		v.Provenance = nil
		versions[i] = v
	}
	r.Versions = versions
	return r
}

func putDominantVersion(existing, incoming crdt.Record, actor string, value json.RawMessage) (crdt.Record, error) {
	clock := crdt.Clock{}
	for _, v := range existing.Versions {
		for a, n := range v.Clock {
			if n > clock[a] {
				clock[a] = n
			}
		}
	}
	for _, v := range incoming.Versions {
		for a, n := range v.Clock {
			if n > clock[a] {
				clock[a] = n
			}
		}
	}
	if len(clock) >= crdt.MaxActors {
		if _, ok := clock[actor]; !ok {
			clock = crdt.Clock{}
		}
	}
	clock[actor]++
	return crdt.Merge(crdt.Record{}, crdt.Record{
		Kind: incoming.Kind,
		ID:   incoming.ID,
		Versions: []crdt.Version{{
			Clock: clock,
			Value: value,
		}},
	})
}

func clockDominatesRecord(clock crdt.Clock, r crdt.Record) bool {
	for _, v := range r.Versions {
		for a, n := range v.Clock {
			if clock[a] < n {
				return false
			}
		}
	}
	return true
}

func recordDominatesWithSameValue(existing, incoming crdt.Record, desired json.RawMessage) bool {
	if len(existing.Versions) != 1 {
		return false
	}
	selected, ok := crdt.Selected(existing)
	if !ok || !bytes.Equal(selected, desired) {
		return false
	}
	return clockDominatesRecord(existing.Versions[0].Clock, incoming)
}

func itemOwnerAndCheckoutIn(data replica.PeerData, itemID string) (string, string) {
	raw, ok := crdt.Selected(data.Record(crdt.Key("item", itemID+".identity")))
	if !ok {
		return "", ""
	}
	var identity struct {
		OwnerDaemonID string `json:"ownerDaemonId"`
		CheckoutID    string `json:"checkoutId"`
	}
	if json.Unmarshal(raw, &identity) != nil {
		return "", ""
	}
	return identity.OwnerDaemonID, identity.CheckoutID
}

func collectLocalCheckouts(storeDir string, data replica.PeerData, defaultDaemonID string) (map[string]model.Checkout, map[string]string) {
	byID := map[string]model.Checkout{}
	byProject := map[string]string{}
	for _, id := range replica.EntityIDs(data, "checkout", "registration") {
		fields, _ := replica.EntityFields(data, "checkout", id)
		var checkout model.Checkout
		if json.Unmarshal(fields["registration"], &checkout) != nil {
			continue
		}
		coFile := filepath.Join(storeDir, "checkouts", id+".json")
		var local model.Checkout
		if err := home.ReadJSON(coFile, &local); err == nil && local.Path != "" {
			checkout.Path = local.Path
			checkout.ValidationCommands = local.ValidationCommands
		}
		byID[id] = checkout
		if checkout.Path != "" && !checkout.Detached {
			byProject[checkout.ProjectID] = id
			byProject[kanban.CanonicalProjectID(data, checkout.ProjectID)] = id
		}
	}
	projectsRoot := filepath.Join(filepath.Dir(storeDir), "projects")
	for _, id := range replica.EntityIDs(data, "checkout", "registration") {
		checkout, ok := byID[id]
		if !ok || checkout.Path != "" || checkout.Detached || checkout.Name == "" {
			continue
		}
		canonProj := kanban.CanonicalProjectID(data, checkout.ProjectID)
		if byProject[canonProj] != "" || byProject[checkout.ProjectID] != "" {
			continue
		}
		candidatePath := filepath.Join(projectsRoot, checkout.Name)
		if info, err := os.Stat(filepath.Join(candidatePath, ".git")); err == nil && info.IsDir() {
			checkout.Path = candidatePath
			local := model.Checkout{
				ID:        checkout.ID,
				ProjectID: checkout.ProjectID,
				DaemonID:  defaultDaemonID,
				Name:      checkout.Name,
				Path:      candidatePath,
			}
			coFile := filepath.Join(storeDir, "checkouts", id+".json")
			_ = os.MkdirAll(filepath.Dir(coFile), 0o700)
			_ = home.WriteJSON(coFile, local)
			byID[id] = checkout
			byProject[checkout.ProjectID] = id
			byProject[canonProj] = id
		}
	}
	return byID, byProject
}

func adoptStore(s *store.Store, storeDir string, next replica.PeerIdentity, isEmbedded bool, logger *slog.Logger) error {
	restoreArchivedCheckouts(storeDir)
	old, hasOld := findOtherModeIdentity(storeDir, next)

	if hasOld {
		source, err := s.Replica.OpenPeerView(old.Account)
		if err != nil {
			return err
		}
		target, err := s.Replica.OpenPeerView(next.Account)
		if err != nil {
			return err
		}
		sourceItems := len(replica.EntityIDs(source, "item", "identity"))
		targetItems := len(replica.EntityIDs(target, "item", "identity"))
		if sourceItems > 0 && targetItems == 0 {
			logger.Info("resetting empty target replica before adopting items from previous mode",
				"sourceAccount", old.Account,
				"targetAccount", next.Account,
				"sourceItems", sourceItems,
			)
			if err := s.Replica.Close(); err != nil {
				return err
			}
			dbPath := peerSQLitePath(storeDir, next.Account)
			for _, suffix := range []string{"", "-wal", "-shm", ".status"} {
				_ = os.Remove(dbPath + suffix)
			}
		}
	}

	write, err := s.BeginConditionalWrite()
	if err != nil {
		return err
	}
	defer write.Finish()
	prepared := false
	prepareOnce := func() error {
		if prepared {
			return nil
		}
		if err := write.Prepare(home.MetadataChange); err != nil {
			return err
		}
		prepared = true
		return nil
	}

	validCheckouts := map[string]bool{}
	if hasOld {
		source, err := s.Replica.OpenPeerView(old.Account)
		if err != nil {
			return err
		}
		target, err := s.Replica.OpenPeerView(next.Account)
		if err != nil {
			return err
		}
		target.State = replica.ClonePeerState(target.State)

		sourceCheckouts, projectLocalCheckout := collectLocalCheckouts(storeDir, source, next.DaemonID)
		for id := range sourceCheckouts {
			validCheckouts[id] = true
		}

		err = source.Each("", func(record crdt.Record) error {
			key := crdt.Key(record.Kind, record.ID)
			entity, field := crdt.SplitField(record.ID)
			existing := target.Record(key)
			record = stripProvenance(record)

			switch {
			case record.Kind == "checkout" && field == "registration":
				if len(existing.Versions) > 0 {
					return nil
				}
				raw, ok := crdt.Selected(record)
				if !ok {
					return nil
				}
				var value map[string]any
				if err := json.Unmarshal(raw, &value); err != nil {
					return err
				}
				if isEmbedded || value["daemonId"] == old.DaemonID || localCheckoutExists(storeDir, entity) {
					value["daemonId"] = next.DaemonID
					if !localCheckoutExists(storeDir, entity) {
						value["detached"] = true
					}
					var e error
					record, e = putDominantVersion(existing, record, next.Actor, replica.RawValue(value))
					if e != nil {
						return e
					}
				} else {
					return nil
				}

			case record.Kind == "item" && field == "identity":
				if len(existing.Versions) > 0 {
					return nil
				}
				raw, ok := crdt.Selected(record)
				if !ok {
					return nil
				}
				var value map[string]any
				if err := json.Unmarshal(raw, &value); err != nil {
					return err
				}
				if isEmbedded || value["ownerDaemonId"] == old.DaemonID || localCardExists(storeDir, entity) {
					value["ownerDaemonId"] = next.DaemonID
					coID, _ := value["checkoutId"].(string)
					projID, _ := value["projectId"].(string)
					if !localCheckoutExists(storeDir, coID) {
						if localCo := projectLocalCheckout[kanban.CanonicalProjectID(source, projID)]; localCo != "" {
							value["checkoutId"] = localCo
						} else if localCo := projectLocalCheckout[projID]; localCo != "" {
							value["checkoutId"] = localCo
						}
					}
					var e error
					record, e = putDominantVersion(existing, record, next.Actor, replica.RawValue(value))
					if e != nil {
						return e
					}
				} else {
					return nil
				}

			case record.Kind == "item" && field == "summary":
				owner, _ := itemOwnerAndCheckoutIn(target, entity)
				if owner != next.DaemonID {
					return nil
				}

			case record.Kind == "schedule" && field == "summary":
				raw, ok := crdt.Selected(record)
				if !ok {
					return nil
				}
				var value map[string]any
				if err := json.Unmarshal(raw, &value); err != nil {
					return err
				}
				if len(existing.Versions) > 0 {
					existingRaw, ok := crdt.Selected(existing)
					if !ok {
						return nil
					}
					var existingVal map[string]any
					if err := json.Unmarshal(existingRaw, &existingVal); err != nil {
						return err
					}
					if existingVal["ownerDaemonId"] != next.DaemonID {
						return nil
					}
					value["ownerDaemonId"] = next.DaemonID
					if existingCo, ok := existingVal["checkoutId"].(string); ok && existingCo != "" {
						value["checkoutId"] = existingCo
					}
				} else if isEmbedded || value["ownerDaemonId"] == old.DaemonID {
					value["ownerDaemonId"] = next.DaemonID
					coID, _ := value["checkoutId"].(string)
					projID, _ := value["projectId"].(string)
					if !localCheckoutExists(storeDir, coID) {
						if localCo := projectLocalCheckout[kanban.CanonicalProjectID(source, projID)]; localCo != "" {
							value["checkoutId"] = localCo
						} else if localCo := projectLocalCheckout[projID]; localCo != "" {
							value["checkoutId"] = localCo
						}
					}
				} else {
					return nil
				}
				desired := replica.RawValue(value)
				if recordDominatesWithSameValue(existing, record, desired) {
					return nil
				}
				var e error
				record, e = putDominantVersion(existing, record, next.Actor, desired)
				if e != nil {
					return e
				}
			}

			merged, e := crdt.Merge(existing, record)
			if e != nil {
				return e
			}
			if len(merged.Versions) > 1 && !(record.Kind == "item" && field == "identity") && record.Kind != "checkout" {
				if selected, ok := crdt.Selected(record); ok {
					merged, e = putDominantVersion(existing, record, next.Actor, selected)
					if e != nil {
						return e
					}
				}
			}
			if existing.Revision() != merged.Revision() {
				target.Records[key] = merged
				target.Dirty[key] = true
			}
			return nil
		})
		if err != nil {
			return err
		}
		if err := source.Err(); err != nil {
			return err
		}
		if len(target.Dirty) > 0 {
			if err := prepareOnce(); err != nil {
				return err
			}
			if err := s.Replica.SavePeerData(next.Account, target); err != nil {
				return err
			}
			logger.Info("adopted peer records from previous gateway mode",
				"sourceAccount", old.Account,
				"targetAccount", next.Account,
				"records", len(target.Dirty),
			)
		}
	}

	target, err := s.Replica.OpenPeerView(next.Account)
	if err != nil {
		return err
	}
	targetCheckouts, projectLocalCheckout := collectLocalCheckouts(storeDir, target, next.DaemonID)
	for id, reg := range targetCheckouts {
		validCheckouts[id] = true
		if reg.DaemonID != next.DaemonID {
			continue
		}
		coPath := filepath.Join(storeDir, "checkouts", id+".json")
		var local model.Checkout
		if err := home.ReadJSON(coPath, &local); err == nil && local.DaemonID != next.DaemonID {
			if err := prepareOnce(); err != nil {
				return err
			}
			local.DaemonID = next.DaemonID
			if err := home.WriteJSON(coPath, local); err != nil {
				return err
			}
		}
	}

	if len(validCheckouts) > 0 {
		checkoutFiles, _ := home.ListJSON(filepath.Join(storeDir, "checkouts"))
		for _, coPath := range checkoutFiles {
			id := strings.TrimSuffix(filepath.Base(coPath), ".json")
			if !validCheckouts[id] {
				if err := prepareOnce(); err != nil {
					return err
				}
				_ = os.Remove(coPath)
			}
		}
	}

	updatedCards := 0
	for _, dir := range []string{s.CardDir(), s.ArchivedCardDir()} {
		paths, err := home.ListMarkdown(dir)
		if err != nil {
			return err
		}
		for _, path := range paths {
			var item model.Card
			body, err := home.ReadMarkdown(path, &item)
			if err != nil {
				continue
			}
			desiredOwner, desiredCheckout := itemOwnerAndCheckoutIn(target, item.ID)
			if desiredOwner == "" {
				if isEmbedded || (hasOld && item.OwnerDaemonID == old.DaemonID) {
					desiredOwner = next.DaemonID
				} else {
					desiredOwner = item.OwnerDaemonID
				}
			}
			if desiredCheckout == "" {
				desiredCheckout = item.CheckoutID
			}
			if isEmbedded && !localCheckoutExists(storeDir, desiredCheckout) {
				if localCo := projectLocalCheckout[kanban.CanonicalProjectID(target, item.ProjectID)]; localCo != "" {
					desiredCheckout = localCo
				} else if localCo := projectLocalCheckout[item.ProjectID]; localCo != "" {
					desiredCheckout = localCo
				}
			}
			if item.OwnerDaemonID == desiredOwner && item.CheckoutID == desiredCheckout {
				continue
			}
			if err := prepareOnce(); err != nil {
				return err
			}
			item.OwnerDaemonID = desiredOwner
			item.CheckoutID = desiredCheckout
			raw, err := home.MarshalMarkdown(item, body)
			if err != nil {
				return err
			}
			if err := fsx.WriteFile(path, raw, 0o600); err != nil {
				return err
			}
			updatedCards++
		}
	}
	if updatedCards > 0 {
		logger.Info("updated local card ownership for active gateway mode",
			"daemonId", next.DaemonID,
			"cards", updatedCards,
		)
	}

	if hasOld && old.DaemonID != "" && old.DaemonID != next.DaemonID {
		if err := s.Schedules.AdoptOwner(old, next); err != nil {
			return err
		}
	}
	return target.Err()
}
