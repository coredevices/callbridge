#pragma once
#include <stdint.h>

/*
 * Keep the BR/EDR ACL link to the phone in active mode. Both ends' Bluedroid power managers try to
 * put an idle HFP link into sniff mode (phone: 495 ms interval); in sniff, ACL data (our SPP/BLE
 * audio frames) only moves at anchor points and throughput collapses to ~10 frames/s. Removing
 * sniff from our link policy makes the controller reject sniff requests from either side.
 */
void link_policy_init(void);
void link_policy_apply(const uint8_t *bda);
void link_policy_print_window(void);
