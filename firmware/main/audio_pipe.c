#include "audio_pipe.h"

#include <string.h>
#include <inttypes.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/ringbuf.h"
#include "freertos/semphr.h"
#include "freertos/queue.h"
#include "esp_log.h"
#include "esp_hf_client_api.h"
#include "esp_timer.h"

#include "adpcm.h"
#include "ble_gatt.h"
#include "spp_link.h"
#include "link_policy.h"

static const char *TAG = "PIPE";

/* protocol constants */
#define FRAME_HDR        6
#define CODEC_ADPCM_8K   0x01
#define CODEC_ADPCM_16K  0x02
#define CODEC_BLAST      0xFF
#define FRAME_MS         20

#define CTRL_CALL_STATE  0x01
#define CTRL_CODEC       0x02
#define CTRL_STATS       0x03
#define CTRL_PONG        0x04
#define CTRL_ANSWER      0x10
#define CTRL_HANGUP      0x11
#define CTRL_LOOPBACK    0x12
#define CTRL_DIAL        0x13
#define CTRL_REQ_STATS   0x14
#define CTRL_PING        0x15
#define CTRL_BLAST       0x16
#define CTRL_SPP_BATCH   0x17   /* u8 frames per SPP write (1..4) */
#define CTRL_KEEPALIVE   0x18   /* no-op; the phone sends it to keep RFCOMM credits flowing */
#define CTRL_LINK_ECHO   0x19   /* u8: 1 = send downlink frames straight back up (bypasses SCO; link test) */
#define CTRL_BLE_ITVL    0x1A   /* u8: BLE connection interval in 1.25 ms units */

/* ring sizes: sco_in holds 100 ms, sco_out (jitter buffer) 400 ms, both at 16 kHz */
#define SCO_IN_BYTES     (16000 * 2 / 10)
#define SCO_OUT_BYTES    (16000 * 2 * 4 / 10)
#define JITTER_PREFILL_MS 20
/* depth caps: anything older than this is discarded so the SCO-out ring never becomes a latency tank */
#define LOOPBACK_MAX_MS   40
#define JITTER_MAX_MS     100
#define MAX_PCM_FRAME    (16000 * 2 * FRAME_MS / 1000)   /* 640 bytes */

static RingbufHandle_t s_sco_in;
static RingbufHandle_t s_sco_out;
static SemaphoreHandle_t s_out_lock;      /* serializes writers of s_sco_out */
static volatile bool s_sco_up = false;
static volatile bool s_msbc = false;
static volatile bool s_loopback = true;   /* on by default: proves the audio path without an app */
static volatile bool s_blast = false;
static volatile bool s_link_echo = false;
typedef struct { uint16_t len; uint8_t data[FRAME_HDR + 160]; } echo_item_t;
static QueueHandle_t s_echo_q;
static volatile bool s_ble_up = false;
static volatile uint32_t s_sco_gen = 0;    /* bumped on every SCO (re)connect; pipe task resyncs */
/* call state inputs, combined by derive_call_state() */
static volatile bool s_call_active = false;
static volatile uint8_t s_call_setup = 0;  /* 0 none, 1 incoming, 2 outgoing */
static volatile uint8_t s_call_state = 0;  /* advertised: 0 idle, 1 incoming, 2 dialing, 3 active, 4 sco_up */

static struct {
    uint32_t tx_frames, rx_frames, rx_lost, rx_late, sco_drops;
    uint32_t underruns, tx_dropped;
    uint32_t sco_in_bytes, sco_out_bytes, link_rx_bytes;   /* raw byte counters for rate checks */
} s_st;

static uint16_t s_tx_seq = 0;
static int s_last_rx_seq = -1;

static volatile bool s_spp_up = false;
static inline bool link_up(void) { return s_spp_up || s_ble_up; }

