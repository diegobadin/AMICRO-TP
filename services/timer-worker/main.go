package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"time"
)

const service = "timer-worker"

const defaultPort = "8089"

// route is a pure, testable function mapping (method, path) to an HTTP status
// and a response body string.
func route(method, path string) (int, string) {
	switch {
	case path == "/health" && method == http.MethodGet:
		return http.StatusOK, `{"status":"ok","service":"` + service + `"}`
	default:
		return http.StatusNotFound, `{"status":"not_found","service":"` + service + `"}`
	}
}

// logLine emits one structured JSON log line per request to stdout.
func logLine(action, status, correlationId string) {
	entry := map[string]string{
		"ts":            time.Now().UTC().Format(time.RFC3339),
		"level":         "info",
		"service":       service,
		"action":        action,
		"status":        status,
		"correlationId": correlationId,
	}
	b, err := json.Marshal(entry)
	if err != nil {
		return
	}
	os.Stdout.Write(append(b, '\n'))
}

func handler(w http.ResponseWriter, r *http.Request) {
	status, body := route(r.Method, r.URL.Path)
	correlationId := r.Header.Get("X-Correlation-Id")
	logLine(r.Method+" "+r.URL.Path, http.StatusText(status), correlationId)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	w.Write([]byte(body))
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = defaultPort
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/", handler)
	addr := ":" + port
	log.Printf("%s listening on %s", service, addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatal(err)
	}
}
