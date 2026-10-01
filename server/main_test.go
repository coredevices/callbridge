package main

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func dial(t *testing.T, url, hello string) *websocket.Conn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := c.Write(ctx, websocket.MessageText, []byte(hello)); err != nil {
		t.Fatal(err)
	}
	_, data, err := c.Read(ctx) // {"type":"ok"}
	if err != nil || !strings.Contains(string(data), `"ok"`) {
		t.Fatalf("expected ok, got %q err=%v", data, err)
	}
	return c
}

func readBinary(t *testing.T, c *websocket.Conn) []byte {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	for {
		typ, data, err := c.Read(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if typ == websocket.MessageBinary {
			return data
		}
	}
}

func TestEchoAndRoom(t *testing.T) {
	r := &rooms{m: map[string]map[*client]struct{}{}}
	srv := httptest.NewServer(serveWS(r))
	defer srv.Close()
	url := "ws" + strings.TrimPrefix(srv.URL, "http")
	ctx := context.Background()

	// echo with delay
	e := dial(t, url, `{"type":"hello","mode":"echo","delayMs":50}`)
	defer e.CloseNow()
	start := time.Now()
	e.Write(ctx, websocket.MessageBinary, []byte{1, 2, 3})
	if got := readBinary(t, e); string(got) != "\x01\x02\x03" {
		t.Fatalf("echo got %v", got)
	}
	if time.Since(start) < 50*time.Millisecond {
		t.Fatal("echo returned before delay")
	}

	// room: a's frames reach b, not a
	a := dial(t, url, `{"type":"hello","mode":"room","room":"x"}`)
	defer a.CloseNow()
	b := dial(t, url, `{"type":"hello","mode":"room","room":"x"}`)
	defer b.CloseNow()
	// b gets a peer notice with count 2
	_, data, _ := b.Read(ctx)
	var peer map[string]any
	json.Unmarshal(data, &peer)
	if peer["count"] != float64(2) {
		t.Fatalf("peer count %v", peer)
	}
	a.Write(ctx, websocket.MessageBinary, []byte{9})
	if got := readBinary(t, b); string(got) != "\x09" {
		t.Fatalf("room got %v", got)
	}
}
