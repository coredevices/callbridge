#include "adpcm.h"

static const int8_t index_adj[8] = { -1, -1, -1, -1, 2, 4, 6, 8 };
static const int16_t step_tab[89] = {
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
    50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
    337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
    2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
    15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
};

static inline int clamp16(int v) { return v > 32767 ? 32767 : (v < -32768 ? -32768 : v); }
static inline int clampidx(int v) { return v > 88 ? 88 : (v < 0 ? 0 : v); }

static uint8_t enc_sample(adpcm_state_t *st, int sample)
{
    int step = step_tab[st->index];
    int diff = sample - st->predictor;
    int nib = 0;
    if (diff < 0) { nib = 8; diff = -diff; }
    int delta = step >> 3;
    if (diff >= step) { nib |= 4; diff -= step; delta += step; }
    if (diff >= (step >> 1)) { nib |= 2; diff -= step >> 1; delta += step >> 1; }
    if (diff >= (step >> 2)) { nib |= 1; delta += step >> 2; }
    st->predictor = clamp16((nib & 8) ? st->predictor - delta : st->predictor + delta);
    st->index = clampidx(st->index + index_adj[nib & 7]);
    return nib;
}

static int16_t dec_sample(adpcm_state_t *st, int nib)
{
    int step = step_tab[st->index];
    int delta = step >> 3;
    if (nib & 4) delta += step;
    if (nib & 2) delta += step >> 1;
    if (nib & 1) delta += step >> 2;
    st->predictor = clamp16((nib & 8) ? st->predictor - delta : st->predictor + delta);
    st->index = clampidx(st->index + index_adj[nib & 7]);
    return st->predictor;
}

void adpcm_encode(adpcm_state_t *st, const int16_t *pcm, size_t n, uint8_t *out)
{
    for (size_t i = 0; i < n; i++) {
        uint8_t nib = enc_sample(st, pcm[i]);
        if ((i & 1) == 0) out[i >> 1] = nib;
        else out[i >> 1] |= nib << 4;
    }
}

void adpcm_decode(adpcm_state_t *st, const uint8_t *in, size_t n, int16_t *pcm)
{
    for (size_t i = 0; i < n; i++) {
        pcm[2 * i] = dec_sample(st, in[i] & 0x0f);
        pcm[2 * i + 1] = dec_sample(st, in[i] >> 4);
    }
}
