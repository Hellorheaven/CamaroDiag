#include "j1979_catalogue_projection.hpp"
#include "cl1.h"
#include "cf1.h"
#include "j1979_legacy_live_wire.hpp"
#include "j1979_legacy_journal.hpp"
#include <openssl/evp.h>
#include <array>
#include <algorithm>
#include <cstring>
#include <stdexcept>
namespace cd::catalogue {
namespace {
void require(bool b,const char* why){if(!b)throw std::invalid_argument(why);}
unsigned u16(Bytes b,unsigned at){return unsigned(b[at])|(unsigned(b[at+1])<<8);}
std::uint32_t u32(Bytes b,unsigned at){
    return std::uint32_t(b[at])|(std::uint32_t(b[at+1])<<8)|
        (std::uint32_t(b[at+2])<<16)|(std::uint32_t(b[at+3])<<24);
}
Projection begin(Mode mode,std::string_view vin,const Catalogue& c,std::string_view source,std::string_view sha){
    require(c.mode==mode&&valid_digest(source)&&valid_digest(sha),"Catalogue/source mode or digest mismatch");
    const std::string identity(vin);
    require(identity.size()==17&&cf1_valid_vin(identity.c_str()),"Independent VIN required");
    Projection p{};p.mode=mode;p.vin=identity;p.source_sha256=source;p.catalogue_sha256=sha;
    return p;
}
void add(Projection& p,ProjectionRow row,const Catalogue& catalogue,bool stable,std::size_t& signals){
    const bool freeze=p.mode==Mode::freeze;
    const auto max_rows=freeze?25000u:100000u;
    require(p.rows.size()<max_rows,"Projection row bound");
    const auto* def=catalogue.find(row.pid,p.transport,row.frame);
    const bool accepted=row.source_status=="RAW"||(freeze&&row.source_status=="PASSED");
    if(!p.identity_rechecked)row.status="IDENTITY_NOT_RECHECKED";
    else if(freeze&&!stable)row.status="TRIGGER_NOT_STABLE";
    else if(!accepted)row.status="NO_CORRELATED_RAW_PAYLOAD";
    else if(!def)row.status="NO_DOCUMENTED_DEFINITION";
    else{
        const unsigned offset=freeze?3:2;
        require(row.response.size()>offset&&row.response[0]==(freeze?0x42:0x41)&&row.response[1]==row.pid&&
            (!freeze||row.response[2]==*row.frame),"Projection reply differs from admitted request");
        if(row.response.size()!=offset+def->width)row.status="DOCUMENTED_WIDTH_MISMATCH";
        else{
            require(def->signals.size()<=max_rows-signals,"Projection signal bound");
            signals+=def->signals.size();row.reference=def->reference;row.status="PROJECTED";
            for(const auto& signal:def->signals)row.signals.push_back(project_signal(signal,Bytes(row.response).subspan(offset)));
        }
    }
    p.rows.push_back(std::move(row));
}
}
Projection project_cl1(Bytes bytes,std::string_view vin,const Catalogue& c,std::string_view source,std::string_view sha){
    auto p=begin(Mode::live,vin,c,source,sha);
    cl1_result e{};char failure[256]{};
    if(cl1_inspect_complete(bytes.data(),bytes.size(),p.vin.c_str(),&e,failure,sizeof(failure))!=0)
        throw std::invalid_argument(failure);
    require(e.capture_integrity_verified&&e.terminal_present,"Original complete CL/1 evidence required");
    p.tx=e.plan.tx;p.rx=e.plan.rx;p.extended=e.plan.extended;p.completed=e.completed;
    p.identity_rechecked=e.initial_vin&&e.final_vin;
    std::size_t signals=0;
    for(std::size_t pos=0;pos<bytes.size();){
        const auto header=bytes.subspan(pos,8);
        const auto size=u16(header,6);
        const auto body=bytes.subspan(pos+8,size);
        if(header[3]==2&&body[0]==4){
            ProjectionRow row{};row.pid=body[6];row.cycle=u16(body,4);row.elapsed_ms=u32(body,12);
            const auto accepted=body[2]!=0;const auto state=body[1];
            row.source_status=!accepted?"FAILED":state==2?"RAW":state==3?"NEGATIVE":state==4?"TIMEOUT":"FAILED";
            row.response.assign(body.begin()+CL1_EVENT_SIZE,body.end());
            add(p,std::move(row),c,true,signals);
        }
        pos+=size+12;
    }
    return p;
}
Projection project_cf1(Bytes bytes,std::string_view vin,const Catalogue& c,std::string_view source,std::string_view sha){
    auto p=begin(Mode::freeze,vin,c,source,sha);
    struct Evidence{
        cf1_evidence e{};
        ~Evidence(){cf1_evidence_free(&e);}
    } holder;
    auto& e=holder.e;char failure[256]{};
    if(cf1_inspect_complete(bytes.data(),bytes.size(),p.vin.c_str(),&e,failure,sizeof(failure))!=0)
        throw std::invalid_argument(failure);
    require(e.capture_integrity_verified&&e.terminal_present,"Original complete CF/1 evidence required");
    p.tx=e.plan.tx;p.rx=e.plan.rx;p.extended=e.plan.extended;p.completed=e.execution_completed;
    p.identity_rechecked=e.execution_completed&&e.initial_vin_observed&&e.final_vin_observed&&
        p.vin==e.initial_vin&&p.vin==e.final_vin;
    for(unsigned i=0;i<e.plan.frame_count;++i){
        FrameCoverage coverage{};coverage.frame=e.plan.frames[i];
        if(i<e.visited_frames){
            const auto& f=e.progress[i];
            require(f.number==coverage.frame,"CF/1 frame progress mismatch");
            coverage.state=f.provisional_status;coverage.trigger_stable=f.trigger_stable;coverage.inventory_complete=f.inventory_complete;
        }
        for(const auto& d:c.definitions)if(d.frame==coverage.frame)coverage.documented.insert(d.pid);
        p.coverage.push_back(std::move(coverage));
    }
    std::size_t signals=0;
    for(std::size_t i=0;i<e.observation_count;++i){
        const auto& row=e.observations[i];
        if(row.request_length!=3||row.request[0]!=2)continue;
        const unsigned pid=row.request[1],frame=row.request[2];
        auto it=std::find_if(p.coverage.begin(),p.coverage.end(),[&](const auto& f){return f.frame==frame;});
        require(it!=p.coverage.end(),"CF/1 observation outside frame scope");
        if(pid%32==0){
            if(row.outcome==1&&row.response_length==7&&row.response[0]==0x42&&row.response[1]==pid&&row.response[2]==frame){
                for(unsigned bit=0;bit<31;++bit)if(row.response[3+bit/8]&(0x80u>>(bit%8))&&pid+bit+1!=2)
                    it->advertised.insert(pid+bit+1);
            }
            continue;
        }
        if(pid==2)continue;
        require(it->advertised.contains(pid),"CF/1 measurement was not advertised");
        ProjectionRow r{};r.pid=pid;r.frame=frame;r.step_index=static_cast<unsigned>(i);
        r.source_status=cf1_measurement_correlated(&row)?"RAW":row.outcome==2?"TIMEOUT":"FAILED";
        if(row.response_length)r.response.assign(row.response,row.response+row.response_length);
        it->requested.insert(pid);
        if(r.source_status=="RAW")it->responded.insert(pid);
        add(p,std::move(r),c,it->trigger_stable,signals);
        if(p.rows.back().status=="PROJECTED")it->projected.insert(pid);
    }
    return p;
}
Projection project_llr1(Bytes bytes,std::string_view vin,unsigned protocol,unsigned source,const Catalogue& c,
                        std::string_view source_sha,std::string_view catalogue_sha){
    auto p=begin(Mode::live,vin,c,source_sha,catalogue_sha);
    require(protocol>=1&&protocol<=5,"Explicit legacy protocol required");
    const auto e=cd::legacy::inspect_live_capture(bytes,static_cast<cd::legacy::Protocol>(protocol),source,vin);
    require(e.plan.raw_only,"Original raw-only legacy live capture required");
    p.transport=Transport::legacy;p.legacy_protocol=protocol;p.legacy_source=source;
    p.completed=e.completed;p.identity_rechecked=e.identity_rechecked;
    std::size_t signals=0;
    for(const auto& sample:e.samples){
        ProjectionRow r{};r.pid=sample.pid;r.cycle=sample.cycle;r.elapsed_ms=sample.elapsed_ms;
        r.source_status=sample.status;r.response=sample.response;
        add(p,std::move(r),c,true,signals);
    }
    return p;
}

Projection project_lj1(Bytes bytes,std::string_view vin,unsigned protocol,unsigned source,const Catalogue& c,
                       std::string_view journal_sha,std::string_view catalogue_sha){
    require(protocol>=1&&protocol<=5,"Explicit legacy protocol required");
    const auto e=cd::legacy::inspect_journal(bytes,static_cast<cd::legacy::Protocol>(protocol),source,vin);
    require(e.kind==cd::legacy::JournalKind::live&&e.terminal&&!e.torn_bytes&&!e.capture.empty(),
        "Sealed original live LJ/1 journal required");
    const auto digest=[](Bytes raw){
        std::array<unsigned char,32> hash{};unsigned size{};
        require(EVP_Digest(raw.data(),raw.size(),hash.data(),&size,EVP_sha256(),nullptr)==1&&size==hash.size(),
            "Catalogue journal SHA-256 unavailable");
        constexpr char digits[]="0123456789abcdef";
        std::string hex;hex.reserve(64);
        for(auto n:hash){hex+=digits[n>>4];hex+=digits[n&15];}
        return hex;
    };
    require(digest(bytes)==journal_sha,"Original journal digest differs");
    const auto capture_sha=digest(e.capture);
    auto p=project_llr1(e.capture,vin,protocol,source,c,capture_sha,catalogue_sha);
    p.source_sha256=std::string(journal_sha);
    p.reconstructed_capture_sha256=capture_sha;
    return p;
}

}
