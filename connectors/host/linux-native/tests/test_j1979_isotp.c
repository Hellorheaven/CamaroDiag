#include "j1979_isotp.h"
#include "j1979_can.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

/* Never opens a real CAN interface, even when vcan is installed. */
int main(void) {
    assert(j1979_isotp_valid_interface("can0"));
    assert(j1979_isotp_valid_interface("vcan2"));
    assert(j1979_isotp_valid_interface("can_if.2"));
    assert(!j1979_isotp_valid_interface(NULL));
    assert(!j1979_isotp_valid_interface(""));
    assert(!j1979_isotp_valid_interface("../can0/path"));
    assert(!j1979_isotp_valid_interface("can\n0"));
    assert(!j1979_isotp_valid_interface("1234567890123456"));
    j1979_isotp_link link={.socket_fd=-1};
    /* Function must reject an unapproved physical pair before socket(). */
    assert(!j1979_isotp_open(&link,"can0",0x7df,0x7e8,NULL));
    assert(link.socket_fd==-1);
    assert(!j1979_isotp_current(&link));
    uint8_t request[2]={4,0},response[3]={0};
    size_t n=sizeof(response);
    assert(j1979_isotp_exchange(&link,request,1,response,&n,200)<0);
    j1979_isotp_close(&link);
    puts("Native Linux ISO-TP interface and physical scope policy checks passed");
    return 0;
}
