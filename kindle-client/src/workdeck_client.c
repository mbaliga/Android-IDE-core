#define _POSIX_C_SOURCE 200809L
#include "protocol.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <zlib.h>

#define DEFAULT_CONFIG "/mnt/us/extensions/workdeck/bin/workdeck.conf"
#define PID_FILE "/tmp/fonebrew-workdeck.pid"
#define FBINK_PRIMARY "/mnt/us/libkh/bin/fbink"
#define MAX_INPUTS 24
#define DEVICE_ID "koa3-workdeck"

struct config {
    char host[254];
    unsigned port;
    uint8_t secret[32];
    int landscape;
};

struct session {
    int fd;
    uint8_t secret[32];
    uint64_t sequence;
    uint64_t last_inbound_sequence;
    pthread_mutex_t write_lock;
    volatile sig_atomic_t running;
    int width;
    int height;
};

static volatile sig_atomic_t keep_running = 1;
static char waveform[16] = "GC16";

static void signal_stop(int value) { (void)value; keep_running = 0; }

static int copy_string(char *destination, size_t capacity, const char *source) {
    size_t length = strlen(source);
    if (length >= capacity) return -1;
    memcpy(destination, source, length + 1);
    return 0;
}

static int write_all_file(int fd, const void *raw, size_t length) {
    const uint8_t *data = raw;
    while (length) {
        ssize_t count = write(fd, data, length);
        if (count < 0) { if (errno == EINTR) continue; return -1; }
        data += count; length -= (size_t)count;
    }
    return 0;
}

static int read_config(const char *path, struct config *config) {
    memset(config, 0, sizeof(*config)); config->port = 48731;
    FILE *file = fopen(path, "r"); if (!file) return -1;
    char line[1024], secret[128] = {0};
    while (fgets(line, sizeof(line), file)) {
        char *end = strpbrk(line, "\r\n"); if (end) *end = '\0';
        if (!strncmp(line, "PHONE_HOST=", 11)) { if (copy_string(config->host, sizeof(config->host), line+11)<0) { fclose(file); return -1; } }
        else if (!strncmp(line, "PHONE_PORT=", 11)) config->port = (unsigned)strtoul(line+11, NULL, 10);
        else if (!strncmp(line, "PAIRING_SECRET=", 15)) { if (copy_string(secret, sizeof(secret), line+15)<0) { fclose(file); return -1; } }
        else if (!strcmp(line, "ORIENTATION=landscape")) config->landscape = 1;
    }
    fclose(file);
    if (!config->host[0] || config->port < 1 || config->port > 65535 || wd_base64_decode_32(secret, config->secret) < 0) return -1;
    struct in_addr numeric; return inet_pton(AF_INET, config->host, &numeric) == 1 ? 0 : -1;
}

static int connect_phone(const struct config *config) {
    int fd = socket(AF_INET, SOCK_STREAM, 0); if (fd < 0) return -1;
    struct sockaddr_in address; memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET; address.sin_port = htons((uint16_t)config->port);
    if (inet_pton(AF_INET, config->host, &address.sin_addr) != 1 || connect(fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        close(fd); return -1;
    }
    int enabled=1; setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &enabled, sizeof(enabled));
    return fd;
}

static int append_utf8(uint8_t *payload, size_t capacity, size_t *offset, const char *text) {
    size_t length = strlen(text); if (length > 65535 || *offset+2+length > capacity) return -1;
    wd_put_u16(payload+*offset, (uint16_t)length); *offset += 2;
    memcpy(payload+*offset, text, length); *offset += length; return 0;
}

