#include "link_policy.h"

#include "esp_log.h"
/* Bluedroid stack internals: not a public ESP-IDF API, but the only way to control sniff from app code. */
#include "common/bt_target.h"
#include "stack/btm_api.h"
#include "stack/hcidefs.h"

static const char *TAG = "LINKPOL";

/* role switch allowed, sniff/hold/park not */
#define POLICY (HCI_ENABLE_MASTER_SLAVE_SWITCH)

/* Our own BTM power-manager client. Any client asking for ACTIVE wins the mode resolution
 * (btm_pm_compare_modes), so BTA's sniff requests resolve to "already active" and no HCI sniff
 * command is sent. Without this, BTA retried a rejected Sniff Mode command every 50 ms during SCO
 * and starved the ACL data path. */
static UINT8 s_pm_id = BTM_PM_SET_ONLY_ID;

static void pm_cb(BD_ADDR bda, tBTM_PM_STATUS status, UINT16 value, UINT8 hci_status)
{
    ESP_LOGI(TAG, "power mode status %d value %d hci %d", status, value, hci_status);
}

void link_policy_init(void)
{
    BTM_SetDefaultLinkPolicy(POLICY);
    tBTM_STATUS st = BTM_PmRegister(BTM_PM_REG_SET, &s_pm_id, pm_cb);
    ESP_LOGI(TAG, "default link policy 0x%04x (no sniff); pm client %d -> %d", POLICY, s_pm_id, st);
}

void link_policy_apply(const uint8_t *bda)
{
    UINT16 settings = POLICY;
    tBTM_STATUS st = BTM_SetLinkPolicy((UINT8 *)bda, &settings);
    tBTM_PM_PWR_MD md = { .mode = BTM_PM_MD_ACTIVE };
    tBTM_STATUS st2 = BTM_SetPowerMode(s_pm_id, (UINT8 *)bda, &md);
    ESP_LOGI(TAG, "link policy 0x%04x + active mode on %02x:%02x:%02x:%02x:%02x:%02x -> %d / %d", POLICY,
             bda[0], bda[1], bda[2], bda[3], bda[4], bda[5], st, st2);
}