/* SPP when the phone has it open (Android), else BLE GATT.
 * The link carries a limited number of packets per second (measured ~25/s with SCO active, whatever
 * their size), so over SPP several frames are batched into one write. Costs (N-1)*20 ms latency. */
#define SPP_BATCH_MAX 4
#define MAX_LINK_FRAME 244   /* largest frame any path produces (blast frames) */
static uint8_t s_batch[SPP_BATCH_MAX * (3 + MAX_LINK_FRAME)];
static size_t s_batch_len = 0;
static int s_batch_n = 0;
static volatile int s_spp_batch = 3;   /* frames per SPP write; console `batch <n>` */

#define BLE_PACKET_MAX 244   /* MTU 247 - 3 */

static bool send_audio(const uint8_t *frame, size_t len)
{
    int want = s_spp_batch < 1 ? 1 : s_spp_batch > SPP_BATCH_MAX ? SPP_BATCH_MAX : s_spp_batch;
    if (!s_spp_up) {
        /* BLE: [len u8][frame] records, as many as `batch` and the packet allow. The link is limited
         * in packets per second (connection events shared with eSCO), not bytes. */
        if (len > 255) return false;
        if (s_batch_len + 1 + len > BLE_PACKET_MAX && s_batch_n > 0) {
            ble_gatt_notify_audio(s_batch, s_batch_len);
            s_batch_len = 0; s_batch_n = 0;
        }
        s_batch[s_batch_len++] = (uint8_t)len;
        memcpy(s_batch + s_batch_len, frame, len); s_batch_len += len;
        if (++s_batch_n < want) return true;
        bool ok = ble_gatt_notify_audio(s_batch, s_batch_len);
        s_batch_len = 0; s_batch_n = 0;
        return ok;
    }
    if (len > MAX_LINK_FRAME || s_batch_len + 3 + len > sizeof s_batch) {
        s_batch_len = 0; s_batch_n = 0;   /* cannot happen with the sizes above; never overrun */
        return false;
    }
    /* stream framing per frame: [channel][len u16][payload], same as spp_link_send builds */
    s_batch[s_batch_len++] = SPP_CH_AUDIO;
    s_batch[s_batch_len++] = len; s_batch[s_batch_len++] = len >> 8;
    memcpy(s_batch + s_batch_len, frame, len); s_batch_len += len;
    s_batch_n++;
    if (s_batch_n < want) {
        return true;   /* queued; counted as sent when the batch goes */
    }
    bool ok = spp_link_send_raw(s_batch, s_batch_len);
    s_batch_len = 0; s_batch_n = 0;
    return ok;
}

void audio_pipe_set_spp_batch(int n) { s_spp_batch = n; }

/* pipeline-task only: drop a half-built batch at session boundaries so stale frames never lead a new stream */
static void batch_reset(void) { s_batch_len = 0; s_batch_n = 0; }
static volatile uint32_t s_link_gen = 0;   /* bumped on link up/down; pipe task resets its batch */

static void notify_control(const uint8_t *msg, size_t len)
{
    if (s_spp_up) spp_link_send(SPP_CH_CONTROL, msg, len);
    else if (s_ble_up) ble_gatt_notify_control(msg, len);
}

static void send_call_state(void)
{
    uint8_t m[2] = { CTRL_CALL_STATE, s_call_state };
    notify_control(m, sizeof m);
}

static void send_stats(void)
{
    uint8_t m[1 + 5 * 4];
    m[0] = CTRL_STATS;
    uint32_t v[5] = { s_st.tx_frames, s_st.rx_frames, s_st.rx_lost, s_st.rx_late, s_st.sco_drops };
    memcpy(&m[1], v, sizeof v);   /* little-endian host */
    notify_control(m, sizeof m);
}

/* ---- jitter buffer / SCO out ---- */

