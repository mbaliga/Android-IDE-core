#ifndef WORKDECK_SHA256_H
#define WORKDECK_SHA256_H

#include <stddef.h>
#include <stdint.h>

typedef struct {
    uint32_t state[8];
    uint64_t bit_count;
    uint8_t block[64];
    size_t block_used;
} wd_sha256_ctx;

void wd_sha256_init(wd_sha256_ctx *ctx);
void wd_sha256_update(wd_sha256_ctx *ctx, const void *data, size_t length);
void wd_sha256_final(wd_sha256_ctx *ctx, uint8_t digest[32]);
void wd_hmac_sha256(const uint8_t *key, size_t key_length,
                    const uint8_t *data, size_t data_length,
                    uint8_t digest[32]);

#endif