static int handshake(struct session *session, int landscape) {
    uint8_t hello[256]; size_t offset=0;
    if (append_utf8(hello,sizeof(hello),&offset,DEVICE_ID)<0) return -1;
    session->width = landscape ? 1680 : 1264; session->height = landscape ? 1264 : 1680;
    wd_put_u32(hello+offset,(uint32_t)session->width); offset+=4;
    wd_put_u32(hello+offset,(uint32_t)session->height); offset+=4;
    wd_put_u32(hello+offset,16); offset+=4;
    hello[offset++]=1; hello[offset++]=0; hello[offset++]=1;
    wd_put_u32(hello+offset,3); offset+=4;
    if (append_utf8(hello,sizeof(hello),&offset,"DU")<0 || append_utf8(hello,sizeof(hello),&offset,"GL16")<0 ||
        append_utf8(hello,sizeof(hello),&offset,"GC16")<0) return -1;
    struct wd_packet packet={WD_HELLO,0,0,(uint32_t)offset,hello};
    if (wd_write_packet(session->fd,&packet)<0) return -1;
    struct wd_packet challenge;
    if (wd_read_packet(session->fd,&challenge)<0) return -1;
    if (challenge.type!=WD_AUTH_CHALLENGE || challenge.length!=32) { wd_packet_free(&challenge); return -1; }
    uint8_t response[32]; wd_auth_response(session->secret,challenge.payload,DEVICE_ID,response); wd_packet_free(&challenge);
    packet=(struct wd_packet){WD_AUTH_RESPONSE,0,0,32,response};
    return wd_write_packet(session->fd,&packet);
}

static int send_signed(struct session *session, uint8_t type, const uint8_t *payload, uint32_t length) {
    struct wd_packet packet={type,0,session->sequence++,length,NULL};
    if (length) { packet.payload=malloc(length); if (!packet.payload) return -1; memcpy(packet.payload,payload,length); }
    if (wd_packet_seal(session->secret,&packet)<0) { wd_packet_free(&packet); return -1; }
    pthread_mutex_lock(&session->write_lock); int result=wd_write_packet(session->fd,&packet); pthread_mutex_unlock(&session->write_lock);
    wd_packet_free(&packet); return result;
}

static int send_text(struct session *session, uint8_t type, const char *text) {
    size_t length=strlen(text); if (length>65535) return -1;
    uint8_t *payload=malloc(length+2); if (!payload) return -1;
    wd_put_u16(payload,(uint16_t)length); memcpy(payload+2,text,length);
    int result=send_signed(session,type,payload,(uint32_t)length+2); free(payload); return result;
}

static int send_touch(struct session *session, float x, float y, uint8_t action) {
    union { float f; uint32_t u; } fx={x}, fy={y}; uint8_t payload[9];
    wd_put_u32(payload,fx.u); wd_put_u32(payload+4,fy.u); payload[8]=action;
    return send_signed(session,WD_TOUCH_POINTER,payload,sizeof(payload));
}

static const char *fbink_path(void) {
    if (access(FBINK_PRIMARY,X_OK)==0) return FBINK_PRIMARY;
    return access("/usr/bin/fbink",X_OK)==0 ? "/usr/bin/fbink" : NULL;
}

static int run_program(char *const argv[]) {
    pid_t child=fork(); if (child<0) return -1;
    if (child==0) { execv(argv[0],argv); _exit(127); }
    int status; while (waitpid(child,&status,0)<0) if (errno!=EINTR) return -1;
    return WIFEXITED(status) && WEXITSTATUS(status)==0 ? 0 : -1;
}

static int refresh_full(void) {
    const char *fbink=fbink_path(); if (!fbink) return -1;
    char *args[]={(char *)fbink,"-W","GC16","-fs",NULL}; return run_program(args);
}

static int render_region(const struct wd_packet *packet) {
    if (packet->length<33) return -1;
    const uint8_t *p=packet->payload;
    uint32_t fw=wd_get_u32(p), fh=wd_get_u32(p+4), left=wd_get_u32(p+8), top=wd_get_u32(p+12);
    uint32_t right=wd_get_u32(p+16), bottom=wd_get_u32(p+20); uint8_t quality=p[24];
    uint32_t compressed=wd_get_u32(p+25);
    if (!fw||!fh||right<=left||bottom<=top||right>fw||bottom>fh||compressed!=packet->length-29) return -1;
    uint64_t area64=(uint64_t)(right-left)*(bottom-top); if (!area64||area64>WD_MAX_PAYLOAD) return -1;
    uLongf area=(uLongf)area64; uint8_t *gray=malloc(area); if (!gray) return -1;
    int z=uncompress(gray,&area,p+29,compressed); if (z!=Z_OK || area!=area64) { free(gray); return -1; }
    char path[96]; snprintf(path,sizeof(path),"/tmp/workdeck-region-%ld.pgm",(long)getpid());
    int fd=open(path,O_CREAT|O_TRUNC|O_WRONLY,0600); if (fd<0) { free(gray); return -1; }
    char header[64]; int header_length=snprintf(header,sizeof(header),"P5\n%u %u\n255\n",right-left,bottom-top);
    int result=write_all_file(fd,header,(size_t)header_length) | write_all_file(fd,gray,(size_t)area); close(fd); free(gray);
    if (result<0) { unlink(path); return -1; }
    const char *fbink=fbink_path(); if (!fbink) { unlink(path); return -1; }
    char specification[256]; snprintf(specification,sizeof(specification),"file=%s,x=%u,y=%u",path,left,top);
    const char *dither=quality==0?"PASSTHROUGH":"ORDERED";
    char *args[]={(char *)fbink,"--image",specification,"-W",waveform,"-D",(char *)dither,"-w",NULL};
    result=run_program(args); unlink(path); return result;
}

