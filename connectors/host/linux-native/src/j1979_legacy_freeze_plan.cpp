#include "j1979_legacy_freeze_plan.hpp"
#include "j1979_legacy_identity.hpp"
#include <algorithm>
#include <bitset>
namespace cd::legacy {
bool FreezePlan::valid()const{
    if(!valid_vin(vin)||frames.empty()||frames.size()>8||pids.size()>64||budget_ms<1000||budget_ms>600000)return false;
    std::bitset<256> seen;
    for(auto frame:frames){if(seen.test(frame))return false;seen.set(frame);}
    seen.reset();
    for(auto pid:pids){if(!pid||pid==2||pid%32==0||seen.test(pid))return false;seen.set(pid);}
    return true;
}
bool FreezePlan::allows(Bytes q)const{
    if(!valid())return false;
    if(q.size()==2)return (q[0]==1&&q[1]==0)||(q[0]==9&&q[1]<=2);
    if(q.size()!=3||q[0]!=2||std::find(frames.begin(),frames.end(),q[2])==frames.end())return false;
    return q[1]==2||q[1]%32==0||pids.empty()||std::find(pids.begin(),pids.end(),q[1])!=pids.end();
}
}
