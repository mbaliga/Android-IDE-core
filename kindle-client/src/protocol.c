#include "protocol.h"
#include "sha256.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define WD_MAGIC 0x57444b31U

uint16_t wd_get_u16(const uint8_t *p) { return (uint16_t)(((uint16_t)p[0] << 8) | p[1]); }
uint32_t wd_get_u32(const uint8_t *p) { return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3]; }
uint64_t wd_get_u64(const uint8_t *p) { return ((uint64_t)wd_get_u32(p)<<32)|wd_get_u32(p+4); }
void wd_put_u16(uint8_t *p, uint16_t v) { p[0]=(uint8_t)(v>>8); p[1]=(uint8_t)v; }
void wd_put_u32(uint8_t *p, uint32_t v) { p[0]=(uint8_t)(v>>24); p[1]=(uint8_t)(v>>16); p[2]=(uint8_t)(v>>8); p[3]=(uint8_t)v; }
void wd_put_u64(uint8_t *p, uint64_t v) { wd_put_u32(p,(uint32_t)(v>>32)); wd_put_u32(p+4,(uint32_t)v); }

static int read_all(int fd, void *raw, size_t length) {
    uint8_t *p = raw;
    while (length) {
        ssize_t n = read(fd, p, length);
        if (n == 0) return -1;
        if (n < 0) { if (errno == EINTR) continue; return -1; }
        p += n; length -= (size_t)n;
    }
    return 0;
}

static int write_all(int fd, const void *raw, size_t length) {
    const uint8_t *p = raw;
    while (length) {
        ssize_t n = write(fd, p, length);
        if (n < 0) { if (errno == EINTR) continue; return -1; }
        p += n; length -= (size_t)n;
    }
    return 0;
}

void wd_packet_free(struct wd_packet *packet) {
    free(packet->payload);
    memset(packet, 0, sizeof(*packet));
}

int wd_read_packet(int fd, struct wd_packet *packet) {
    uint8_t header[20];
    memset(packet, 0, sizeof(*packet));
    if (read_all(fd, header, sizeof(header)) < 0) return -1;
    if (wd_get_u32(header) != WD_MAGIC || header[4] != WD_VERSION) return -1;
    packet->type = header[5]; packet->flags = wd_get_u16(header+6);
    packet->sequence = wd_get_u64(header+8); packet->length = wd_get_u32(header+16);
    if (packet->type < WD_HELLO || packet->type > WD_ERROR || packet->length > WD_MAX_PAYLOAD) return -1;
    if (packet->length) {
        packet->payload = malloc(packet->length);
        if (!packet->payload || read_all(fd, packet->payload, packet->length) < 0) { wd_packet_free(packet); return -1; }
    }
    return 0;
}

int wd_write_packet(int fd, const struct wd_packet *packet) {
    uint8_t header[20];
    if (packet->length > WD_MAX_PAYLOAD || (packet->length && !packet->payload)) return -1;
    wd_put_u32(header, WD_MAGIC); header[4]=WD_VERSION; header[5]=packet->type;
    wd_put_u16(header+6, packet->flags); wd_put_u64(header+8, packet->sequence); wd_put_u32(header+16, packet->length);
    return write_all(fd, header, sizeof(header)) < 0 ||
        (packet->length && write_all(fd, packet->payload, packet->length) < 0) ? -1 : 0;
}

static int packet_mac(const uint8_t secret[32], const struct wd_packet *packet,
                      const uint8_t *payload, uint32_t length, uint8_t digest[32]) {
    size_t count = 10U + length;
    uint8_t *data = malloc(count);
    if (!data) return -1;
    data[0]=WD_VERSION; data[1]=packet->type; wd_put_u64(data+2, packet->sequence);
    if (length) memcpy(data+10, payload, length);
    wd_hmac_sha256(secret, 32, data, count, digest);
    memset(data, 0, count); free(data);
    return 0;
}

static int constant_equal(const uint8_t *a, const uint8_t *b, size_t length) {
    uint8_t difference = 0;
    for (size_t i=0; i<length; ++i) difference |= a[i]^b[i];
    return difference == 0;
}

int wd_packet_seal(const uint8_t secret[32], struct wd_packet *packet) {
    if (packet->flags & WD_AUTH_FLAG || packet->length > WD_MAX_PAYLOAD-32U) return -1;
    uint8_t *signed_payload = malloc(packet->length+32U), digest[32];
    if (!signed_payload) return -1;
    if (packet_mac(secret, packet, packet->payload, packet->length, digest)<0) { free(signed_payload); return -1; }
    memcpy(signed_payload, digest, 32); if (packet->length) memcpy(signed_payload+32, packet->payload, packet->length);
    free(packet->payload); packet->payload=signed_payload; packet->length+=32U; packet->flags|=WD_AUTH_FLAG;
    return 0;
}

int wd_packet_open(const uint8_t secret[32], struct wd_packet *packet) {
    if (!(packet->flags & WD_AUTH_FLAG) || packet->length < 32U) return -1;
    uint32_t length = packet->length-32U; uint8_t digest[32];
    if (packet_mac(secret, packet, packet->payload+32, length, digest)<0) return -1;
    if (!constant_equal(digest, packet->payload, 32)) return -1;
    memmove(packet->payload, packet->payload+32, length);
    packet->length=length; packet->flags &= (uint16_t)~WD_AUTH_FLAG;
    return 0;
}

void wd_auth_response(const uint8_t secret[32], const uint8_t nonce[32], const char *device_id, uint8_t response[32]) {
    size_t id_length = strlen(device_id), count = 33U + id_length;
    uint8_t *data = malloc(count);
    if (!data) { memset(response, 0, 32); return; }
    memcpy(data, nonce, 32); memcpy(data+32, device_id, id_length); data[count-1]='1';
    wd_hmac_sha256(secret, 32, data, count, response);
    memset(data, 0, count); free(data);
}

int wd_base64_decode_32(const char *encoded, uint8_t output[32]) {
    static const char alphabet[]="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    uint32_t bits=0; int bit_count=0; size_t produced=0;
    for (const char *p=encoded; *p && *p!='='; ++p) {
        const char *found=strchr(alphabet,*p); if (!found) return -1;
        bits=(bits<<6)|(uint32_t)(found-alphabet); bit_count+=6;
        if (bit_count>=8) { bit_count-=8; if (produced>=32) return -1; output[produced++]=(uint8_t)(bits>>bit_count); }
    }
    return produced==32 ? 0 : -1;
}
