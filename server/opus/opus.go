// Package opus is a minimal cgo binding to libopus for the dev tools (pulse tester). The server
// itself does not import it, so the server binary stays cgo-free.
package opus

/*
#cgo pkg-config: opus
#include <opus.h>
#include <stdlib.h>
static OpusEncoder *enc_new(int rate, int bitrate, int *err) {
	OpusEncoder *e = opus_encoder_create(rate, 1, OPUS_APPLICATION_VOIP, err);
	if (*err == OPUS_OK) {
		opus_encoder_ctl(e, OPUS_SET_BITRATE(bitrate));
		opus_encoder_ctl(e, OPUS_SET_VBR(0));
		opus_encoder_ctl(e, OPUS_SET_COMPLEXITY(5));
	}
	return e;
}
*/
import "C"

import (
	"fmt"
	"unsafe"
)

// Encoder encodes 16-bit mono PCM frames.
type Encoder struct{ e *C.OpusEncoder }

// NewEncoder makes a CBR VoIP encoder.
func NewEncoder(rate, bitrate int) (*Encoder, error) {
	var err C.int
	e := C.enc_new(C.int(rate), C.int(bitrate), &err)
	if err != C.OPUS_OK {
		return nil, fmt.Errorf("opus_encoder_create: %d", int(err))
	}
	return &Encoder{e}, nil
}

// Encode one frame of pcm (len = rate*20ms) into an Opus packet.
func (e *Encoder) Encode(pcm []int16) ([]byte, error) {
	out := make([]byte, 400)
	n := C.opus_encode(e.e, (*C.opus_int16)(unsafe.Pointer(&pcm[0])), C.int(len(pcm)), (*C.uchar)(unsafe.Pointer(&out[0])), C.opus_int32(len(out)))
	if n < 0 {
		return nil, fmt.Errorf("opus_encode: %d", int(n))
	}
	return out[:n], nil
}

// Decoder decodes Opus packets to 16-bit mono PCM.
type Decoder struct {
	d    *C.OpusDecoder
	rate int
}

// NewDecoder makes a decoder producing pcm at rate.
func NewDecoder(rate int) (*Decoder, error) {
	var err C.int
	d := C.opus_decoder_create(C.int(rate), 1, &err)
	if err != C.OPUS_OK {
		return nil, fmt.Errorf("opus_decoder_create: %d", int(err))
	}
	return &Decoder{d, rate}, nil
}

// Decode one packet (20 ms).
func (d *Decoder) Decode(pkt []byte) ([]int16, error) {
	pcm := make([]int16, d.rate*20/1000)
	n := C.opus_decode(d.d, (*C.uchar)(unsafe.Pointer(&pkt[0])), C.opus_int32(len(pkt)), (*C.opus_int16)(unsafe.Pointer(&pcm[0])), C.int(len(pcm)), 0)
	if n < 0 {
		return nil, fmt.Errorf("opus_decode: %d", int(n))
	}
	return pcm[:n], nil
}
