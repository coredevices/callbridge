package main

import (
	"context"
	"encoding/json"
	"fmt"
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
	st := &statsStore{m: map[string]*clientStats{}}
	srv := httptest.NewServer(serveWS(r, st))
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

func TestPingAndLatency(t *testing.T) {
	r := &rooms{m: map[string]map[*client]struct{}{}}
	st := &statsStore{m: map[string]*clientStats{}}
	srv := httptest.NewServer(serveWS(r, st))
	defer srv.Close()
	url := "ws" + strings.TrimPrefix(srv.URL, "http")
	ctx := context.Background()

	c := dial(t, url, `{"type":"hello","mode":"echo","name":"pixel"}`)
	defer c.CloseNow()
	c.Write(ctx, websocket.MessageText, []byte(`{"type":"ping","t":42}`))
	_, data, err := c.Read(ctx)
	if err != nil || !strings.Contains(string(data), `"pong"`) || !strings.Contains(string(data), `42`) {
		t.Fatalf("pong got %q err=%v", data, err)
	}
	c.Write(ctx, websocket.MessageText, []byte(`{"type":"latency","sco_rt_ms":120.5,"ws_rt_ms":30}`))
	deadline := time.Now().Add(2 * time.Second)
	for {
		var cs *clientStats
		for _, c := range st.snapshot() {
			if c.Name == "pixel" {
				cs = c
			}
		}
		if cs != nil && len(cs.History) == 1 {
			h := cs.History[0]
			if h.Ble != nil || *h.Sco != 120.5 || *h.Ws != 30 || !cs.Online {
				t.Fatalf("bad sample %+v", h)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("latency sample not recorded")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestStatsStoreBounds(t *testing.T) {
	st := &statsStore{m: map[string]*clientStats{}}
	for i := 0; i < clientCap+5; i++ {
		st.record(fmt.Sprintf("c%d", i), clientStats{Online: i%2 == 0}, nil)
	}
	if n := len(st.snapshot()); n != clientCap {
		t.Fatalf("client cap not enforced: %d", n)
	}
	bad := 1.7e308
	if validMs(&bad) != nil {
		t.Fatal("extreme latency accepted")
	}
	neg := -1.0
	if validMs(&neg) != nil {
		t.Fatal("negative latency accepted")
	}
}
