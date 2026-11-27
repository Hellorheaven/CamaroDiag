#define _POSIX_C_SOURCE 200809L
#include "j1979_can.h"
#include "j1979_isotp.h"
#include "j1979_live.h"

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <array>

static volatile sig_atomic_t cancel_requested;
static void cancel_handler(int signal_number) {
    (void)signal_number;
    cancel_requested = 1;
}
typedef struct {
    const char *interface_name, *output, *vin;
    uint32_t tx, rx;
    unsigned budget_ms, cycles, interval_ms, pid_count;
    uint8_t pids[J1979_LIVE_MAX_PIDS];
    bool live, confirmed, tx_set, rx_set, cycles_set, interval_set;
} options;
static bool parse_hex(const char *s, uint32_t *out) {
    if (!s || !*s || *s == '-' || *s == '+') return false;
    if (s[0]=='0' && (s[1]=='x' || s[1]=='X')) s += 2;
    if (!*s) return false;
    for (const char *p = s; *p; ++p)
        if (!((*p>='0'&&*p<='9') || (*p>='A'&&*p<='F') ||
              (*p>='a'&&*p<='f'))) return false;
    errno=0;char *tail=NULL;
    unsigned long n=strtoul(s,&tail,16);
    if (errno || *tail || n>UINT32_MAX) return false;
    *out=(uint32_t)n;return true;
}
static bool parse_milliseconds(const char *s, unsigned *out) {
    if (!s || !*s) return false;
    for (const char *p=s;*p;++p) if (*p<'0'||*p>'9') return false;
    errno=0;char *tail=NULL;
    unsigned long n=strtoul(s,&tail,10);
    if (errno||*tail||n<1||n>600000) return false;
    *out=(unsigned)n;return true;
}
static bool parse(int argc, char **argv, options *o) {
    *o={};o->budget_ms=180000;
    o->live=!strcmp(argv[1],"--j1979-live");
    o->cycles=30;o->interval_ms=1000;
    bool has_budget=false;
    for (int i=2;i<argc;++i) {
        const char *flag=argv[i];
        if (!strcmp(flag,"--confirm-physical-channel")) {
            if (o->confirmed) return false;
            o->confirmed=true;
            continue;
        }
        if (i+1>=argc) return false;
        const char *value=argv[++i];
        if (!value || !*value) return false;
        if (!strcmp(flag,"--interface")) {
            if (o->interface_name) return false;
            o->interface_name=value;
        } else if (!strcmp(flag,"--tx")) {
            if (o->tx_set||!parse_hex(value,&o->tx)) return false;
            o->tx_set=true;
        } else if (!strcmp(flag,"--rx")) {
            if (o->rx_set||!parse_hex(value,&o->rx)) return false;
            o->rx_set=true;
        } else if (!strcmp(flag,"--output")) {
            if (o->output) return false;
            o->output=value;
        } else if (!strcmp(flag,"--vin")) {
            if (o->vin||!j1979_can_valid_vin(value)) return false;
            o->vin=value;
        } else if (!strcmp(flag,"--budget-ms")) {
            if (has_budget||!parse_milliseconds(value,&o->budget_ms)) return false;
            has_budget=true;
        } else if (!strcmp(flag,"--pid") && o->live) {
            uint32_t pid=0;
            if (o->pid_count==J1979_LIVE_MAX_PIDS ||
                !parse_hex(value,&pid) || !pid || pid>255 || pid%32u==0) return false;
            for (unsigned j=0;j<o->pid_count;++j)
                if (o->pids[j]==pid) return false;
            o->pids[o->pid_count++]=(uint8_t)pid;
        } else if (!strcmp(flag,"--cycles") && o->live) {
            if (o->cycles_set || !parse_milliseconds(value,&o->cycles) ||
                o->cycles>1000) return false;
            o->cycles_set=true;
        } else if (!strcmp(flag,"--interval-ms") && o->live) {
            if (o->interval_set || !parse_milliseconds(value,&o->interval_ms) ||
                o->interval_ms<100 || o->interval_ms>60000) return false;
            o->interval_set=true;
        } else return false;
    }
    return o->confirmed && o->tx_set && o->rx_set && o->output &&
        o->vin && (!o->live || (o->pid_count &&
        o->pid_count * o->cycles <= J1979_LIVE_MAX_SAMPLES)) &&
        j1979_isotp_valid_interface(o->interface_name) &&
        j1979_can_physical_pair(o->tx,o->rx);
}
int j1979_can_cli(int argc, char **argv) {
    options o{};
    if (!parse(argc,argv,&o)) {
        fprintf(stderr,"Native J1979: explicit --interface, --tx, --rx, "
            "--vin, --output and --confirm-physical-channel required; "
            "live additionally requires --pid\n");
        return 2;
    }
    if (!isatty(STDIN_FILENO)) {
        fprintf(stderr,"Native J1979: interactive local physical operator approval required\n");
        return 2;
    }
    fprintf(stderr,
        "SAE J1979 read-only, selected ECU, vehicle stationary, "
        "correct interface and protocol? Type yes: ");
    char confirmation[16]={0};
    if (!fgets(confirmation,sizeof(confirmation),stdin) ||
        strcmp(confirmation,"yes\n")) {
        fprintf(stderr,"Native J1979: authorization not granted\n");
        return 2;
    }
    /* Reserve output before opening any ECU-capable transport. Preserve
     * evidence if the physical link fails or the user cancels mid-session. */
    int fd=open(o.output,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if (fd<0) {
        fprintf(stderr,"Native J1979: output already exists or is not safe\n");
        return 2;
    }
    FILE *stream=fdopen(fd,"w");
    if (!stream) {close(fd);unlink(o.output);return 2;}
    cancel_requested=0;
    struct sigaction handler{},old_int{},old_term{};
    handler.sa_handler=cancel_handler;
    sigemptyset(&handler.sa_mask);
    bool restored_handlers=false;
    if (sigaction(SIGINT,&handler,&old_int)==0) {
        if (sigaction(SIGTERM,&handler,&old_term)==0) restored_handlers=true;
        else sigaction(SIGINT,&old_int,NULL);
    }
    j1979_isotp_link link{};
    link.socket_fd=-1;
    bool opened=restored_handlers &&
        j1979_isotp_open(&link,o.interface_name,o.tx,o.rx,&cancel_requested);
    j1979_can_plan plan{};
    plan.exchange=j1979_isotp_exchange;
    plan.clock=nullptr; /* CLOCK_MONOTONIC in the native C++ campaign engine */
    plan.current=j1979_isotp_current;
    plan.context=&link;
    plan.tx=o.tx;plan.rx=o.rx;
    plan.budget_ms=o.budget_ms;
    plan.expected_vin=o.vin;
    j1979_can_result result{};
    j1979_live_result live_result{};
    j1979_live_plan live_plan{};
    live_plan.channel=plan;
    live_plan.pid_count=o.pid_count;
    live_plan.cycles=o.cycles;
    live_plan.interval_ms=o.interval_ms;
    std::copy_n(o.pids,o.pid_count,live_plan.pids);
    int scan_status=opened ?
        (o.live ? j1979_live_record(&live_plan,&live_result) :
                  j1979_can_scan(&plan,&result)) : -1;
    if (!opened) fprintf(stderr,"Native J1979: selected SocketCAN ISO-TP unavailable\n");
    if (cancel_requested) {
        if (o.live) {
            live_result.completed=false;
            snprintf(live_result.stop_reason,sizeof(live_result.stop_reason),"CANCELLED");
        } else {
            result.cancelled=true;
            result.completed=false;
            snprintf(result.stop_reason,sizeof(result.stop_reason),"CANCELLED");
        }
    } else if (scan_status || !opened) {
        if (o.live) {
            live_result.completed=false;
            snprintf(live_result.stop_reason,sizeof(live_result.stop_reason),"%s",
                     !opened ? "TRANSPORT_UNAVAILABLE":"CAMPAIGN_FAILED");
        } else {
            result.completed=false;
            snprintf(result.stop_reason,sizeof(result.stop_reason),"%s",
                     !opened ? "TRANSPORT_UNAVAILABLE":"CAMPAIGN_FAILED");
        }
    }
    j1979_isotp_close(&link);
    if (restored_handlers) {
        sigaction(SIGINT,&old_int,NULL);
        sigaction(SIGTERM,&old_term,NULL);
    }
    bool saved=false;
    if (o.live && live_result.steps) {
        saved=j1979_live_write_json(stream,&live_plan,&live_result,o.interface_name);
    } else if (!o.live && result.steps) {
        saved=j1979_can_write_json(stream,&plan,&result,o.interface_name);
    } else {
        saved=fputs(o.live ?
            "{\"schema\":\"camarodiag-j1979-live-linux-c-v1\","
            "\"protocol\":\"SAE-J1979\",\"completed\":false,"
            "\"physical_vehicle_verified\":false,"
            "\"permits_programming\":false,"
            "\"stop_reason\":\"TRANSPORT_UNAVAILABLE\",\"steps\":[]}\n":
            "{\"schema\":\"camarodiag-functional-j1979-can-linux-c-v1\","
            "\"protocol\":\"SAE-J1979\",\"completed\":false,"
            "\"physical_vehicle_verified\":false,"
            "\"permits_programming\":false,"
            "\"stop_reason\":\"TRANSPORT_UNAVAILABLE\",\"steps\":[]}\n",stream)!=EOF;
    }
    if (saved) saved=fflush(stream)==0 && fsync(fd)==0;
    if (fclose(stream)!=0) saved=false;
    int status=opened && scan_status==0 && saved &&
        (o.live ? live_result.completed : result.completed) ? 0:2;
    if (!saved) fprintf(stderr,"Native J1979: output was not persisted completely\n");
    j1979_can_result_free(&result);
    j1979_live_free(&live_result);
    return status;
}
