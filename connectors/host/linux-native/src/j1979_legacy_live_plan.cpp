#include "j1979_legacy_live.hpp"
#include "j1979_legacy_identity.hpp"
#include "j1979_measurement.h"
#include "j1979_multi.hpp"
#include <algorithm>
#include <bitset>

namespace cd::legacy {
namespace {
bool data_pid(unsigned pid){return pid&&pid<=255&&pid%32!=0;}
bool numeric_pid(unsigned pid){
    if(!data_pid(pid))return false;
    if(const auto width=cd::j1979::multi_width(pid))return *width<=5;
    return j1979_measurement_width(static_cast<std::uint8_t>(pid))!=0||cd::j1979::trim_pid(pid);
}
}
bool LivePlan::valid()const{
    if(!valid_vin(vin)||pids.empty()||pids.size()>32||cycles<1||cycles>1000||
        pids.size()*cycles>10000||interval_ms<100||interval_ms>60000||budget_ms<1000||budget_ms>600000)return false;
    std::bitset<256> seen;
    for(auto pid:pids){
        if((raw_only?!data_pid(pid):!numeric_pid(pid))||seen.test(pid))return false;
        seen.set(pid);
    }
    return true;
}
bool LivePlan::allows(Bytes q)const{
    if(!valid()||q.size()!=2)return false;
    if(q[0]==9)return q[1]<=2;
    if(q[0]!=1)return false;
    if(q[1]%32==0)return true;
    if(std::find(pids.begin(),pids.end(),q[1])!=pids.end())return true;
    if(raw_only)return false;
    if(q[1]==0x1d)return std::any_of(pids.begin(),pids.end(),[](auto pid){return cd::j1979::trim_pid(pid);});
    if(q[1]==0x4f||q[1]==0x50)
        return std::any_of(pids.begin(),pids.end(),[&](auto pid){return cd::j1979::dependency(pid)==q[1];});
    return false;
}
}
