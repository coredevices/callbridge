/*
 * SPDX-FileCopyrightText: 2021 Espressif Systems (Shanghai) CO LTD
 *
 * SPDX-License-Identifier: Unlicense OR CC0-1.0
 */

#ifndef __BT_APP_HF_H__
#define __BT_APP_HF_H__

#include <stdint.h>
#include "esp_hf_client_api.h"


#define BT_HF_TAG               "BT_HF"

/**
 * @brief     callback function for HF client
 */
void bt_app_hf_client_cb(esp_hf_client_cb_event_t event, esp_hf_client_cb_param_t *param);


void bt_app_hf_reconnect_bonded(void);
void bt_app_hf_start_reconnect(void);
/* true once the service-level connection to the phone is up */
bool bt_app_hf_is_connected(void);
/* true while the transport is connecting or up but SLC not yet negotiated */
bool bt_app_hf_is_connecting(void);

#endif /* __BT_APP_HF_H__*/
