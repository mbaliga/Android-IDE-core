/* Compact SHA-256/HMAC implementation dedicated to Workdeck protocol authentication. */
#include "sha256.h"

#include <string.h>

#define ROTR(x, n) (((x) >> (n)) | ((x) << (32U - (n))))

static const uint32_t k[64] = {
    0x428a2f98U,0x71374491U,0xb5c0fbcfU,0xe9b5dba5U,0x3956c25bU,0x59f111f1U,0x923f82a4U,0xab1c5ed5U,
    0xd807aa98U,0x12835b01U,0x243185beU,0x550c7dc3U,0x72be5d74U,0x80deb1feU,0x9bdc06a7U,0xc19bf174U,
    0xe49b69c1U,0xefbe4786U,0x0fc19dc6U,0x240ca1ccU,0x2de92c6fU,0x4a7484aaU,0x5cb0a9dcU,0x76f988daU,
    0x983e5152U,0xa831c66dU,0xb00327c8U,0xbf597fc7U,0xc6e00bf3U,0xd5a79147U,0x06ca6351U,0x14292967U,
    0x27b70a85U,0x2e1b2138U,0x4d2c6dfcU,0x53380d13U,0x650a7354U,0x766a0abbU,0x81c2c92eU,0x92722c85U,
    0xa2bfe8a1U,0xa81a664bU,0xc24b8b70U,0xc76c51a3U,0xd192e819U,0xd6990624U,0xf40e3585U,0x106aa070U,
    0x19a4c116U,0x1e376c08U,0x2748774cU,0x34b0bcb5U,0x391c0cb3U,0x4ed8aa4aU,0x5b9cca4fU,0x682e6ff3U,
    0x748f82eeU,0x78a5636fU,0x84c87814U,0x8cc70208U,0x90befffaU,0xa4506cebU,0xbef9a3f7U,0xc67178f2U,
};

static uint32_t load_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}

static void store_be32(uint8_t *p, uint32_t value) {
    p[0] = (uint8_t)(value >> 24); p[1] = (uint8_t)(value >> 16);
    p[2] = (uint8_t)(value >> 8); p[3] = (uint8_t)value;
}

static void transform(wd_sha256_ctx *ctx, const uint8_t block[64]) {
    uint32_t w[64];
    for (size_t i = 0; i < 16; ++i) w[i] = load_be32(block + i * 4);
    for (size_t i = 16; i < 64; ++i) {
        uint32_t s0 = ROTR(w[i - 15], 7) ^ ROTR(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = ROTR(w[i - 2], 17) ^ ROTR(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a=ctx->state[0], b=ctx->state[1], c=ctx->state[2], d=ctx->state[3];
    uint32_t e=ctx->state[4], f=ctx->state[5], g=ctx->state[6], h=ctx->state[7];
    for (size_t i = 0; i < 64; ++i) {
        uint32_t s1 = ROTR(e, 6) ^ ROTR(e, 11) ^ ROTR(e, 25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t t1 = h + s1 + ch + k[i] + w[i];
        uint32_t s0 = ROTR(a, 2) ^ ROTR(a, 13) ^ ROTR(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t t2 = s0 + maj;
        h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
    }
    ctx->state[0]+=a; ctx->state[1]+=b; ctx->state[2]+=c; ctx->state[3]+=d;
    ctx->state[4]+=e; ctx->state[5]+=f; ctx->state[6]+=g; ctx->state[7]+=h;
}

void wd_sha256_init(wd_sha256_ctx *ctx) {
    static const uint32_t initial[8] = {
        0x6a09e667U,0xbb67ae85U,0x3c6ef372U,0xa54ff53aU,
        0x510e527fU,0x9b05688cU,0x1f83d9abU,0x5be0cd19U,
    };
    memcpy(ctx->state, initial, sizeof(initial));
    ctx->bit_count = 0; ctx->block_used = 0;
}

void wd_sha256_update(wd_sha256_ctx *ctx, const void *raw, size_t length) {
    const uint8_t *data = raw;
    ctx->bit_count += (uint64_t)length * 8U;
    while (length) {
        size_t take = 64U - ctx->block_used;
        if (take > length) take = length;
        memcpy(ctx->block + ctx->block_used, data, take);
        ctx->block_used += take; data += take; length -= take;
        if (ctx->block_used == 64U) { transform(ctx, ctx->block); ctx->block_used = 0; }
    }
}

void wd_sha256_final(wd_sha256_ctx *ctx, uint8_t digest[32]) {
    ctx->block[ctx->block_used++] = 0x80;
    if (ctx->block_used > 56U) {
        memset(ctx->block + ctx->block_used, 0, 64U - ctx->block_used);
        transform(ctx, ctx->block); ctx->block_used = 0;
    }
    memset(ctx->block + ctx->block_used, 0, 56U - ctx->block_used);
    for (size_t i = 0; i < 8; ++i) ctx->block[63U - i] = (uint8_t)(ctx->bit_count >> (i * 8));
    transform(ctx, ctx->block);
    for (size_t i = 0; i < 8; ++i) store_be32(digest + i * 4, ctx->state[i]);
    memset(ctx, 0, sizeof(*ctx));
}

void wd_hmac_sha256(const uint8_t *key, size_t key_length,
                    const uint8_t *data, size_t data_length,
                    uint8_t digest[32]) {
    uint8_t normalized[64] = {0}, inner[32], ipad[64], opad[64];
    if (key_length > 64U) {
        wd_sha256_ctx hash; wd_sha256_init(&hash); wd_sha256_update(&hash, key, key_length);
        wd_sha256_final(&hash, normalized);
    } else memcpy(normalized, key, key_length);
    for (size_t i = 0; i < 64; ++i) { ipad[i] = normalized[i] ^ 0x36U; opad[i] = normalized[i] ^ 0x5cU; }
    wd_sha256_ctx hash;
    wd_sha256_init(&hash); wd_sha256_update(&hash, ipad, 64); wd_sha256_update(&hash, data, data_length); wd_sha256_final(&hash, inner);
    wd_sha256_init(&hash); wd_sha256_update(&hash, opad, 64); wd_sha256_update(&hash, inner, 32); wd_sha256_final(&hash, digest);
    memset(normalized, 0, sizeof(normalized)); memset(inner, 0, sizeof(inner));
}
