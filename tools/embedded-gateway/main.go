package main

import (
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
	"github.com/dbpprt/dieter/internal/replication/crdt"
	"github.com/dbpprt/dieter/internal/security/roster"
	"github.com/dbpprt/dieter/internal/store"
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
	if *storeDir == "" {
		fmt.Fprintln(os.Stderr, "either -store or -prepare-daemon is required")
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

	s := store.New(storeDir)
	if err := s.Ensure(); err != nil {
		return fmt.Errorf("ensure store: %w", err)
	}
	subject := fmt.Sprintf("github:%d", account.GetGithubId())
	scope := crdt.Revision([]string{identity.Issuer(), subject})
	if _, err := s.Replica.BindPeerAccount(scope, subject, identity.ID, identity.Issuer()); err != nil {
		return fmt.Errorf("bind peer account: %w", err)
	}
	if _, err := s.Kanban.RehomeCheckouts(); err != nil {
		return fmt.Errorf("rehome checkouts: %w", err)
	}

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
