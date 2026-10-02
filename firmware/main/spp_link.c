#include "spp_link.h"

#include <string.h>
#include <inttypes.h>
#include "esp_log.h"
#include "esp_spp_api.h"

#include "audio_pipe.h"

static const char *TAG = "SPP";

#define SERVER_NAME "callbridge"
#define HDR 3
#define MAX_PAYLOAD 1024

static volatile uint32_t s_handle = 0;
static volatile bool s_up = false;
static volatile bool s_cong = false;
static volatile uint32_t s_write_fail = 0;
uint32_t spp_link_write_failures(void) { return s_write_fail; }

/* reassembly of the inbound byte stream into frames */
static uint8_t s_rx[HDR + MAX_PAYLOAD];
static size_t s_rx_len = 0;

static void feed(const uint8_t *data, size_t len)
{
    while (len > 0) {
        size_t take = len;
        if (s_rx_len + take > sizeof s_rx) take = sizeof s_rx - s_rx_len;
        memcpy(s_rx + s_rx_len, data, take);
        s_rx_len += take; data += take; len -= take;
        for (;;) {
            if (s_rx_len < HDR) break;
            uint8_t ch = s_rx[0];
            uint16_t plen = s_rx[1] | (s_rx[2] << 8);
            if (plen > MAX_PAYLOAD) {        /* corrupt stream: resync by dropping everything */
                ESP_LOGW(TAG, "bad frame len %u, resync", plen);
                s_rx_len = 0;
                break;
            }
            if (s_rx_len < HDR + plen) break;
            if (ch == SPP_CH_AUDIO) audio_pipe_rx_frame(s_rx + HDR, plen);
            else if (ch == SPP_CH_CONTROL) audio_pipe_control(s_rx + HDR, plen);
            size_t used = HDR + plen;
            memmove(s_rx, s_rx + used, s_rx_len - used);
            s_rx_len -= used;
        }
    }
}

static void spp_cb(esp_spp_cb_event_t event, esp_spp_cb_param_t *param)
{
    switch (event) {
    case ESP_SPP_INIT_EVT:
        if (param->init.status == ESP_SPP_SUCCESS) {
            esp_spp_start_srv(ESP_SPP_SEC_AUTHENTICATE, ESP_SPP_ROLE_SLAVE, 0, SERVER_NAME);
        } else {
            ESP_LOGE(TAG, "init failed %d", param->init.status);
        }
        break;
    case ESP_SPP_START_EVT:
        ESP_LOGI(TAG, "server '%s' started (%d)", SERVER_NAME, param->start.status);
        break;
    case ESP_SPP_SRV_OPEN_EVT:
        if (param->srv_open.status != ESP_SPP_SUCCESS) {
            break;
        }
        if (s_up) {   /* one phone at a time */
            ESP_LOGW(TAG, "second connection %" PRIu32 " rejected", param->srv_open.handle);
            esp_spp_disconnect(param->srv_open.handle);
            break;
        }
        s_handle = param->srv_open.handle;
        s_cong = false;
        s_rx_len = 0;
        s_write_fail = 0;
        s_up = true;
        ESP_LOGI(TAG, "phone connected, handle %" PRIu32, s_handle);
        audio_pipe_spp_state(true);
        break;
    case ESP_SPP_CLOSE_EVT:
        if (param->close.handle != s_handle) {
            break;
        }
        ESP_LOGI(TAG, "closed (status %d, by remote %d), %" PRIu32 " write failures", param->close.status, param->close.async, s_write_fail);
        s_up = false;
        audio_pipe_spp_state(false);
        break;
    case ESP_SPP_DATA_IND_EVT:
        if (param->data_ind.handle == s_handle) {
            feed(param->data_ind.data, param->data_ind.len);
        }
        break;
    case ESP_SPP_CONG_EVT:
        if (param->cong.handle == s_handle) {
            s_cong = param->cong.cong;
        }
        break;
    case ESP_SPP_WRITE_EVT:
        if (param->write.handle == s_handle) {
            s_cong = param->write.cong;
            if (param->write.status != ESP_SPP_SUCCESS) {
                s_write_fail++;   /* queued OK but the stack dropped it; visible in `stats` */
            }
        }
        break;
    default:
        break;
    }
}

bool spp_link_is_up(void) { return s_up; }

bool spp_link_send_raw(const uint8_t *framed, size_t len)
{
    if (!s_up || s_cong) {
        return false;
    }
    return esp_spp_write(s_handle, len, (uint8_t *)framed) == ESP_OK;
}

bool spp_link_send(uint8_t channel, const uint8_t *data, size_t len)
{
    if (!s_up || s_cong || len > MAX_PAYLOAD) {
        return false;
    }
    uint8_t buf[HDR + MAX_PAYLOAD];
    buf[0] = channel; buf[1] = len; buf[2] = len >> 8;
    memcpy(buf + HDR, data, len);
    return esp_spp_write(s_handle, HDR + len, buf) == ESP_OK;   /* Bluedroid copies the buffer */
}

void spp_link_init(void)
{
    ESP_ERROR_CHECK(esp_spp_register_callback(spp_cb));
    esp_spp_cfg_t cfg = { .mode = ESP_SPP_MODE_CB, .enable_l2cap_ertm = false, .tx_buffer_size = 0 };
    ESP_ERROR_CHECK(esp_spp_enhanced_init(&cfg));
}