static void out_write(const uint8_t *pcm, size_t bytes, uint32_t max_ms)
{
    size_t rate_bytes_per_ms = (s_msbc ? 16000 : 8000) * 2 / 1000;
    size_t max_bytes = max_ms * rate_bytes_per_ms;
    xSemaphoreTake(s_out_lock, portMAX_DELAY);
    /* drop the oldest audio until the new frame fits under the cap: latency beats completeness here */
    size_t used = SCO_OUT_BYTES - xRingbufferGetCurFreeSize(s_sco_out);
    while (used + bytes > max_bytes) {
        size_t n = 0;
        uint8_t *p = xRingbufferReceiveUpTo(s_sco_out, &n, 0, used + bytes - max_bytes);
        if (!p) break;
        vRingbufferReturnItem(s_sco_out, p);
        used -= n;
        s_st.rx_late++;
    }
    if (xRingbufferGetCurFreeSize(s_sco_out) >= bytes) {
        xRingbufferSend(s_sco_out, pcm, bytes, 0);
    }
    xSemaphoreGive(s_out_lock);
}

static void out_prefill_if_empty(void)
{
    /* ponytail: fixed 40 ms prefill = ~60 ms target depth with one frame; adaptive depth if jitter demands */
    static const uint8_t zeros[16000 * 2 * JITTER_PREFILL_MS / 1000] = {0};
    size_t rate = s_msbc ? 16000 : 8000;
    xSemaphoreTake(s_out_lock, portMAX_DELAY);
    if (xRingbufferGetCurFreeSize(s_sco_out) == SCO_OUT_BYTES) {
        xRingbufferSend(s_sco_out, zeros, rate * 2 * JITTER_PREFILL_MS / 1000, 0);
    }
    xSemaphoreGive(s_out_lock);
}

uint32_t audio_pipe_sco_out(uint8_t *pcm, uint32_t bytes)
{
    uint32_t got = 0;
    s_st.sco_out_bytes += bytes;
    if (xSemaphoreTake(s_out_lock, pdMS_TO_TICKS(5)) != pdTRUE) {
        memset(pcm, 0, bytes);
        s_st.underruns++;
        return bytes;
    }
    while (got < bytes) {
        size_t n = 0;
        uint8_t *p = xRingbufferReceiveUpTo(s_sco_out, &n, 0, bytes - got);
        if (!p) {
            break;
        }
        memcpy(pcm + got, p, n);
        vRingbufferReturnItem(s_sco_out, p);
        got += n;
    }
    xSemaphoreGive(s_out_lock);
    if (got < bytes) {
        memset(pcm + got, 0, bytes - got);   /* underrun: pad with silence, keep timing */
        s_st.underruns++;
    }
    return bytes;
}

/* ---- SCO in ---- */

void audio_pipe_sco_in(const uint8_t *pcm, uint32_t bytes)
{
    s_st.sco_in_bytes += bytes;
    if (s_loopback) {
        out_write(pcm, bytes, LOOPBACK_MAX_MS);
    }
    if (xRingbufferSend(s_sco_in, pcm, bytes, 0) != pdTRUE) {
        s_st.sco_drops++;
    }
    esp_hf_client_outgoing_data_ready();
}

static void derive_call_state(void)
{
    uint8_t st = s_sco_up ? 4 : s_call_active ? 3 : s_call_setup == 1 ? 1 : s_call_setup == 2 ? 2 : 0;
    if (s_call_state != st) {
        s_call_state = st;
        send_call_state();
    }
}

void audio_pipe_sco_state(bool connected, bool msbc)
{
    s_msbc = msbc;
    s_sco_up = connected;
    if (connected) {
        s_sco_gen++;   /* pipe task flushes and resyncs; the rings are only touched from their owners */
        s_last_rx_seq = -1;
        uint8_t m[2] = { CTRL_CODEC, msbc ? CODEC_ADPCM_16K : CODEC_ADPCM_8K };
        notify_control(m, sizeof m);
    }
    derive_call_state();
}

