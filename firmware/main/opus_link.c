#include "opus_link.h"

#include "esp_log.h"
#include "encoder/impl/esp_opus_enc.h"
#include "decoder/impl/esp_opus_dec.h"

#include <string.h>
#include <stdlib.h>

static const char *TAG = "OPUS";
static void *s_enc, *s_dec;
static uint8_t *s_enc_out;     /* the encoder wants an output buffer of its recommended size */
static int s_enc_out_size;

bool opus_link_init(void)
{
    esp_opus_enc_config_t ec = ESP_OPUS_ENC_CONFIG_DEFAULT();
    ec.sample_rate = OPUS_LINK_RATE;
    ec.channel = ESP_AUDIO_MONO;
    ec.bits_per_sample = 16;
    ec.bitrate = OPUS_LINK_BITRATE;
    ec.frame_duration = ESP_OPUS_ENC_FRAME_DURATION_20_MS;
    ec.application_mode = ESP_OPUS_ENC_APPLICATION_VOIP;
    ec.complexity = 0;          /* complexity 2 took 32 ms per 20 ms frame at 160 MHz with state in PSRAM */
    ec.enable_vbr = false;      /* CBR: every frame is exactly 40 bytes, so packing is predictable */
    ec.enable_fec = false;
    esp_audio_err_t e1 = esp_opus_enc_open(&ec, sizeof ec, &s_enc);
    int in_size = 0;
    if (e1 == ESP_AUDIO_ERR_OK) {
        esp_opus_enc_get_frame_size(s_enc, &in_size, &s_enc_out_size);
        s_enc_out = malloc(s_enc_out_size);
    }

    esp_opus_dec_cfg_t dc = ESP_OPUS_DEC_CONFIG_DEFAULT();
    dc.sample_rate = OPUS_LINK_RATE;
    dc.channel = ESP_AUDIO_MONO;
    dc.frame_duration = ESP_OPUS_DEC_FRAME_DURATION_20_MS;
    esp_audio_err_t e2 = esp_opus_dec_open(&dc, sizeof dc, &s_dec);
    ESP_LOGI(TAG, "opus %d Hz %d bps: encoder %d (in %d B, out buf %d B), decoder %d", OPUS_LINK_RATE, OPUS_LINK_BITRATE, e1, in_size, s_enc_out_size, e2);
    return e1 == ESP_AUDIO_ERR_OK && e2 == ESP_AUDIO_ERR_OK;
}

int opus_link_encode(const int16_t *pcm, uint8_t *out, int out_max)
{
    if (!s_enc) return -1;
    if (!s_enc_out) return -1;
    esp_audio_enc_in_frame_t in = { .buffer = (uint8_t *)pcm, .len = OPUS_LINK_SAMPLES * 2 };
    esp_audio_enc_out_frame_t o = { .buffer = s_enc_out, .len = (uint32_t)s_enc_out_size };
    esp_audio_err_t e = esp_opus_enc_process(s_enc, &in, &o);
    if (e != ESP_AUDIO_ERR_OK || (int)o.encoded_bytes > out_max) {
        static int warned;
        if (warned++ < 3) ESP_LOGW(TAG, "encode: err %d, %u bytes", e, (unsigned)o.encoded_bytes);
        return -1;
    }
    memcpy(out, s_enc_out, o.encoded_bytes);
    return (int)o.encoded_bytes;
}

int opus_link_decode(const uint8_t *in, int len, int16_t *out)
{
    if (!s_dec) return -1;
    esp_audio_dec_in_raw_t raw = { .buffer = (uint8_t *)in, .len = (uint32_t)len };
    esp_audio_dec_out_frame_t o = { .buffer = (uint8_t *)out, .len = OPUS_LINK_SAMPLES * 2 };
    esp_audio_dec_info_t info;
    if (esp_opus_dec_decode(s_dec, &raw, &o, &info) != ESP_AUDIO_ERR_OK) return -1;
    return (int)(o.decoded_size / 2);
}
