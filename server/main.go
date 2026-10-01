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
	"encoding/json"
	"flag"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
)

type hello struct {
	Type    string `json:"type"`
	Mode    string `json:"mode"`
	Room    string `json:"room"`
	DelayMs int    `json:"delayMs"`
}

type client struct {
	conn *websocket.Conn
	out  chan []byte // frames to send; dropped when full
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

func serveWS(r *rooms) http.HandlerFunc {
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

		for {
			typ, data, err := conn.Read(ctx)
			if err != nil {
				return
			}
			if typ != websocket.MessageBinary {
				continue
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
	http.HandleFunc("/ws", serveWS(r))
	http.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("ok")) })
	log.Printf("callbridge server listening on %s", *addr)
	log.Fatal(http.ListenAndServe(*addr, nil))
}
