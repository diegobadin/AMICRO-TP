package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// The P4 lesson, made permanent: room-gameplay requires BOTH headers and a probe that sets one sees
// a 401 that is correct rather than a bug. A worker that sends half an identity would look like a
// broken engine from every other angle. Since P7 the shared token is a third required part — and it
// fails the same silent way, so it is asserted here rather than discovered on a cluster.
func TestTickSendsBothTrustHeaders(t *testing.T) {
	var player, session, token, correlation, path, method string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		player = r.Header.Get("X-Player-Id")
		session = r.Header.Get("X-Session-Id")
		token = r.Header.Get("X-Internal-Token")
		correlation = r.Header.Get("X-Correlation-Id")
		path, method = r.URL.Path, r.Method
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	c := &tickClient{
		baseURL:       server.URL,
		sessionID:     "worker-abc",
		internalToken: "shared-token",
		client:        server.Client(),
	}
	if err := c.tick(context.Background(), "room-1"); err != nil {
		t.Fatalf("tick: %v", err)
	}

	if player != systemPlayerID {
		t.Errorf("X-Player-Id = %q, want %q", player, systemPlayerID)
	}
	if session != "worker-abc" {
		t.Errorf("X-Session-Id = %q, want the pod's session", session)
	}
	if token != "shared-token" {
		t.Errorf("X-Internal-Token = %q, want the shared token", token)
	}
	if correlation == "" {
		t.Error("every tick carries a correlation id")
	}
	if method != http.MethodPost || path != "/internal/rooms/room-1/tick" {
		t.Errorf("called %s %s", method, path)
	}
}

func TestTickTreatsAGoneRoomAsDone(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer server.Close()

	c := &tickClient{baseURL: server.URL, sessionID: "w", client: server.Client()}
	if err := c.tick(context.Background(), "room-1"); err != nil {
		t.Fatalf("a room that has gone is not an error: %v", err)
	}
}

// A refused tick must surface as an error so the row is left due and retried, rather than being
// quietly counted as delivered.
func TestTickReportsRefusal(t *testing.T) {
	for _, status := range []int{http.StatusUnauthorized, http.StatusInternalServerError} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(status)
		}))
		c := &tickClient{baseURL: server.URL, sessionID: "w", client: server.Client()}
		err := c.tick(context.Background(), "room-1")
		server.Close()
		if err == nil {
			t.Errorf("status %d must be reported, not swallowed", status)
		}
	}
}

func TestTickGivesUpOnASilentPeer(t *testing.T) {
	// A peer that accepts the connection and never answers hangs anything without a guard above it.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer server.Close()

	c := &tickClient{baseURL: server.URL, sessionID: "w", client: &http.Client{Timeout: 200 * time.Millisecond}}
	start := time.Now()
	if err := c.tick(context.Background(), "room-1"); err == nil {
		t.Fatal("a tick that never gets an answer has to fail")
	}
	if time.Since(start) > 3*time.Second {
		t.Fatal("the timeout did not bound the request")
	}
}

func TestSessionIdsAreDistinctPerPod(t *testing.T) {
	a, b := newSessionID(), newSessionID()
	if a == b {
		t.Fatal("two pods must not claim the same session id")
	}
	if !strings.HasPrefix(a, "worker-") {
		t.Fatalf("session id %q should say what it is", a)
	}
}
