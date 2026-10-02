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
	"github.com/coredevices/callbridge/server/audio"
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

func TestToneDetector(t *testing.T) {
	rate := 16000
	// a second of near-silence (low noise), then the burst
	quiet := make([]int16, rate)
	for i := range quiet {
		quiet[i] = int16((i*7919)%64 - 32)
	}
	frames := audio.EncodeFrames(quiet, rate, 0)
	burst := audio.EncodeFrames(append(audio.Tone(rate), make([]int16, rate*20/1000)...), rate, uint16(len(frames)))
	d := &audio.Detector{}
	for _, f := range frames {
		if d.Feed(f) {
			t.Fatal("false positive on silence")
		}
	}
	d.Armed = true
	if !d.Feed(burst[0]) {
		t.Fatal("burst not detected on its first frame")
	}
	if d.Feed(burst[1]) {
		t.Fatal("should disarm after detection")
	}
	// round trip sanity of the codec itself
	pcm, r := audio.DecodeFrame(burst[0])
	if r != rate || len(pcm) != rate*20/1000 {
		t.Fatalf("decode: rate %d len %d", r, len(pcm))
	}
}

func TestDecodeFrameRejectsBadLength(t *testing.T) {
	if pcm, _ := audio.DecodeFrame([]byte{0, 0, audio.CodecAdpcm16, 0, 0, 0}); pcm != nil {
		t.Fatal("header-only frame accepted")
	}
	if pcm, _ := audio.DecodeFrame(append([]byte{0, 0, audio.CodecAdpcm8k, 0, 0, 0}, make([]byte, 80)...)); pcm == nil {
		t.Fatal("valid 8 kHz frame rejected")
	}
}
