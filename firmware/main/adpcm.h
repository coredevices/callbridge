#pragma once
#include <stdint.h>
#include <stddef.h>

/* IMA/DVI ADPCM, matching app/shared Adpcm.kt. State is carried across calls. */
typedef struct {
    int16_t predictor;
    uint8_t index;
} adpcm_state_t;

/* Encodes n (even) samples into n/2 bytes, low nibble first. */
void adpcm_encode(adpcm_state_t *st, const int16_t *pcm, size_t n, uint8_t *out);
/* Decodes n bytes into 2n samples. */
void adpcm_decode(adpcm_state_t *st, const uint8_t *in, size_t n, int16_t *pcm);
