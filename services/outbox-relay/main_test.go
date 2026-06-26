package main

import (
	"net/http"
	"strings"
	"testing"
)

func TestRouteHealthReturnsOKWithServiceName(t *testing.T) {
	status, body := route(http.MethodGet, "/health")
	if status != http.StatusOK {
		t.Fatalf("expected status %d, got %d", http.StatusOK, status)
	}
	if !strings.Contains(body, `"service":"`+service+`"`) {
		t.Fatalf("expected body to contain service name %q, got %s", service, body)
	}
	if !strings.Contains(body, `"status":"ok"`) {
		t.Fatalf("expected body to contain status ok, got %s", body)
	}
}

func TestRouteUnknownPathReturns404(t *testing.T) {
	status, _ := route(http.MethodGet, "/does-not-exist")
	if status != http.StatusNotFound {
		t.Fatalf("expected status %d, got %d", http.StatusNotFound, status)
	}
}
