#include "ble_gatt.h"

#include <string.h>
#include "esp_log.h"
#include "esp_gap_ble_api.h"
#include "esp_gatts_api.h"
#include "esp_gatt_common_api.h"
#include "esp_timer.h"

#include "audio_pipe.h"

static const char *TAG = "GATT";

#define APP_ID        0
#define SVC_INST_ID   0
#define DEVICE_NAME   "callbridge"
#define LOCAL_MTU     247
#define VAL_MAX_LEN   244

/* CB00000x-5F6A-4E2B-9C1D-0C2A7B3E4D50, little-endian byte order as Bluedroid wants it */
#define UUID128(last) { 0x50, 0x4D, 0x3E, 0x7B, 0x2A, 0x0C, 0x1D, 0x9C, 0x2B, 0x4E, 0x6A, 0x5F, last, 0x00, 0x00, 0xCB }
static const uint8_t svc_uuid[16]      = UUID128(0x01);
static const uint8_t audio_tx_uuid[16] = UUID128(0x02);
static const uint8_t audio_rx_uuid[16] = UUID128(0x03);
static const uint8_t control_uuid[16]  = UUID128(0x04);

enum {
    IDX_SVC,
    IDX_TX_DECL, IDX_TX_VAL, IDX_TX_CCC,
    IDX_RX_DECL, IDX_RX_VAL,
    IDX_CTL_DECL, IDX_CTL_VAL, IDX_CTL_CCC,
    IDX_NB,
};

static const uint16_t primary_service_uuid = ESP_GATT_UUID_PRI_SERVICE;
static const uint16_t char_decl_uuid       = ESP_GATT_UUID_CHAR_DECLARE;
static const uint16_t char_ccc_uuid        = ESP_GATT_UUID_CHAR_CLIENT_CONFIG;
static const uint8_t prop_notify           = ESP_GATT_CHAR_PROP_BIT_NOTIFY;
static const uint8_t prop_write_nr         = ESP_GATT_CHAR_PROP_BIT_WRITE_NR;
static const uint8_t prop_rw_notify        = ESP_GATT_CHAR_PROP_BIT_READ | ESP_GATT_CHAR_PROP_BIT_WRITE | ESP_GATT_CHAR_PROP_BIT_NOTIFY;
static uint8_t ccc_tx[2] = {0, 0};
static uint8_t ccc_ctl[2] = {0, 0};
static uint8_t empty_val[1] = {0};
static uint8_t ctl_val[2] = {0x01, 0x00};   /* last call state, readable */

static const esp_gatts_attr_db_t gatt_db[IDX_NB] = {
    [IDX_SVC] = {{ESP_GATT_AUTO_RSP}, {ESP_UUID_LEN_16, (uint8_t *)&primary_service_uuid, ESP_GATT_PERM_READ,
                 sizeof svc_uuid, sizeof svc_uuid, (uint8_t *)svc_uuid}},

    [IDX_TX_DECL] = {{ESP_GATT_AUTO_RSP}, {ESP_UUID_LEN_16, (uint8_t *)&char_decl_uuid, ESP_GATT_PERM_READ, 1, 1, (uint8_t *)&prop_notify}},
    [IDX_TX_VAL]  = {{ESP_GATT_AUTO_RSP}, {ESP_UUID_LEN_128, (uint8_t *)audio_tx_uuid, 0, VAL_MAX_LEN, sizeof empty_val, empty_val}},
    [IDX_TX_CCC]  = {{ESP_GATT_AUTO_RSP}, {ESP_UUID_LEN_16, (uint8_t *)&char_ccc_uuid, ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE, 2, 2, ccc_tx}},

    [IDX_RX_DECL] = {{ESP_GATT_AUTO_RSP}, {ESP_UUID_LEN_16, (uint8_t *)&char_decl_uuid, ESP_GATT_PERM_READ, 1, 1, (uint8_t *)&prop_write_nr}},
    [IDX_RX_VAL]  = {{ESP_GATT_AUTO_RSP}, {ESP_UUID_LEN_128, (uint8_t *)audio_rx_uuid, ESP_GATT_PERM_WRITE, VAL_MAX_LEN, sizeof empty_val, empty_val}},

    [IDX_CTL_DECL] = {{ESP_GATT_AUTO_RSP}, {ESP_UUID_LEN_16, (uint8_t *)&char_decl_uuid, ESP_GATT_PERM_READ, 1, 1, (uint8_t *)&prop_rw_notify}},
    [IDX_CTL_VAL]  = {{ESP_GATT_AUTO_RSP}, {ESP_UUID_LEN_128, (uint8_t *)control_uuid, ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE, VAL_MAX_LEN, sizeof ctl_val, ctl_val}},
    [IDX_CTL_CCC]  = {{ESP_GATT_AUTO_RSP}, {ESP_UUID_LEN_16, (uint8_t *)&char_ccc_uuid, ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE, 2, 2, ccc_ctl}},
};

