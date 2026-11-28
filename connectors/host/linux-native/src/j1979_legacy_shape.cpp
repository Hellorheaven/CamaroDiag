#include "j1979_legacy_shape.hpp"
#include "j1979_measurement.h"
#include "j1979_projection.hpp"
#include "j1979_context.hpp"
#include <algorithm>
namespace cd::legacy {
ApplicationShape pid_shape(Bytes q,Bytes r){
    using S=ApplicationShape;
    if(q.size()<2||q.size()>3||(q[0]!=1&&q[0]!=2)||
        q.size()!=(q[0]==1?2u:3u)||r.empty()||r.size()>7)return S::invalid;
    if(r[0]==0x7f)return r.size()==3&&r[1]==q[0]&&r[2]&&r[2]!=0x78?S::negative:S::invalid;
    const auto mode=q[0],pid=q[1];const size_t off=q.size();
    if(r.size()<=off||r[0]!=mode+64||!std::equal(q.begin()+1,q.end(),r.begin()+1))return S::invalid;
    if(pid%32==0)return r.size()==off+4&&!(pid==224&&(r.back()&1))?S::positive:S::invalid;
    if(mode==2&&pid==2)return r.size()==5?S::positive:S::invalid;
    const auto width=j1979_measurement_width(pid);
    const auto count=r.size()-off;
    const bool trim=cd::j1979::trim_pid(pid);
    if(trim)return (count==1||count==2)?(mode==2?S::raw:S::positive):S::invalid;
    if(width&&count!=width)return S::invalid;
    if(mode==2){
        // Frozen context-dependent signals must not inherit the current
        // engine's scaling configuration or oxygen bank layout.
        return width&&!cd::j1979::dependency(pid)?S::positive:S::raw;
    }
    if(pid==0x13||pid==0x1d)return count==1?S::positive:S::invalid;
    if(pid==0x01)return count==4?S::positive:S::invalid;
    if(pid==0x41)return count==4&&cd::j1979::drive_cycle(r.subspan(off),{}).has_value()?S::positive:S::invalid;
    if(pid==0x4f||pid==0x50)return count==4?S::positive:S::invalid;
    const cd::j1979::Observation row{q,r,true,true};
    if(!cd::j1979::known_shape(row))return S::invalid;
    return width||cd::j1979::multi_width(pid)||cd::j1979::status_width(pid)?S::positive:S::raw;
}
}