void audio_pipe_call_state(uint8_t state)
{
    /* inputs from the HFP indicators: 0 clears setup, 1 incoming, 2 dialing, 3 active, 5 call ended */
    switch (state) {
    case 0: s_call_setup = 0; break;
    case 1: s_call_setup = 1; break;
    case 2: s_call_setup = 2; break;
    case 3: s_call_active = true; s_call_setup = 0; break;
    case 5: s_call_active = false; s_call_setup = 0; break;
    }
    derive_call_state();
}

/* ---- BLE rx ---- */

void audio_pipe_rx_frame(const uint8_t *f, uint16_t len)
{
    s_st.link_rx_bytes += len;
    if (s_link_echo) {
        /* link test: hand the frame to the pipeline task, which owns the link batch buffer */
        echo_item_t it;
        it.len = len > sizeof it.data ? sizeof it.data : len;
        memcpy(it.data, f, it.len);
        if (xQueueSend(s_echo_q, &it, 0) != pdTRUE) s_st.rx_late++;
        s_st.rx_frames++;
        return;
    }
    if (len < FRAME_HDR) {
        return;
    }
    uint16_t seq = f[0] | (f[1] << 8);
    uint8_t codec = f[2];
    adpcm_state_t st = { .predictor = (int16_t)(f[3] | (f[4] << 8)), .index = f[5] > 88 ? 88 : f[5] };
    size_t n = len - FRAME_HDR;
    if (codec != CODEC_ADPCM_8K && codec != CODEC_ADPCM_16K) {
        return;
    }
    if (s_last_rx_seq >= 0) {
        int d = (seq - s_last_rx_seq) & 0xffff;
        if (d > 1 && d < 32768) s_st.rx_lost += d - 1;
    }
    s_last_rx_seq = seq;
    s_st.rx_frames++;
    if (s_loopback || !s_sco_up) {
        return;   /* loopback owns the SCO out path */
    }
    int16_t pcm[MAX_PCM_FRAME / 2];
    if (n * 2 > sizeof pcm / sizeof pcm[0]) n = sizeof pcm / sizeof pcm[0] / 2;
    adpcm_decode(&st, f + FRAME_HDR, n, pcm);
    size_t samples = n * 2;
    if (codec == CODEC_ADPCM_8K && s_msbc) {
        /* the link is 8 kHz; a wideband (mSBC) call wants 16 kHz: linear interpolation, in place from the end */
        if (samples * 2 > sizeof pcm / sizeof pcm[0]) samples = sizeof pcm / sizeof pcm[0] / 2;
        for (int i = (int)samples - 1; i >= 0; i--) {
            int16_t a = pcm[i], b = i + 1 < (int)samples ? pcm[i + 1] : pcm[i];
            pcm[2 * i] = a;
            pcm[2 * i + 1] = (int16_t)((a + b) / 2);
        }
        samples *= 2;
    } else if (codec == CODEC_ADPCM_16K && !s_msbc) {
        s_st.rx_late++;   /* 16 kHz frames on a narrowband call: nobody sends these today */
        return;
    }
    out_prefill_if_empty();
    out_write((uint8_t *)pcm, samples * 2, JITTER_MAX_MS);
}

