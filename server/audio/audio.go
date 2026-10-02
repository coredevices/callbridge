// Package audio holds the callbridge frame codec (IMA ADPCM, 20 ms self-contained frames) and
// the 1 kHz Tone Detector shared by the server and the pulse tester.
package audio

import (
	"math"
	"time"
)

// Frame header per docs/protocol.md: seq u16 LE, codec u8, predictor i16 LE, index u8, payload.
const (
	FrameHeader  = 6
	CodecAdpcm8k = 0x01
	CodecAdpcm16 = 0x02
	ToneHz       = 1000.0
	BurstMs      = 60
)

var indexAdj = [8]int{-1, -1, -1, -1, 2, 4, 6, 8}
var stepTab = [89]int{
	7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
	50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
	337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
	2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
	15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
}

type adpcm struct {
	predictor int
	index     int
}

func clamp(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func (a *adpcm) decodeNibble(nib int) int16 {
	step := stepTab[a.index]
	delta := step >> 3
	if nib&4 != 0 {
		delta += step
	}
	if nib&2 != 0 {
		delta += step >> 1
	}
	if nib&1 != 0 {
		delta += step >> 2
	}
	if nib&8 != 0 {
		a.predictor = clamp(a.predictor-delta, -32768, 32767)
	} else {
		a.predictor = clamp(a.predictor+delta, -32768, 32767)
	}
	a.index = clamp(a.index+indexAdj[nib&7], 0, 88)
	return int16(a.predictor)
}

func (a *adpcm) encodeSample(s int) int {
	step := stepTab[a.index]
	diff := s - a.predictor
	nib := 0
	if diff < 0 {
		nib = 8
		diff = -diff
	}
	delta := step >> 3
	if diff >= step {
		nib |= 4
		diff -= step
		delta += step
	}
	if diff >= step>>1 {
		nib |= 2
		diff -= step >> 1
		delta += step >> 1
	}
	if diff >= step>>2 {
		nib |= 1
		delta += step >> 2
	}
	if nib&8 != 0 {
		a.predictor = clamp(a.predictor-delta, -32768, 32767)
	} else {
		a.predictor = clamp(a.predictor+delta, -32768, 32767)
	}
	a.index = clamp(a.index+indexAdj[nib&7], 0, 88)
	return nib
}

// DecodeFrame returns the PCM samples and sample rate of a self-contained frame, or nil.
func DecodeFrame(f []byte) ([]int16, int) {
	if len(f) < FrameHeader {
		return nil, 0
	}
	rate := 0
	switch f[2] {
	case CodecAdpcm8k:
		rate = 8000
	case CodecAdpcm16:
		rate = 16000
	default:
		return nil, 0
	}
	a := adpcm{predictor: int(int16(uint16(f[3]) | uint16(f[4])<<8)), index: clamp(int(f[5]), 0, 88)}
	payload := f[FrameHeader:]
	if len(payload) != rate*20/1000/2 { // exactly one 20 ms frame; anything else is not ours
		return nil, 0
	}
	pcm := make([]int16, len(payload)*2)
	for i, b := range payload {
		pcm[2*i] = a.decodeNibble(int(b & 0x0f))
		pcm[2*i+1] = a.decodeNibble(int(b >> 4))
	}
	return pcm, rate
}

// EncodeFrames packs pcm into 20 ms self-contained ADPCM frames at rate, starting at seq.
func EncodeFrames(pcm []int16, rate int, seq uint16) [][]byte {
	n := rate * 20 / 1000
	var out [][]byte
	for off := 0; off+n <= len(pcm); off += n {
		a := adpcm{}
		f := make([]byte, FrameHeader+n/2)
		f[0], f[1] = byte(seq), byte(seq>>8)
		if rate == 16000 {
			f[2] = CodecAdpcm16
		} else {
			f[2] = CodecAdpcm8k
		}
		// predictor 0, index 0 (bytes 3..5 already zero)
		for i := 0; i < n; i++ {
			nib := a.encodeSample(int(pcm[off+i]))
			if i&1 == 0 {
				f[FrameHeader+i/2] = byte(nib)
			} else {
				f[FrameHeader+i/2] |= byte(nib << 4)
			}
		}
		out = append(out, f)
		seq++
	}
	return out
}

// Tone is a BurstMs 1 kHz burst at rate with 5 ms ramps, 0.6 full scale (same as the app's).
func Tone(rate int) []int16 { return ToneAt(rate, ToneHz, BurstMs) }

// ToneAt is a burst of ms milliseconds at hz with 5 ms ramps, 0.6 full scale.
func ToneAt(rate int, hz float64, ms int) []int16 {
	n := rate * ms / 1000
	ramp := float64(rate) * 0.005
	pcm := make([]int16, n)
	for i := range pcm {
		env := math.Min(1, math.Min(float64(i)/ramp, float64(n-1-i)/ramp))
		pcm[i] = int16(math.Sin(2*math.Pi*hz*float64(i)/float64(rate)) * 0.6 * env * 32767)
	}
	return pcm
}

// Goertzel returns the normalised magnitude at ToneHz over pcm (same scale as the app's Detector).
func Goertzel(pcm []int16, rate int) float64 { return GoertzelAt(pcm, rate, ToneHz) }

// GoertzelAt returns the normalised magnitude at hz over pcm.
func GoertzelAt(pcm []int16, rate int, hz float64) float64 {
	k := 2 * math.Cos(2*math.Pi*hz/float64(rate))
	var s1, s2 float64
	for _, v := range pcm {
		s0 := float64(v)/32768 + k*s1 - s2
		s2, s1 = s1, s0
	}
	return math.Sqrt(s1*s1+s2*s2-k*s1*s2) / float64(len(pcm))
}

// Detector tracks a rolling 1 kHz noise floor on a client's frames and, while armed,
// reports the first frame whose Tone level clears the threshold.
type Detector struct {
	floor    float64 // exponential average of the magnitude while not detecting
	seeded   bool    // floor initialised from the first frame
	Armed    bool
	Deadline time.Time // armed expires here; owned by the caller's loop
	Rate     int       // last seen sample rate
	Debug    func(m, threshold float64, samples, rate int)
}

// Floor returns the current noise-floor estimate.
func (d *Detector) Floor() float64 { return d.floor }

const DetMinLevel = 0.0008 // the Pixel's SCO output lands ~40 dB down; still 10x a quiet floor

// Feed returns true on the frame where the burst is first seen (only while armed).
func (d *Detector) Feed(frame []byte) bool {
	pcm, rate := DecodeFrame(frame)
	if pcm == nil {
		return false
	}
	d.Rate = rate
	m := Goertzel(pcm, rate)
	if d.Armed && d.Debug != nil {
		d.Debug(m, math.Max(d.floor*8, DetMinLevel), len(pcm), rate)
	}
	if !d.seeded {
		if d.Armed {
			// no baseline yet and a test is running: judge this frame against the minimum level only
			d.floor, d.seeded = 0, true
		} else {
			d.floor, d.seeded = m, true // baseline from the first quiet frame, whatever its level
		}
	}
	threshold := math.Max(d.floor*8, DetMinLevel)
	if d.Armed && m > threshold {
		d.Armed = false
		return true
	}
	if !d.Armed || m <= threshold { // the burst itself must not raise the floor while armed
		d.floor = d.floor*0.9 + m*0.1
	}
	return false
}
