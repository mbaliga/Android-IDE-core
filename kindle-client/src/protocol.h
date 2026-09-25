#ifndef WORKDECK_PROTOCOL_H
#define WORKDECK_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>

#define WD_VERSION 1U
#define WD_MAX_PAYLOAD (8U * 1024U * 1024U)
#define WD_AUTH_FLAG 1U

enum wd_message_type {
    WD_HELLO=1, WD_FULL_FRAME=2, WD_DIRTY_RECTANGLE=3, WD_REFRESH_HINT=4,
    WD_TOUCH_POINTER=5, WD_KEYBOARD=6, WD_CLIPBOARD_TEXT=7, WD_VIEWPORT_ROTATION=8,
    WD_PING_RECONNECT=9, WD_SUSPEND_WAKE=10, WD_NATIVE_DOCUMENT=11,
    WD_CONTROL_ACTION=12, WD_AUTH_CHALLENGE=13, WD_AUTH_RESPONSE=14, WD_ERROR=15,
};

struct wd_packet {
    uint8_t type;
    uint16_t flags;
    uint64_t sequence;
    uint32_t length;
    uint8_t *payload;
};

void wd_packet_free(struct wd_packet *packet);
int wd_read_packet(int fd, struct wd_packet *packet);
int wd_write_packet(int fd, const struct wd_packet *packet);
int wd_packet_seal(const uint8_t secret[32], struct wd_packet *packet);
int wd_packet_open(const uint8_t secret[32], struct wd_packet *packet);
void wd_auth_response(const uint8_t secret[32], const uint8_t nonce[32],
                      const char *device_id, uint8_t response[32]);
int wd_base64_decode_32(const char *encoded, uint8_t output[32]);
uint16_t wd_get_u16(const uint8_t *p);
uint32_t wd_get_u32(const uint8_t *p);
uint64_t wd_get_u64(const uint8_t *p);
void wd_put_u16(uint8_t *p, uint16_t value);
void wd_put_u32(uint8_t *p, uint32_t value);
void wd_put_u64(uint8_t *p, uint64_t value);

#endif
