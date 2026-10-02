// pulse: joins a callbridge room as a software far end, streams silence at 50 frames/s like a
// real call, fires a tone pulse every interval and reports when (and whether) the echo comes
// back. Pulses cycle through distinct frequencies so each echo is attributed to the pulse that
// caused it. The phone side must echo (app echo, or SCO echo through the dongle).
//
//	go run ./cmd/pulse -url ws://localhost:8765/ws -room demo -interval 3s -count 20
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"sort"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/coredevices/callbridge/server/audio"
)

type pulse struct {
	id   int
	hz   float64
	at   time.Time
	rtt  time.Duration
	seen bool
}

func main() {
	url := flag.String("url", "ws://localhost:8765/ws", "server websocket url")
	room := flag.String("room", "demo", "room to join")
	mode := flag.String("mode", "room", "room (phone echoes) or echo (server echoes; sanity check)")
	interval := flag.Duration("interval", 3*time.Second, "time between pulses")
	count := flag.Int("count", 0, "pulses to send (0 = forever)")
	rate := flag.Int("rate", 8000, "sample rate: 8000 (CVSD link) or 16000 (mSBC)")
	probe := flag.Int("probe", 0, "probe mode: send marker frames of this many bytes at 50/s and time every one (needs a byte-level echo, e.g. dongle link echo)")
	dur := flag.Duration("dur", 30*time.Second, "probe mode duration")
	flag.Parse()
	if *probe > 0 {
		runProbe(*url, *room, *probe, *dur)
		return
	}

	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, *url, nil)
	if err != nil {
		fmt.Println("dial:", err)
		os.Exit(1)
	}
	defer c.CloseNow()
	hello, _ := json.Marshal(map[string]any{"type": "hello", "mode": *mode, "room": *room, "delayMs": 0, "name": "pulse"})
	if err := c.Write(ctx, websocket.MessageText, hello); err != nil {
		fmt.Println("hello:", err)
		os.Exit(1)
	}

	const frameDur = 20 * time.Millisecond
	const minLevel = 0.0008
	silence := audio.EncodeFrames(make([]int16, *rate*20/1000), *rate, 0)[0]
	// distinct pulse frequencies, all inside the 300-3400 Hz voice band of the SCO link
	freqs := []float64{700, 900, 1100, 1300, 1500, 1700}
	tones := make([][][]byte, len(freqs))
	for i, hz := range freqs {
		tones[i] = audio.EncodeFrames(append(audio.ToneAt(*rate, hz, audio.BurstMs), make([]int16, *rate*20/1000)...), *rate, 0)
	}

	var (
		mu      sync.Mutex
		pending []*pulse
		results []pulse
		floor   = make([]float64, len(freqs))
		rxSec   int
		lost    int
		lastSeq = -1
		lastHit time.Time
	)

	// receiver: identify which pulse frequency a frame carries, attribute it to the pending pulse
	go func() {
		for {
			typ, data, err := c.Read(ctx)
			if err != nil {
				return
			}
			if typ == websocket.MessageText {
				var m map[string]any
				json.Unmarshal(data, &m)
				if m["type"] == "peer" {
					fmt.Printf("room %q: %v in room\n", *room, m["count"])
				}
				continue
			}
			mu.Lock()
			if len(data) >= 2 {
				seq := int(data[0]) | int(data[1])<<8
				if lastSeq >= 0 {
					if d := (seq - lastSeq) & 0xffff; d > 1 && d < 32768 {
						lost += d - 1
					}
				}
				lastSeq = seq
			}
			rxSec++
			pcm, r := audio.DecodeFrame(data)
			if pcm == nil {
				mu.Unlock()
				continue
			}
			best, bestLevel := -1, 0.0
			for i, hz := range freqs {
				m := audio.GoertzelAt(pcm, r, hz)
				th := floor[i] * 8
				if th < minLevel {
					th = minLevel
				}
				if m > th && m > bestLevel {
					best, bestLevel = i, m
				}
				if m <= th {
					floor[i] = floor[i]*0.9 + m*0.1
				}
			}
			if best >= 0 && time.Since(lastHit) > 150*time.Millisecond {
				for k, p := range pending {
					if p.hz == freqs[best] {
						lastHit = time.Now()
						pending = append(pending[:k], pending[k+1:]...)
						p.seen = true
						p.rtt = time.Since(p.at)
						results = append(results, *p)
						fmt.Printf("pulse #%-3d %4.0f Hz  echo after %4d ms   (level %.4f, rx %d/s, lost %d)\n", p.id, p.hz, p.rtt.Milliseconds(), bestLevel, rxSec, lost)
						break
					}
				}
			}
			mu.Unlock()
		}
	}()

	// per-second: expire pulses that never came back, reset the rate counter
	go func() {
		for range time.Tick(time.Second) {
			mu.Lock()
			var keep []*pulse
			for _, p := range pending {
				if time.Since(p.at) > 5*time.Second {
					results = append(results, *p)
					fmt.Printf("pulse #%-3d %4.0f Hz  LOST (no echo in 5 s)        (rx %d/s, lost %d)\n", p.id, p.hz, rxSec, lost)
				} else {
					keep = append(keep, p)
				}
			}
			pending = keep
			rxSec = 0
			mu.Unlock()
		}
	}()

	// sender: continuous stream, pulse every interval
	seq := uint16(0)
	next := time.Now().Add(2 * time.Second)
	sent := 0
	var burst [][]byte
	tick := time.NewTicker(frameDur)
	for range tick.C {
		var f []byte
		if len(burst) > 0 {
			f, burst = burst[0], burst[1:]
		} else if time.Now().After(next) && (*count == 0 || sent < *count) {
			i := sent % len(freqs)
			f, burst = tones[i][0], tones[i][1:]
			sent++
			mu.Lock()
			pending = append(pending, &pulse{id: sent, hz: freqs[i], at: time.Now()})
			mu.Unlock()
			next = time.Now().Add(*interval)
		} else {
			f = silence
		}
		f = append([]byte{byte(seq), byte(seq >> 8)}, f[2:]...)
		seq++
		if err := c.Write(ctx, websocket.MessageBinary, f); err != nil {
			fmt.Println("write:", err)
			os.Exit(1)
		}
		mu.Lock()
		done := *count > 0 && sent >= *count && len(pending) == 0 && len(burst) == 0
		mu.Unlock()
		if done {
			break
		}
	}
	time.Sleep(200 * time.Millisecond)
	mu.Lock()
	var rtts []time.Duration
	for _, r := range results {
		if r.seen {
			rtts = append(rtts, r.rtt)
		}
	}
	n := len(results)
	mu.Unlock()
	sort.Slice(rtts, func(i, j int) bool { return rtts[i] < rtts[j] })
	fmt.Printf("\n%d pulses, %d echoed", n, len(rtts))
	if len(rtts) > 0 {
		fmt.Printf(", median %d ms, min %d ms, max %d ms", rtts[len(rtts)/2].Milliseconds(), rtts[0].Milliseconds(), rtts[len(rtts)-1].Milliseconds())
	}
	fmt.Println()
}

