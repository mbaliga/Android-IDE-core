#include "protocol.h"
#include "sha256.h"

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static void hex(const uint8_t *bytes, size_t count, char *output) {
    static const char digits[]="0123456789abcdef";
    for (size_t i=0;i<count;++i) { output[i*2]=digits[bytes[i]>>4]; output[i*2+1]=digits[bytes[i]&15]; }
    output[count*2]='\0';
}

int main(void) {
    uint8_t key[20]; memset(key,0x0b,sizeof(key)); uint8_t digest[32]; char encoded[65];
    wd_hmac_sha256(key,sizeof(key),(const uint8_t *)"Hi There",8,digest); hex(digest,32,encoded);
    assert(!strcmp(encoded,"b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"));

    uint8_t secret[32]; assert(wd_base64_decode_32("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",secret)==0);
    for (unsigned i=0;i<32;++i) assert(secret[i]==i);

    struct wd_packet packet={WD_CLIPBOARD_TEXT,0,77,5,NULL};
    packet.payload=malloc(5); assert(packet.payload); memcpy(packet.payload,"hello",5); assert(wd_packet_seal(secret,&packet)==0);
    int sockets[2]; assert(socketpair(AF_UNIX,SOCK_STREAM,0,sockets)==0); assert(wd_write_packet(sockets[0],&packet)==0);
    struct wd_packet received; assert(wd_read_packet(sockets[1],&received)==0); assert(wd_packet_open(secret,&received)==0);
    assert(received.type==WD_CLIPBOARD_TEXT && received.sequence==77 && received.length==5 && !memcmp(received.payload,"hello",5));
    wd_packet_free(&packet); wd_packet_free(&received); close(sockets[0]); close(sockets[1]);
    puts("workdeck protocol tests passed"); return 0;
}