static uint16_t s_handles[IDX_NB];
static esp_gatt_if_t s_gatts_if = ESP_GATT_IF_NONE;
static volatile uint16_t s_conn_id = 0xffff;
static volatile uint16_t s_mtu = 23;
static volatile bool s_tx_subscribed = false;
static volatile bool s_ctl_subscribed = false;
static volatile bool s_congested = false;

static esp_ble_adv_data_t adv_data = {
    .set_scan_rsp = false,
    .include_name = false,
    .include_txpower = false,
    .min_interval = 0x0006,
    .max_interval = 0x0010,
    .service_uuid_len = sizeof svc_uuid,
    .p_service_uuid = (uint8_t *)svc_uuid,
    .flag = ESP_BLE_ADV_FLAG_GEN_DISC,   /* BR/EDR is supported: this is a dual-mode device */
};
static esp_ble_adv_data_t scan_rsp_data = {
    .set_scan_rsp = true,
    .include_name = true,
};
static esp_ble_adv_params_t adv_params = {
    .adv_int_min = 0x20,
    .adv_int_max = 0x40,
    .adv_type = ADV_TYPE_IND,
    .own_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .channel_map = ADV_CHNL_ALL,
    .adv_filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
};
static uint8_t s_adv_config_pending = 0;
static volatile bool s_advertising = false;

/* Advertising sometimes silently fails to start at boot (races with Classic scan-mode setup), so a
 * watchdog restarts it whenever we are neither connected nor advertising. */
/* BLE advertising takes radio time from the Classic link; off by default while SPP is the data path.
 * Console `adv 1` turns it on (needed for BLE/iOS discovery). */
static volatile bool s_adv_enabled = true;
void ble_gatt_set_advertising(bool on)
{
    s_adv_enabled = on;
    if (!on && s_advertising) esp_ble_gap_stop_advertising();
}

static void adv_watchdog(void *arg)
{
    if (s_adv_enabled && s_conn_id == 0xffff && !s_advertising && s_adv_config_pending == 0) {
        esp_ble_gap_start_advertising(&adv_params);
    }
}

static void gap_cb(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param)
{
    switch (event) {
    case ESP_GAP_BLE_ADV_DATA_SET_COMPLETE_EVT:
    case ESP_GAP_BLE_SCAN_RSP_DATA_SET_COMPLETE_EVT:
        ESP_LOGI(TAG, "adv/scan-rsp data set (event %d, status %d), pending %d", event, param->adv_data_cmpl.status, s_adv_config_pending - 1);
        if (--s_adv_config_pending == 0 && s_adv_enabled) {
            esp_err_t e = esp_ble_gap_start_advertising(&adv_params);
            if (e) ESP_LOGE(TAG, "start advertising: %s", esp_err_to_name(e));
        }
        break;
    case ESP_GAP_BLE_ADV_START_COMPLETE_EVT:
        s_advertising = param->adv_start_cmpl.status == ESP_BT_STATUS_SUCCESS;
        ESP_LOGI(TAG, "advertising %s", s_advertising ? "started" : "FAILED");
        break;
    case ESP_GAP_BLE_ADV_STOP_COMPLETE_EVT:
        s_advertising = false;
        break;
    case ESP_GAP_BLE_UPDATE_CONN_PARAMS_EVT:
        ESP_LOGI(TAG, "conn params: interval %d x1.25ms, latency %d, timeout %d",
                 param->update_conn_params.conn_int, param->update_conn_params.latency, param->update_conn_params.timeout);
        break;
    default:
        ESP_LOGI(TAG, "gap event %d", event);
        break;
    }
}

static bool notify(uint16_t handle, bool subscribed, const uint8_t *data, size_t len)
{
    if (s_conn_id == 0xffff || !subscribed || s_congested || len > (size_t)(s_mtu - 3)) {
        return false;
    }
    return esp_ble_gatts_send_indicate(s_gatts_if, s_conn_id, handle, len, (uint8_t *)data, false) == ESP_OK;
}

bool ble_gatt_notify_audio(const uint8_t *data, size_t len) { return notify(s_handles[IDX_TX_VAL], s_tx_subscribed, data, len); }

bool ble_gatt_notify_control(const uint8_t *data, size_t len)
{
    if (len >= 2 && data[0] == 0x01) {
        ctl_val[1] = data[1];
        esp_ble_gatts_set_attr_value(s_handles[IDX_CTL_VAL], 2, ctl_val);
    }
    return notify(s_handles[IDX_CTL_VAL], s_ctl_subscribed, data, len);
}