// runProbe sends frames [seq u16][0xFE][payload...] of size bytes at 50/s and matches echoes by
// seq, so every frame yields a round-trip time. Prints a per-second summary and percentiles.
func runProbe(url, room string, size int, dur time.Duration) {
	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, url, nil)
	if err != nil {
		fmt.Println("dial:", err)
		os.Exit(1)
	}
	defer c.CloseNow()
	hello, _ := json.Marshal(map[string]any{"type": "hello", "mode": "room", "room": room, "delayMs": 0, "name": "probe"})
	c.Write(ctx, websocket.MessageText, hello)
	var mu sync.Mutex
	sentAt := map[uint16]time.Time{}
	var rtts []time.Duration
	secRx, secMax := 0, time.Duration(0)
	go func() {
		for {
			typ, data, err := c.Read(ctx)
			if err != nil {
				return
			}
			if typ != websocket.MessageBinary || len(data) < 3 || data[2] != 0xFE {
				continue
			}
			seq := uint16(data[0]) | uint16(data[1])<<8
			mu.Lock()
			if t, ok := sentAt[seq]; ok {
				d := time.Since(t)
				rtts = append(rtts, d)
				delete(sentAt, seq)
				secRx++
				if d > secMax {
					secMax = d
				}
			}
			mu.Unlock()
		}
	}()
	fmt.Printf("probe: %d-byte frames at 50/s for %s\n", size, dur)
	frame := make([]byte, size)
	frame[2] = 0xFE
	tick := time.NewTicker(20 * time.Millisecond)
	sec := time.NewTicker(time.Second)
	end := time.Now().Add(dur)
	seq := uint16(0)
	sent := 0
	for time.Now().Before(end) {
		select {
		case <-tick.C:
			frame[0], frame[1] = byte(seq), byte(seq>>8)
			mu.Lock()
			sentAt[seq] = time.Now()
			mu.Unlock()
			c.Write(ctx, websocket.MessageBinary, frame)
			seq++
			sent++
		case <-sec.C:
			mu.Lock()
			fmt.Printf("  echoed %2d/s, worst %4d ms\n", secRx, secMax.Milliseconds())
			secRx, secMax = 0, 0
			mu.Unlock()
		}
	}
	time.Sleep(3 * time.Second)
	mu.Lock()
	defer mu.Unlock()
	sort.Slice(rtts, func(i, j int) bool { return rtts[i] < rtts[j] })
	fmt.Printf("sent %d, echoed %d (%.0f%%)", sent, len(rtts), 100*float64(len(rtts))/float64(sent))
	if len(rtts) > 0 {
		p := func(q float64) int64 { return rtts[int(q*float64(len(rtts)-1))].Milliseconds() }
		fmt.Printf(", rtt p10 %d ms, p50 %d ms, p90 %d ms, max %d ms", p(0.1), p(0.5), p(0.9), p(1))
	}
	fmt.Println()
}