void audio_pipe_control(const uint8_t *m, uint16_t len)
{
    if (len < 1) return;
    switch (m[0]) {
    case CTRL_ANSWER:   esp_hf_client_answer_call(); break;
    case CTRL_HANGUP:   esp_hf_client_reject_call(); break;
    case CTRL_LOOPBACK: if (len >= 2) audio_pipe_set_loopback(m[1] != 0); break;
    case CTRL_DIAL: {
        char num[32] = {0};
        size_t n = len - 1 < sizeof num - 1 ? len - 1 : sizeof num - 1;
        memcpy(num, m + 1, n);
        esp_hf_client_dial(num);
        break;
    }
    case CTRL_REQ_STATS: send_stats(); break;
    case CTRL_PING: {
        uint8_t pong[5] = { CTRL_PONG, 0, 0, 0, 0 };
        if (len >= 5) memcpy(&pong[1], &m[1], 4);
        notify_control(pong, sizeof pong);
        break;
    }
    case CTRL_BLAST: if (len >= 2) s_blast = m[1] != 0; break;
    case CTRL_SPP_BATCH: if (len >= 2) audio_pipe_set_spp_batch(m[1]); break;
    case CTRL_KEEPALIVE: break;
    case CTRL_BLE_ITVL: if (len >= 2) ble_gatt_set_conn_interval(m[1]); break;
    case CTRL_LINK_ECHO:
        if (len >= 2) {
            s_link_echo = m[1] != 0;
            s_sco_gen++;   /* resync the SCO pipeline either way */
            ESP_LOGI(TAG, "link echo %s", s_link_echo ? "on" : "off");
        }
        break;
    default: ESP_LOGW(TAG, "unknown control 0x%02x", m[0]);
    }
}

void audio_pipe_ble_state(bool connected)
{
    s_ble_up = connected;
    s_link_gen++;
    if (!link_up()) s_blast = false;
    else send_call_state();
}

void audio_pipe_spp_state(bool connected)
{
    s_spp_up = connected;
    s_link_gen++;
    if (!link_up()) s_blast = false;
    else send_call_state();
}

void audio_pipe_hfp_disconnected(void)
{
    /* no final CALL=0 arrives when the SLC drops mid-call; clear the indicator inputs ourselves */
    s_call_active = false;
    s_call_setup = 0;
    derive_call_state();
}

void audio_pipe_set_loopback(bool on)
{
    s_loopback = on;
    ESP_LOGI(TAG, "loopback %s", on ? "on" : "off");
}

bool audio_pipe_loopback(void) { return s_loopback; }

void audio_pipe_print_stats(void)
{
    printf("sco_up=%d msbc=%d ble=%d spp=%d loopback=%d blast=%d\n", s_sco_up, s_msbc, s_ble_up, s_spp_up, s_loopback, s_blast);
    printf("tx_frames=%" PRIu32 " tx_dropped=%" PRIu32 " rx_frames=%" PRIu32 " rx_lost=%" PRIu32
           " rx_late=%" PRIu32 " sco_drops=%" PRIu32 " underruns=%" PRIu32 "\n",
           s_st.tx_frames, s_st.tx_dropped, s_st.rx_frames, s_st.rx_lost, s_st.rx_late, s_st.sco_drops, s_st.underruns);
    link_policy_print_window();
    printf("spp_write_failures=%" PRIu32 " sco_in_bytes=%" PRIu32 " sco_out_bytes=%" PRIu32 " link_rx_bytes=%" PRIu32 " uptime_ms=%" PRIu32 "\n",
           spp_link_write_failures(), s_st.sco_in_bytes, s_st.sco_out_bytes, s_st.link_rx_bytes, (uint32_t)(esp_timer_get_time() / 1000));
}

/* ---- pipeline task (core 1): SCO in -> ADPCM -> BLE ---- */

