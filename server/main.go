// callbridge server: WebSocket audio relay.
//
// Binary messages are opaque audio frames (see docs/protocol.md). A text
// "hello" message selects the mode:
//
//	echo: frames are returned to the sender after delayMs.
//	room: frames are forwarded to every other client in the same room.
package main

import (
	"context"
	_ "embed"
	"encoding/json"
	"flag"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/coredevices/callbridge/server/audio"
)

//go:embed index.html
var indexHTML []byte

//go:embed far.html
var farHTML []byte

type hello struct {
	Type    string `json:"type"`
	Mode    string `json:"mode"`
	Room    string `json:"room"`
	DelayMs int    `json:"delayMs"`
	Name    string `json:"name"`
}

// control is any text message after hello: ping (echoed as pong), a latency report,
// burst (arms the tone detector; t_server = client's estimate of the emit time on the
// server clock), inject (server plays a tone burst into the return stream) or an e2e
// result measured by the client.
type control struct {
	Type    string   `json:"type"`
	T       float64  `json:"t"`
	TServer float64  `json:"t_server"`
	Sco     *float64 `json:"sco_rt_ms"`
	Ble     *float64 `json:"ble_rt_ms"`
	Ws      *float64 `json:"ws_rt_ms"`
	FullRt  *float64 `json:"full_rt_ms"`
	Down    *float64 `json:"down_ms"`
}

// latencySample is one report from a client; nil sections are unmeasured.
// sco/ble/ws are per-hop round trips from pings. up/down/full are measured with a real
// tone burst detected at the far end (docs/protocol.md "Latency").
type latencySample struct {
	T      int64    `json:"t"` // unix ms, server clock
	Sco    *float64 `json:"sco_rt_ms"`
	Ble    *float64 `json:"ble_rt_ms"`
	Ws     *float64 `json:"ws_rt_ms"`
	Up     *float64 `json:"up_ms"`
	Down   *float64 `json:"down_ms"`
	FullRt *float64 `json:"full_rt_ms"`
}

type clientStats struct {
	Name    string          `json:"name"`
	Mode    string          `json:"mode"`
	Online  bool            `json:"online"`
	History []latencySample `json:"history"`
	seen    int64           // unix ms of last record; eviction order
}

const (
	historyCap   = 900   // ~30 min at one report per 2 s
	clientCap    = 100   // evict the oldest offline client beyond this
	maxLatencyMs = 60000 // reports outside [0, maxLatencyMs] are dropped
)

type statsStore struct {
	mu sync.Mutex
	m  map[string]*clientStats
}

func (s *statsStore) record(id string, cs clientStats, smp *latencySample) {
	s.mu.Lock()
	defer s.mu.Unlock()
	c := s.m[id]
	if c == nil {
		if len(s.m) >= clientCap {
			s.evictLocked()
		}
		c = &clientStats{History: []latencySample{}}
		s.m[id] = c
	}
	c.Name, c.Mode, c.Online, c.seen = cs.Name, cs.Mode, cs.Online, time.Now().UnixMilli()
	if smp != nil {
		c.History = append(c.History, *smp)
		if len(c.History) > historyCap {
			c.History = c.History[len(c.History)-historyCap:]
		}
	}
}

// evictLocked drops the least recently seen client, preferring offline ones.
func (s *statsStore) evictLocked() {
	var victim string
	var vs *clientStats
	for id, c := range s.m {
		if vs == nil || (!c.Online && vs.Online) || (c.Online == vs.Online && c.seen < vs.seen) {
			victim, vs = id, c
		}
	}
	delete(s.m, victim)
}

func validMs(p *float64) *float64 {
	if p == nil || *p < 0 || *p > maxLatencyMs || *p != *p {
		return nil
	}
	return p
}

func (s *statsStore) snapshot() map[string]*clientStats {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make(map[string]*clientStats, len(s.m))
	for k, v := range s.m {
		cp := *v
		cp.History = append([]latencySample(nil), v.History...)
		out[k] = &cp
	}
	return out
}

type client struct {
	conn         *websocket.Conn
	out          chan []byte // frames to send; dropped when full
	burstArmedAt float64     // server-clock ms the client says it emitted the burst
}

type rooms struct {
	mu sync.Mutex
	m  map[string]map[*client]struct{}
}

func (r *rooms) join(name string, c *client) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.m[name] == nil {
		r.m[name] = map[*client]struct{}{}
	}
	r.m[name][c] = struct{}{}
	r.notifyLocked(name)
}

func (r *rooms) leave(name string, c *client) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.m[name], c)
	if len(r.m[name]) == 0 {
		delete(r.m, name)
		return
	}
	r.notifyLocked(name)
}

func (r *rooms) notifyLocked(name string) {
	msg, _ := json.Marshal(map[string]any{"type": "peer", "count": len(r.m[name])})
	for c := range r.m[name] {
		c.send(msg, true)
	}
}

func (r *rooms) broadcast(name string, from *client, frame []byte) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for c := range r.m[name] {
		if c != from {
			c.send(frame, false)
		}
	}
}

// send enqueues a message; non-blocking so a slow client cannot stall others.
// Text control messages are tagged with a leading marker byte in the channel.
func (c *client) send(b []byte, text bool) {
	msg := make([]byte, 1, len(b)+1)
	if text {
		msg[0] = 1
	}
	msg = append(msg, b...)
	select {
	case c.out <- msg:
	default: // ponytail: drop on backpressure; add a counter if this ever matters
	}
}