static int read_text_field(const uint8_t *payload, uint32_t length, uint32_t *offset, char **text) {
    if (*offset+4>length) return -1;
    uint32_t count=wd_get_u32(payload+*offset);
    *offset+=4;
    if (count>2U*1024U*1024U || *offset+count>length) return -1;
    *text=malloc((size_t)count+1); if (!*text) return -1;
    memcpy(*text,payload+*offset,count); (*text)[count]='\0'; *offset+=count; return 0;
}

static void render_text_line(const char *text, unsigned row, int title) {
    const char *fbink=fbink_path(); if (!fbink) return;
    char row_value[16]; snprintf(row_value,sizeof(row_value),"%u",row);
    if (title) { char *args[]={(char *)fbink,"-m","-S","2","-y",row_value,(char *)text,NULL}; run_program(args); }
    else { char *args[]={(char *)fbink,"-x","1","-y",row_value,(char *)text,NULL}; run_program(args); }
}

static unsigned render_wrapped(const char *text, unsigned row, unsigned limit) {
    const size_t columns=70; const char *cursor=text;
    while (*cursor && row<limit) {
        const char *newline=strchr(cursor,'\n'); size_t available=newline?(size_t)(newline-cursor):strlen(cursor);
        while (available && row<limit) {
            size_t take=available<columns?available:columns;
            if (take==columns) { size_t split=take; while (split>20 && cursor[split]!=' ') --split; if (split>20) take=split; }
            char line[72]; memcpy(line,cursor,take); line[take]='\0'; render_text_line(line,row++,0);
            cursor+=take; available-=take; while (*cursor==' ') ++cursor;
        }
        if (newline) cursor=newline+1; else break;
    }
    return row;
}

static int render_native_document(const struct wd_packet *packet) {
    uint32_t offset=0; char *title=NULL;
    if (read_text_field(packet->payload,packet->length,&offset,&title)<0 || offset+12>packet->length) { free(title); return -1; }
    offset+=8; uint32_t sections=wd_get_u32(packet->payload+offset); offset+=4;
    if (!sections||sections>64) { free(title); return -1; }
    const char *fbink=fbink_path(); if (!fbink) { free(title); return -1; }
    char *clear_args[]={(char *)fbink,"-k",NULL}; run_program(clear_args);
    render_text_line(title,0,1); free(title); unsigned row=3;
    for (uint32_t i=0; i<sections && row<58; ++i) {
        if (offset>=packet->length) return -1;
        ++offset;
        char *heading=NULL,*body=NULL;
        if (read_text_field(packet->payload,packet->length,&offset,&heading)<0 ||
            read_text_field(packet->payload,packet->length,&offset,&body)<0) { free(heading); free(body); return -1; }
        render_text_line(heading,row++,1); row=render_wrapped(body,row,58); if (row<58) ++row;
        free(heading); free(body);
    }
    return refresh_full();
}

static int decode_short_text(const struct wd_packet *packet, char *output, size_t capacity) {
    if (packet->length<2) return -1;
    uint16_t count=wd_get_u16(packet->payload);
    if ((uint32_t)count+2!=packet->length || (size_t)count>=capacity) return -1;
    memcpy(output,packet->payload+2,count); output[count]='\0'; return 0;
}

