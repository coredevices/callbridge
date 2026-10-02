#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

/*
 * Bluetooth Classic SPP (RFCOMM) data link to the phone. Same piconet as the HFP/SCO link, so it
 * does not fight BLE for the radio. Stream framing: [channel u8][len u16 LE][payload], channel
 * 0 = audio frame, 1 = control message (payloads as in docs/protocol.md).
 */
#define SPP_CH_AUDIO   0
#define SPP_CH_CONTROL 1

void spp_link_init(void);
bool spp_link_is_up(void);
uint32_t spp_link_write_failures(void);
/* false if the link is down or congested (caller counts it as a drop) */
bool spp_link_send(uint8_t channel, const uint8_t *data, size_t len);
/* writes already-framed bytes (one or more [channel][len][payload] records) */
bool spp_link_send_raw(const uint8_t *framed, size_t len);
