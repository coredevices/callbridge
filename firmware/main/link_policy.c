#include "link_policy.h"

#include "esp_log.h"
/* Bluedroid stack internals: not a public ESP-IDF API, but the only way to control sniff from app code. */
#include "common/bt_target.h"
#include "stack/btm_api.h"
#include "stack/hcidefs.h"

static const char *TAG = "LINKPOL";

/* role switch allowed, sniff/hold/park not */
#define POLICY (HCI_ENABLE_MASTER_SLAVE_SWITCH)

void link_policy_init(void)
{
    BTM_SetDefaultLinkPolicy(POLICY);
    ESP_LOGI(TAG, "default link policy 0x%04x (no sniff)", POLICY);
}

void link_policy_apply(const uint8_t *bda)
{
    UINT16 settings = POLICY;
    tBTM_STATUS st = BTM_SetLinkPolicy((UINT8 *)bda, &settings);
    ESP_LOGI(TAG, "link policy 0x%04x on %02x:%02x:%02x:%02x:%02x:%02x -> %d", POLICY,
             bda[0], bda[1], bda[2], bda[3], bda[4], bda[5], st);
}
