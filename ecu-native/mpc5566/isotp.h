#ifndef CAMARODIAG_NATIVE_ISOTP_H
#define CAMARODIAG_NATIVE_ISOTP_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define CD_ISOTP_MAX 4091u

typedef struct {
    uint32_t id;
    bool extended;
    uint8_t length;
    uint8_t bytes[8];
} cd_can_frame;

typedef bool (*cd_can_send)(void *context, const cd_can_frame *frame);

typedef struct {
    uint32_t request_id;
    uint32_t response_id;
    bool extended;
    uint8_t padding;
    uint32_t timeout_us;
    void *context;
    cd_can_send send;
} cd_isotp_channel;

typedef enum {
    CD_ISOTP_IGNORED,
    CD_ISOTP_RECEIVING,
    CD_ISOTP_COMPLETE,
    CD_ISOTP_INVALID,
    CD_ISOTP_SEND_FAILED,
    CD_ISOTP_TIMEOUT
} cd_isotp_rx_result;

typedef struct {
    cd_isotp_channel channel;
    uint8_t *buffer;
    size_t capacity, used, total;
    uint32_t last_activity;
    uint8_t sequence, block_size, st_min, block_received;
    bool receiving, complete;
} cd_isotp_rx;

bool cd_isotp_channel_valid(const cd_isotp_channel *channel);
bool cd_isotp_st_min_us(uint8_t encoded, uint32_t *microseconds);
bool cd_isotp_rx_init(cd_isotp_rx *rx, const cd_isotp_channel *channel,
    uint8_t *buffer, size_t capacity, uint8_t block_size, uint8_t st_min);
cd_isotp_rx_result cd_isotp_rx_frame(cd_isotp_rx *rx,
    const cd_can_frame *frame, uint32_t now_us);
cd_isotp_rx_result cd_isotp_rx_poll(cd_isotp_rx *rx, uint32_t now_us);
void cd_isotp_rx_release(cd_isotp_rx *rx);

#endif
