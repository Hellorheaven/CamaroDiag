#pragma once
#include "j1979_live.h"
#include <cstdio>

namespace cd::liveoptions {
// Display only: no interpreted data before the final VIN verification.
// The callback neither sends an ECU request nor changes the journal.
inline void show_sample(void*,const j1979_live_step* sample) noexcept {
    if(!sample)return;
    std::fprintf(stderr,"J1979 pending identity: %llu ms cycle %u PID %02X %s\n",
        static_cast<unsigned long long>(sample->elapsed_ms),sample->cycle,
        static_cast<unsigned>(sample->request[1]),sample->status);
}
}
