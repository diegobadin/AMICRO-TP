package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"time"
)

// room-gameplay holds no signing key: it trusts X-Player-Id and X-Session-Id because the gateway is
// the only way in from outside, and this worker is inside. Both headers are required — one alone is
// a 401, and the `system:` prefix is refused on every player-facing route, so this identity can only
// ever do the one thing it exists for.
const systemPlayerID = "system:timer-worker"

type tickClient struct {
	baseURL   string
	sessionID string
	client    *http.Client
}

func (t *tickClient) tick(ctx context.Context, roomID string) error {
	url := fmt.Sprintf("%s/internal/rooms/%s/tick", t.baseURL, roomID)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("X-Player-Id", systemPlayerID)
	req.Header.Set("X-Session-Id", t.sessionID)
	req.Header.Set("X-Correlation-Id", correlationID())

	res, err := t.client.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(res.Body, 4096))

	// A room that has gone since the query is not a failure — it means somebody else finished it,
	// which is exactly the outcome the tick was asking for.
	if res.StatusCode == http.StatusNotFound {
		return nil
	}
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return fmt.Errorf("tick %s: unexpected status %d", roomID, res.StatusCode)
	}
	return nil
}

func correlationID() string {
	buf := make([]byte, 8)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("tick-%d", time.Now().UnixNano())
	}
	return "tick-" + hex.EncodeToString(buf)
}

func newSessionID() string {
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("worker-%d", time.Now().UnixNano())
	}
	return "worker-" + hex.EncodeToString(buf)
}