static void gatts_cb(esp_gatts_cb_event_t event, esp_gatt_if_t gatts_if, esp_ble_gatts_cb_param_t *param)
{
    switch (event) {
    case ESP_GATTS_REG_EVT: {
        s_gatts_if = gatts_if;
        ESP_LOGI(TAG, "registered, status %d", param->reg.status);
        esp_err_t e1 = esp_ble_gap_set_device_name(DEVICE_NAME);
        s_adv_config_pending = 2;
        esp_err_t e2 = esp_ble_gap_config_adv_data(&adv_data);
        esp_err_t e3 = esp_ble_gap_config_adv_data(&scan_rsp_data);
        esp_err_t e4 = esp_ble_gatts_create_attr_tab(gatt_db, gatts_if, IDX_NB, SVC_INST_ID);
        if (e1 || e2 || e3 || e4) ESP_LOGE(TAG, "setup: name %d adv %d rsp %d tab %d", e1, e2, e3, e4);
        break;
    }
    case ESP_GATTS_CREAT_ATTR_TAB_EVT:
        if (param->add_attr_tab.status != ESP_GATT_OK || param->add_attr_tab.num_handle != IDX_NB) {
            ESP_LOGE(TAG, "attr table failed: status 0x%x handles %d", param->add_attr_tab.status, param->add_attr_tab.num_handle);
            break;
        }
        memcpy(s_handles, param->add_attr_tab.handles, sizeof s_handles);
        ESP_LOGI(TAG, "service handle %d, audio_tx %d, audio_rx %d, control %d",
                 s_handles[IDX_SVC], s_handles[IDX_TX_VAL], s_handles[IDX_RX_VAL], s_handles[IDX_CTL_VAL]);
        esp_ble_gatts_start_service(s_handles[IDX_SVC]);
        break;
    case ESP_GATTS_CONNECT_EVT: {
        s_conn_id = param->connect.conn_id;
        s_advertising = false;   /* the controller stops advertising on connect */
        s_mtu = 23;
        s_tx_subscribed = s_ctl_subscribed = false;
        s_congested = false;
        ESP_LOGI(TAG, "connected, conn_id %d", s_conn_id);
        /* short interval for audio: 7.5-15 ms; the central may override */
        esp_ble_conn_update_params_t cp = { .latency = 0, .min_int = 0x06, .max_int = 0x0C, .timeout = 400 };
        memcpy(cp.bda, param->connect.remote_bda, sizeof(esp_bd_addr_t));
        esp_ble_gap_update_conn_params(&cp);
        break;
    }
    case ESP_GATTS_DISCONNECT_EVT:
        ESP_LOGI(TAG, "disconnected, reason 0x%x", param->disconnect.reason);
        s_conn_id = 0xffff;
        s_tx_subscribed = s_ctl_subscribed = false;
        audio_pipe_ble_state(false);
        if (s_adv_enabled) esp_ble_gap_start_advertising(&adv_params);
        break;
    case ESP_GATTS_MTU_EVT:
        s_mtu = param->mtu.mtu;
        ESP_LOGI(TAG, "MTU %d", s_mtu);
        break;
    case ESP_GATTS_WRITE_EVT: {
        uint16_t h = param->write.handle;
        if (h == s_handles[IDX_RX_VAL]) {
            audio_pipe_rx_frame(param->write.value, param->write.len);
        } else if (h == s_handles[IDX_CTL_VAL]) {
            audio_pipe_control(param->write.value, param->write.len);
        } else if (h == s_handles[IDX_TX_CCC] && param->write.len == 2) {
            s_tx_subscribed = (param->write.value[0] & 1) != 0;
            ESP_LOGI(TAG, "audio_tx notify %s", s_tx_subscribed ? "on" : "off");
            audio_pipe_ble_state(s_tx_subscribed || s_ctl_subscribed);
        } else if (h == s_handles[IDX_CTL_CCC] && param->write.len == 2) {
            s_ctl_subscribed = (param->write.value[0] & 1) != 0;
            ESP_LOGI(TAG, "control notify %s", s_ctl_subscribed ? "on" : "off");
            audio_pipe_ble_state(s_tx_subscribed || s_ctl_subscribed);
        }
        break;
    }
    case ESP_GATTS_CONGEST_EVT:
        s_congested = param->congest.congested;
        break;
    default:
        break;
    }
}

void ble_gatt_init(void)
{
    esp_err_t e1 = esp_ble_gatts_register_callback(gatts_cb);
    esp_err_t e2 = esp_ble_gap_register_callback(gap_cb);
    esp_err_t e3 = esp_ble_gatt_set_local_mtu(LOCAL_MTU);
    esp_err_t e4 = esp_ble_gatts_app_register(APP_ID);
    ESP_LOGI(TAG, "init: gatts_cb %d gap_cb %d mtu %d app_register %d", e1, e2, e3, e4);
    const esp_timer_create_args_t wd = { .callback = adv_watchdog, .name = "adv_wd" };
    esp_timer_handle_t h;
    if (esp_timer_create(&wd, &h) == ESP_OK) esp_timer_start_periodic(h, 3 * 1000 * 1000);
}