static void pipe_task(void *arg)
{
    uint8_t pcm[MAX_PCM_FRAME];
    uint8_t frame[FRAME_HDR + MAX_PCM_FRAME / 4];
    size_t have = 0;
    uint32_t gen = 0, lgen = 0;
    for (;;) {
        if (lgen != s_link_gen) {
            lgen = s_link_gen;
            batch_reset();
        }
        if (gen != s_sco_gen) {
            /* new SCO link: drop stale audio, restart sequence; this task owns s_sco_in receives */
            gen = s_sco_gen;
            size_t n;
            void *p;
            while ((p = xRingbufferReceiveUpTo(s_sco_in, &n, 0, SCO_IN_BYTES))) vRingbufferReturnItem(s_sco_in, p);
            xSemaphoreTake(s_out_lock, portMAX_DELAY);
            while ((p = xRingbufferReceiveUpTo(s_sco_out, &n, 0, SCO_OUT_BYTES))) vRingbufferReturnItem(s_sco_out, p);
            xSemaphoreGive(s_out_lock);
            have = 0;
            s_tx_seq = 0;
            batch_reset();
        }
        if (s_link_echo) {
            echo_item_t it;
            if (xQueueReceive(s_echo_q, &it, pdMS_TO_TICKS(50)) == pdTRUE) {
                if (send_audio(it.data, it.len)) s_st.tx_frames++; else s_st.tx_dropped++;
            }
            /* keep the SCO-in ring from overflowing while it is not consumed */
            size_t n; void *p;
            while ((p = xRingbufferReceiveUpTo(s_sco_in, &n, 0, SCO_IN_BYTES))) vRingbufferReturnItem(s_sco_in, p);
            have = 0;
            continue;
        }
        if (s_blast) {
            /* throughput test: max-rate 244-byte frames, codec 0xFF */
            uint8_t b[244];
            memset(b, 0xA5, sizeof b);
            b[0] = s_tx_seq; b[1] = s_tx_seq >> 8; b[2] = CODEC_BLAST;
            if (send_audio(b, sizeof b)) { s_tx_seq++; s_st.tx_frames++; }
            else { s_st.tx_dropped++; }
            vTaskDelay(pdMS_TO_TICKS(5));   /* 200 frames/s offered, well above any BLE ceiling here; never starve */
            continue;
        }
        bool msbc = s_msbc;   /* snapshot: the rate must not change under one frame */
        size_t want = (msbc ? 16000 : 8000) * 2 * FRAME_MS / 1000;
        if (have >= want) {
            have = 0;   /* codec changed under us without a generation bump; start the frame over */
        }
        size_t n = 0;
        uint8_t *p = xRingbufferReceiveUpTo(s_sco_in, &n, pdMS_TO_TICKS(100), want - have);
        if (!p) {
            continue;
        }
        memcpy(pcm + have, p, n);
        vRingbufferReturnItem(s_sco_in, p);
        have += n;
        if (gen != s_sco_gen) {
            continue;   /* SCO (re)connected while we waited: resync before using these samples */
        }
        if (have < want) {
            continue;
        }
        have = 0;
        if (!link_up()) {
            continue;
        }
        adpcm_state_t st = { .predictor = 0, .index = 0 };
        /* ponytail: encoder state resets per frame (predictor 0, index 0) so every frame is self-contained;
         * carrying state across frames would improve quality at the cost of loss tolerance. */
        /* the link always carries 8 kHz frames (86 bytes); a wideband call is decimated by 2 */
        int16_t *p16 = (int16_t *)pcm;
        size_t samples = want / 2;
        if (msbc) {
            for (size_t i = 0; i < samples / 2; i++) p16[i] = (int16_t)((p16[2 * i] + p16[2 * i + 1]) / 2);
            samples /= 2;
        }
        frame[0] = s_tx_seq; frame[1] = s_tx_seq >> 8;
        frame[2] = CODEC_ADPCM_8K;
        frame[3] = 0; frame[4] = 0; frame[5] = 0;
        adpcm_encode(&st, p16, samples, frame + FRAME_HDR);
        if (send_audio(frame, FRAME_HDR + samples / 2)) {
            s_st.tx_frames++;
        } else {
            s_st.tx_dropped++;
        }
        s_tx_seq++;
    }
}

void audio_pipe_init(void)
{
    s_sco_in = xRingbufferCreate(SCO_IN_BYTES, RINGBUF_TYPE_BYTEBUF);
    s_sco_out = xRingbufferCreate(SCO_OUT_BYTES, RINGBUF_TYPE_BYTEBUF);
    s_out_lock = xSemaphoreCreateMutex();
    s_echo_q = xQueueCreate(16, sizeof(echo_item_t));
    xTaskCreatePinnedToCore(pipe_task, "audio_pipe", 4096, NULL, 10, NULL, 1);
}