static int process_packet(struct session *session, struct wd_packet *packet) {
    if (wd_packet_open(session->secret,packet)<0) return -1;
    if (packet->sequence<=session->last_inbound_sequence) return -1;
    session->last_inbound_sequence=packet->sequence;
    switch (packet->type) {
        case WD_FULL_FRAME: case WD_DIRTY_RECTANGLE: return render_region(packet);
        case WD_NATIVE_DOCUMENT: return render_native_document(packet);
        case WD_REFRESH_HINT: {
            char hint[40]; if (decode_short_text(packet,hint,sizeof(hint))<0) return -1;
            if (!strcmp(hint,"FAST_SCROLL")) snprintf(waveform,sizeof(waveform),"DU");
            else if (!strcmp(hint,"TEXT_HIGH_QUALITY")) snprintf(waveform,sizeof(waveform),"GL16");
            else snprintf(waveform,sizeof(waveform),"GC16");
            return !strcmp(hint,"FULL_CLEANUP") ? refresh_full() : 0;
        }
        case WD_PING_RECONNECT: return 0; /* The phone echoes the client's ping. */
        case WD_VIEWPORT_ROTATION:
            if (packet->length!=12) return -1;
            session->width=(int)wd_get_u32(packet->payload);
            session->height=(int)wd_get_u32(packet->payload+4);
            return session->width>0 && session->height>0 ? 0 : -1;
        case WD_SUSPEND_WAKE: return 0;
        default: return 0;
    }
}

struct input_state { int fd; int touch; int x; int y; int max_x; int max_y; int dirty; int transition; };

static void *input_loop(void *raw) {
    struct session *session=raw; struct input_state inputs[MAX_INPUTS]; struct pollfd pollers[MAX_INPUTS]; int count=0;
    for (int index=0; index<MAX_INPUTS; ++index) {
        char path[64]; snprintf(path,sizeof(path),"/dev/input/event%d",index); int fd=open(path,O_RDONLY|O_NONBLOCK);
        if (fd<0) continue;
        inputs[count]=(struct input_state){.fd=fd,.max_x=session->width-1,.max_y=session->height-1,.transition=-1};
        struct input_absinfo x,y;
        if (ioctl(fd,EVIOCGABS(ABS_MT_POSITION_X),&x)==0 && ioctl(fd,EVIOCGABS(ABS_MT_POSITION_Y),&y)==0) {
            inputs[count].max_x=x.maximum>x.minimum?x.maximum-x.minimum:session->width-1;
            inputs[count].max_y=y.maximum>y.minimum?y.maximum-y.minimum:session->height-1;
        }
        pollers[count]=(struct pollfd){.fd=fd,.events=POLLIN}; ++count;
    }
    time_t last_ping=time(NULL);
    while (session->running && keep_running) {
        int ready=poll(pollers,(nfds_t)count,250);
        if (ready<0 && errno!=EINTR) break;
        for (int i=0; i<count; ++i) if (pollers[i].revents&POLLIN) {
            struct input_event event;
            while (read(inputs[i].fd,&event,sizeof(event))==(ssize_t)sizeof(event)) {
                if (event.type==EV_ABS) {
                    if (event.code==ABS_MT_POSITION_X||event.code==ABS_X) { inputs[i].x=event.value; inputs[i].dirty=1; }
                    if (event.code==ABS_MT_POSITION_Y||event.code==ABS_Y) { inputs[i].y=event.value; inputs[i].dirty=1; }
                    if (event.code==ABS_MT_TRACKING_ID) { inputs[i].transition=event.value<0?2:0; inputs[i].touch=event.value>=0; inputs[i].dirty=1; }
                } else if (event.type==EV_KEY) {
                    if (event.code==BTN_TOUCH) { inputs[i].transition=event.value?0:2; inputs[i].touch=event.value!=0; inputs[i].dirty=1; }
                    if (event.value==1 && (event.code==KEY_PAGEUP||event.code==KEY_VOLUMEUP)) send_text(session,WD_CONTROL_ACTION,"page_up");
                    if (event.value==1 && (event.code==KEY_PAGEDOWN||event.code==KEY_VOLUMEDOWN)) send_text(session,WD_CONTROL_ACTION,"page_down");
                    if (event.value==1 && event.code==KEY_POWER) send_text(session,WD_SUSPEND_WAKE,"suspend");
                } else if (event.type==EV_SYN && event.code==SYN_REPORT && inputs[i].dirty) {
                    float x=(float)inputs[i].x/(float)(inputs[i].max_x?inputs[i].max_x:1);
                    float y=(float)inputs[i].y/(float)(inputs[i].max_y?inputs[i].max_y:1);
                    uint8_t action=(uint8_t)(inputs[i].transition==2?2:(inputs[i].transition==0?0:1));
                    if (send_touch(session,x<0?0:x>1?1:x,y<0?0:y>1?1:y,action)<0) session->running=0;
                    inputs[i].transition=inputs[i].touch?1:-1; inputs[i].dirty=0;
                }
            }
        }
        if (time(NULL)-last_ping>=20) { if (send_text(session,WD_PING_RECONNECT,"ping")<0) session->running=0; last_ping=time(NULL); }
    }
    for (int i=0; i<count; ++i) close(inputs[i].fd);
    return NULL;
}

