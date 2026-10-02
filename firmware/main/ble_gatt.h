#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

/* BLE GATT peripheral: callbridge service with audio_tx (notify), audio_rx (write w/o rsp),
 * control (read/write/notify). UUIDs in docs/protocol.md. Advertises as "callbridge". */
void ble_gatt_init(void);
void ble_gatt_set_advertising(bool on);
/* request a fixed connection interval (1.25 ms units, 6..40) now and on future connections */
void ble_gatt_set_conn_interval(uint16_t units);

/* Returns false if not connected, not subscribed, congested, or the frame exceeds MTU-3. */
bool ble_gatt_notify_audio(const uint8_t *data, size_t len);
bool ble_gatt_notify_control(const uint8_t *data, size_t len);
