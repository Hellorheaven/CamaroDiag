#pragma once
#include "j1979_isotp.h"
#include "j1979_freeze_scope.h"
#include "j1979_freeze_campaign.h"
#include "cf1.h"
#include "j1979_elm_can.hpp"
namespace cd::hostfreeze {
int exchange(j1979_isotp_link&,const J1979FreezeScope&,const uint8_t*,size_t,uint8_t*,size_t*,unsigned);
struct Result {
    J1979FreezeCampaign::Summary summary{};
    bool capture_started{},terminal_written{},local_socket_closed{};
};
struct ElmResult {
    Result capture;
    std::vector<elmcan::Observation> observations;
    std::string error;
};
ElmResult run_elm(const cf1_plan&,elmcan::Scope,legacy::Link&,legacy::Current,int capture_fd,elmcan::EvidenceSink={});
Result run_socket(const cf1_plan&,const char* interface,int capture_fd,const volatile sig_atomic_t* cancel);
}