static int run_session(const struct config *config) {
    struct session session; memset(&session,0,sizeof(session)); session.fd=connect_phone(config); if (session.fd<0) return -1;
    memcpy(session.secret,config->secret,32); session.sequence=1; session.running=1; pthread_mutex_init(&session.write_lock,NULL);
    if (handshake(&session,config->landscape)<0) { close(session.fd); pthread_mutex_destroy(&session.write_lock); return -1; }
    send_text(&session,WD_SUSPEND_WAKE,"wake");
    pthread_t input_thread; int threaded=pthread_create(&input_thread,NULL,input_loop,&session)==0;
    while (session.running && keep_running) {
        struct wd_packet packet;
        if (wd_read_packet(session.fd,&packet)<0) break;
        int result=process_packet(&session,&packet); wd_packet_free(&packet);
        if (result<0) break;
    }
    session.running=0; shutdown(session.fd,SHUT_RDWR); if (threaded) pthread_join(input_thread,NULL);
    close(session.fd); pthread_mutex_destroy(&session.write_lock); memset(session.secret,0,32); return 0;
}

static int stop_daemon(void) {
    FILE *file=fopen(PID_FILE,"r");
    if (!file) return 1;
    long pid=0;
    int parsed=fscanf(file,"%ld",&pid);
    fclose(file);
    if (parsed!=1 || pid<=1 || kill((pid_t)pid,SIGTERM)<0) return 1;
    return 0;
}

static int daemonize(void) {
    pid_t child=fork();
    if (child<0) return -1;
    if (child>0) return 1;
    if (setsid()<0) _exit(1);
    child=fork();
    if (child<0) _exit(1);
    if (child>0) _exit(0);
    if (chdir("/")<0) _exit(1);
    int nullfd=open("/dev/null",O_RDWR);
    if (nullfd>=0) { dup2(nullfd,0); dup2(nullfd,1); dup2(nullfd,2); if (nullfd>2) close(nullfd); }
    return 0;
}

int main(int argc, char **argv) {
    const char *config_path=DEFAULT_CONFIG; int background=0;
    for (int i=1; i<argc; ++i) {
        if (!strcmp(argv[i],"--stop")) return stop_daemon();
        if (!strcmp(argv[i],"--daemon")) background=1;
        else if (!strcmp(argv[i],"--config") && i+1<argc) config_path=argv[++i];
        else if (strcmp(argv[i],"--foreground")) { fprintf(stderr,"unknown argument: %s\n",argv[i]); return 2; }
    }
    struct config config; if (read_config(config_path,&config)<0) { fprintf(stderr,"invalid Workdeck config\n"); return 2; }
    if (background) { int result=daemonize(); if (result!=0) return result<0?1:0; }
    FILE *pid=fopen(PID_FILE,"w"); if (!pid) return 1; fprintf(pid,"%ld\n",(long)getpid()); fclose(pid);
    signal(SIGTERM,signal_stop); signal(SIGINT,signal_stop); signal(SIGPIPE,SIG_IGN);
    unsigned delay=1;
    while (keep_running) {
        run_session(&config); if (!keep_running) break;
        sleep(delay); if (delay<30) delay=delay*2>30?30:delay*2;
    }
    unlink(PID_FILE); memset(&config,0,sizeof(config)); return 0;
}
