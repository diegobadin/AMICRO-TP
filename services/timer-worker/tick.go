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

// Both ids exist to be greppable across two services' logs, not to be unguessable; the clock is a
// good enough fallback if the entropy source ever refuses, because a duplicate id costs a confusing
// log line and nothing else.
func randomID(prefix string, bytes int) string {
	buf := make([]byte, bytes)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("%s-%d", prefix, time.Now().UnixNano())
	}
	return prefix + "-" + hex.EncodeToString(buf)
}

func correlationID() string { return randomID("tick", 8) }

func newSessionID() string { return randomID("worker", 16) }
