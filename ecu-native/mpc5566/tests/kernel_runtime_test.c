#include "flash_model.h"
#include "../kernel_runtime.h"
#include <assert.h>
#include <stdio.h>

typedef struct {
    cd_can_frame frames[16];
    unsigned count, fail_at;
} link_capture;
static bool send_frame(void *context, const cd_can_frame *frame)
{
    link_capture *capture = context;
    if (capture->count >= 16 || capture->count == capture->fail_at) return false;
    capture->frames[capture->count++] = *frame;
    return true;
}

int main(void)
{
    static model m;
    static cd_kernel_runtime runtime;
    link_capture link = { .fail_at = 99 };
    cd_isotp_channel channel = {0x7E0, 0x7E8, false, 0, 1000000, &link, send_frame};
    init_model(&m);
    cd_flash_controller flash = controller_for(&m);
    assert(cd_kernel_runtime_init(&runtime, &flash, &channel, 4, 1));

    cd_can_frame wrong = {0x7E1, false, 8, { 5, 0x3D, 5, 2, 0, 0 }};
    assert(cd_kernel_runtime_step(&runtime, &wrong, 1) && m.pulses == 0 && link.count == 0);
    cd_can_frame erase = {0x7E0, false, 8, { 5, 0x3D, 5, 2, 0, 0 }};
    assert(cd_kernel_runtime_step(&runtime, &erase, 2));
    assert(m.pulses == 1 && runtime.kernel.remaining == 0x10000 && link.count == 2);
    assert(link.frames[0].bytes[0] == 3 && link.frames[0].bytes[1] == 0x7F &&
        link.frames[0].bytes[3] == 0x78);
    assert(link.frames[1].bytes[1] == 0x7D && link.frames[1].bytes[2] == 5);
    assert(cd_kernel_runtime_step(&runtime, NULL, 3));
    cd_kernel_runtime_link_failure(&runtime);
    assert(flash.recovery_required && !cd_kernel_runtime_step(&runtime, NULL, 4));

    init_model(&m);
    flash = controller_for(&m);
    link.count = 0; link.fail_at = 0;
    assert(cd_kernel_runtime_init(&runtime, &flash, &channel, 4, 1));
    assert(!cd_kernel_runtime_step(&runtime, &erase, 1));
    assert(m.pulses == 0 && !flash.recovery_required); /* pending could not be sent */

    init_model(&m);
    flash = controller_for(&m);
    link.count = 0; link.fail_at = 1;
    assert(cd_kernel_runtime_init(&runtime, &flash, &channel, 4, 1));
    assert(!cd_kernel_runtime_step(&runtime, &erase, 1));
    assert(m.pulses == 1 && flash.recovery_required); /* lost final response */

    init_model(&m);
    flash = controller_for(&m);
    link.count = 0; link.fail_at = 99;
    assert(cd_kernel_runtime_init(&runtime, &flash, &channel, 4, 1));
    const cd_can_frame probe = {0x7E0, false, 8, {2, 0x3D, 0}};
    assert(cd_kernel_runtime_step(&runtime, &probe, 1));
    assert(runtime.tx.state == CD_ISOTP_TX_WAIT_FLOW && link.count == 1);
    assert(cd_kernel_runtime_step(&runtime, &erase, 2) && m.pulses == 0);
    const cd_can_frame flow = {0x7E0, false, 8, {0x30, 0, 0}};
    assert(cd_kernel_runtime_step(&runtime, &flow, 3));
    assert(runtime.tx.state == CD_ISOTP_TX_IDLE && link.count == 2);
    /* Two single-frame replies in immediate succession, with no idle poll. */
    init_model(&m);
    flash = controller_for(&m);
    link.count = 0;
    assert(cd_kernel_runtime_init(&runtime, &flash, &channel, 4, 1));
    const cd_can_frame invalid = {0x7E0, false, 8, {1, 0xFF}};
    assert(cd_kernel_runtime_step(&runtime, &invalid, 1));
    assert(cd_kernel_runtime_step(&runtime, &invalid, 2));
    assert(link.count == 2);
    init_model(&m);
    flash = controller_for(&m);
    link.count = 0;
    assert(cd_kernel_runtime_init(&runtime, &flash, &channel, 4, 1));
    assert(cd_kernel_runtime_step(&runtime, &erase, 1));
    assert(cd_kernel_runtime_step(&runtime, NULL, 4000000));
    assert(!cd_kernel_runtime_step(&runtime, NULL, 5000000));
    assert(flash.recovery_required);
    puts("PASS: native runtime CAN-to-flash dispatch and response-loss handling");
    return 0;
}