// writer drains c.out until ctx ends. c.out is never closed: delayed echo
// callbacks may still fire after disconnect and a send on a closed channel
// would panic; they just fill the buffer and get dropped instead.
func (c *client) writer(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case msg := <-c.out:
			typ := websocket.MessageBinary
			if msg[0] == 1 {
				typ = websocket.MessageText
			}
			if err := c.conn.Write(ctx, typ, msg[1:]); err != nil {
				return
			}
		}
	}
}

func serveWS(r *rooms, st *statsStore) http.HandlerFunc {
	return func(w http.ResponseWriter, req *http.Request) {
		conn, err := websocket.Accept(w, req, &websocket.AcceptOptions{OriginPatterns: []string{"*"}})
		if err != nil {
			return
		}
		defer conn.CloseNow()
		ctx, cancel := context.WithCancel(req.Context())
		defer cancel()

		typ, data, err := conn.Read(ctx)
		if err != nil || typ != websocket.MessageText {
			return
		}
		var h hello
		if json.Unmarshal(data, &h) != nil || h.Type != "hello" || (h.Mode != "echo" && h.Mode != "room") {
			conn.Write(ctx, websocket.MessageText, []byte(`{"type":"error","message":"expected hello with mode echo|room"}`))
			return
		}
		c := &client{conn: conn, out: make(chan []byte, 256)}
		det := &audio.Detector{Rate: 16000}
		var dbgFrames int
		det.Debug = func(m, th float64, n, rate int) {
			if dbgFrames++; dbgFrames%5 == 1 || m > th {
				log.Printf("client %s: armed frame %d: level %.4f threshold %.4f (%d samples @ %d Hz)", req.RemoteAddr, dbgFrames, m, th, n, rate)
			}
		}
		var lastUp *float64 // carried into the next periodic sample so the page can plot it
		go c.writer(ctx)
		c.send([]byte(`{"type":"ok"}`), true)

		if h.Mode == "room" {
			if h.Room == "" {
				h.Room = "default"
			}
			r.join(h.Room, c)
			defer r.leave(h.Room, c)
		}
		delay := time.Duration(min(max(h.DelayMs, 0), 5000)) * time.Millisecond // bounded: each frame holds a timer until it fires
		log.Printf("client %s mode=%s room=%q delay=%s", req.RemoteAddr, h.Mode, h.Room, delay)
		id := req.RemoteAddr // identity; h.Name is only a label
		st.record(id, clientStats{Name: h.Name, Mode: h.Mode, Online: true}, nil)
		defer st.record(id, clientStats{Name: h.Name, Mode: h.Mode, Online: false}, nil)

		for {
			typ, data, err := conn.Read(ctx)
			if err != nil {
				return
			}
			if typ == websocket.MessageText {
				var m control
				if json.Unmarshal(data, &m) != nil {
					continue
				}
				switch m.Type {
				case "ping":
					pong, _ := json.Marshal(map[string]any{"type": "pong", "t": m.T, "server_t": float64(time.Now().UnixMicro()) / 1000})
					c.send(pong, true)
				case "latency":
					st.record(id, clientStats{Name: h.Name, Mode: h.Mode, Online: true},
						&latencySample{T: time.Now().UnixMilli(), Sco: validMs(m.Sco), Ble: validMs(m.Ble), Ws: validMs(m.Ws), Up: lastUp})
					lastUp = nil
				case "burst":
					dbgFrames = 0
					// the read loop owns the detector; expiry is checked here, not from a timer goroutine
					det.Armed = true
					det.Deadline = time.Now().Add(3 * time.Second)
					c.burstArmedAt = m.TServer
				case "inject":
					// play a tone burst towards the client; it detects it at the phone
					now := float64(time.Now().UnixMicro()) / 1000
					for _, f := range audio.EncodeFrames(audio.Tone(det.Rate), det.Rate, 0xFFF0) {
						c.send(f, false)
					}
					msg, _ := json.Marshal(map[string]any{"type": "injected", "t_server": now})
					c.send(msg, true)
				case "e2e":
					st.record(id, clientStats{Name: h.Name, Mode: h.Mode, Online: true},
						&latencySample{T: time.Now().UnixMilli(), FullRt: validMs(m.FullRt), Down: validMs(m.Down), Up: lastUp})
					lastUp = nil
				}
				continue
			}
			if typ != websocket.MessageBinary {
				continue
			}
			if det.Armed && time.Now().After(det.Deadline) {
				det.Armed = false
			}
			if det.Feed(data) {
				now := float64(time.Now().UnixMicro()) / 1000
				up := now - c.burstArmedAt
				lastUp = &up
				msg, _ := json.Marshal(map[string]any{"type": "burst_detected", "t_server": now, "up_ms": up})
				c.send(msg, true)
				log.Printf("client %s: burst detected, up %.0f ms", id, up)
			}
			switch h.Mode {
			case "echo":
				if delay == 0 {
					c.send(data, false)
				} else {
					time.AfterFunc(delay, func() { c.send(data, false) })
				}
			case "room":
				r.broadcast(h.Room, c, data)
			}
		}
	}
}

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	flag.Parse()
	r := &rooms{m: map[string]map[*client]struct{}{}}
	st := &statsStore{m: map[string]*clientStats{}}
	http.HandleFunc("/ws", serveWS(r, st))
	http.HandleFunc("/stats", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"clients": st.snapshot(), "now": time.Now().UnixMilli()})
	})
	http.HandleFunc("/far", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(farHTML)
	})
	http.HandleFunc("/", func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path != "/" {
			http.NotFound(w, req)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(indexHTML)
	})
	http.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("ok")) })
	log.Printf("callbridge server listening on %s", *addr)
	log.Fatal(http.ListenAndServe(*addr, nil))
}
