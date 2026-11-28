#include "j1979_live_history.hpp"
#include "j1979_can_freeze_history.hpp"
#include "j1979_legacy_scan_history.hpp"
#include "j1979_legacy_identity.hpp"
#include "j1979_native_report_detail.hpp"
#include <algorithm>
#include <array>

namespace cd::network::history {
namespace {
using namespace detail;
const Value* property(const Value& v,std::string_view k){
    std::string camel;bool upper=false;
    for(char c:k){
        if(c=='_'){upper=true;continue;}
        if(upper&&c>='a'&&c<='z')c=static_cast<char>(c-'a'+'A');
        upper=false;camel+=c;
    }
    const auto* a=v.find(k);const auto* b=camel==k?nullptr:v.find(camel);
    require(!a||!b,"Ambiguous historical transport field");return a?a:b;
}
const Value& optional(const Value& v,std::string_view k){
    static const Value nil{};const auto* p=property(v,k);return p?*p:nil;
}
const Value& field(const Value& v,std::string_view k){
    const auto* p=property(v,k);require(p,"Missing historical transport field");return *p;
}
bool correlated(std::string_view status){
    return status=="PASSED"||status=="RAW"||status=="NEGATIVE";
}
bool trace_positive(std::string_view status){
    return status=="PASSED"||status=="CORRELATED"||status=="NEGATIVE";
}
void bound(std::size_t& total,std::size_t n){
    require(n<=256u*1024u&&total<=16u*1024u*1024u-n,"Historical live transport evidence limit");
    total+=n;
}
std::optional<unsigned> number(const Value& v,unsigned min,unsigned max){
    if(v.null())return {};
    const auto n=v.unsigned_integer();require(n>=min&&n<=max,"Historical live protocol range");
    return static_cast<unsigned>(n);
}
void can(const Value& root,HistoricalLive& out,std::span<const HistoricalLiveRow> rows){
    const auto* raw=property(root,"raw_protocol_response");
    if(!raw)return;
    const auto profile=number(field(root,"expected_protocol"),6,9);
    require(profile&&((out.pair.tx>0x7ff)==(*profile==7||*profile==9)),"Historical CAN profile/address mismatch");
    const auto observed=number(optional(root,"observed_protocol"),6,9);
    require(historical_elm_can_probe(*raw)==observed,"Historical CAN protocol transcript mismatch");
    if(!out.raw_only)return;
    std::size_t total=0;
    for(const auto& r:rows){
        const auto& transcript=optional(*r.original,"raw_adapter_response");
        if(!transcript.null())bound(total,transcript.string().size());
        if(correlated(r.status)){
            require(!transcript.null(),"Missing raw CAN live transcript");
            require(historical_elm_can_payload(transcript.string(),out.pair)==r.response,"Historical CAN live response differs from selected ISO-TP source");
            if(r.request!=Bytes{9,2})require(observed==profile,"CAN data read before fixed protocol");
        }else if(r.status=="TIMEOUT"&&!transcript.null()){
            require(historical_elm_no_data(transcript.string()),"CAN live timeout contradicts transcript");
        }
    }
    require(!out.completed||observed==profile,"Completed CAN live report lacks fixed protocol");
    out.adapter_replayed=true;
}
struct Exchange{
    std::string command,status;
    std::vector<Bytes> selected;
};
Exchange exchange(const Value& v,const HistoricalLive& out,std::size_t& total){
    Exchange e{field(v,"request_hex").string(),field(v,"status").string(),{}};
    const auto protocol=*out.protocol;
    const bool probe=e.command=="ATDPN";
    Bytes q;
    if(!probe){
        q=legacy::unhex(e.command);
        require(q.size()==2&&(q[0]==1||(q[0]==9&&q[1]<=2)),"Unexpected legacy live transport command");
    }
    const auto& saved=field(v,"frames").array();
    require(saved.size()<=128,"Legacy live frame count");
    if(out.legacy_driver){
        require(!probe,"Driver trace contains ELM command");
        const auto& tx=optional(v,"tx_hex");
        if(!tx.null())require(bytes(tx,8)==legacy::history::driver_request_frame(protocol,q),"Legacy live transmitted header mismatch");
        for(const auto& item:saved){
            auto frame=legacy::history::inspect_driver_frame(item,protocol,out.source);
            bound(total,frame.raw.size()*2);e.selected.push_back(std::move(frame.payload));
        }
        return e;
    }
    const auto& raw=optional(v,"raw_response");
    if(!raw.null())bound(total,raw.string().size());
    const auto& selected=optional(v,"selected_records_hex");
    if(probe){
        require(saved.empty()&&(selected.null()||selected.array().empty()),"Legacy probe contains frames");
        const auto claimed=number(optional(v,"observed_protocol"),1,5);
        if(e.status=="PASSED"){
            if(raw.null())require(claimed==static_cast<unsigned>(protocol),"Missing legacy protocol probe");
            else{
                require(legacy::fixed_protocol(raw.string())==protocol,"Legacy live protocol probe mismatch");
                require(!claimed||*claimed==static_cast<unsigned>(protocol),"Legacy live protocol declaration mismatch");
            }
        }
        return e;
    }
    std::vector<legacy::Frame> decoded;
    if(raw.null())require(saved.empty()&&!trace_positive(e.status),"Missing legacy live frame transcript");
    else{
        try{decoded=legacy::frames(protocol,e.command,raw.string());}
        catch(const std::invalid_argument&){
            require(saved.empty()&&!trace_positive(e.status),"Malformed legacy live transcript accepted");
        }
    }
    require(decoded.size()==saved.size(),"Legacy live frame count differs");
    for(std::size_t i=0;i<saved.size();++i){
        const auto& f=decoded[i];const auto& claim=saved[i];
        require(address(field(claim,"source"))==f.source&&bytes(field(claim,"raw_hex"),11)==f.raw&&
            bytes(field(claim,"payload_hex"),7)==f.payload,"Legacy live recorded frame differs from transcript");
        if(f.source==out.source)e.selected.push_back(f.payload);
    }
    if(!selected.null()){
        const auto& a=selected.array();require(a.size()==e.selected.size(),"Legacy live selected record count differs");
        for(std::size_t i=0;i<a.size();++i)require(bytes(a[i],7)==e.selected[i],"Legacy live selected record differs");
    }
    return e;
}
void identity(const std::vector<Exchange>& group,const HistoricalLiveRow& row,bool driver){
    const std::vector<std::string> order=driver?std::vector<std::string>{"0100","0900","0901","0902"}:
        std::vector<std::string>{"0100","ATDPN","0900","0901","0902"};
    require(group.size()<=order.size(),"Too many legacy live identity exchanges");
    for(std::size_t i=0;i<group.size();++i)require(group[i].command==order[i],"Legacy live identity command order differs");
    if(row.status=="NEGATIVE"){
        require(!group.empty()&&group.back().selected==std::vector<Bytes>{row.response},"Legacy live identity negative response differs");return;
    }
    if(!correlated(row.status))return;
    require(group.size()==order.size()&&row.status=="PASSED","Incomplete legacy live identity transcript");
    const auto offset=driver?1u:2u;
    const auto& initial=group[0].selected;
    require(initial.size()==1&&initial[0].size()==6&&initial[0][0]==0x41&&initial[0][1]==0&&trace_positive(group[0].status),
        "Legacy live identity lacks selected-source PID00");
    if(!driver)require(group[1].status=="PASSED","Legacy live identity lacks verified protocol");
    require(trace_positive(group[offset].status)&&group[offset].selected.size()==1&&legacy::vin_support_page(group[offset].selected[0]),
        "Legacy live identity support differs");
    require(trace_positive(group[offset+1].status)&&group[offset+1].selected.size()==1&&legacy::vin_fragment_count(group[offset+1].selected[0]),
        "Legacy live identity count differs");
    require(trace_positive(group.back().status),"Legacy live VIN fragments not accepted");
    const auto vin=legacy::normalized_vin(group.back().selected);
    require(vin.has_value(),"Legacy live VIN fragments malformed");
    Bytes normalized{0x49,2,1};normalized.insert(normalized.end(),vin->begin(),vin->end());
    require(normalized==row.response,"Legacy live VIN differs from selected fragments");
}
void legacy_trace(const Value& root,HistoricalLive& out,std::span<const HistoricalLiveRow> rows){
    const auto& trace=field(root,"transport_exchanges").array();
    require(trace.size()<=10256,"Historical legacy live exchange count");
    std::vector<std::vector<Exchange>> groups(rows.size());
    std::size_t total=0,previous=0;
    for(const auto& v:trace){
        const auto index=field(v,"logical_query").unsigned_integer();
        require(index<rows.size()&&index>=previous,"Legacy live logical query order differs");previous=index;
        auto& group=groups[index];require(group.size()<5,"Legacy live logical query exchange limit");
        group.push_back(exchange(v,out,total));
    }
    for(std::size_t i=0;i<rows.size();++i){
        const auto& r=rows[i];const auto& g=groups[i];
        require(!g.empty(),"Missing legacy live logical exchange");
        if(r.request==Bytes{9,2})identity(g,r,out.legacy_driver);
        else{
            require(g.size()==1&&g[0].command==legacy::hex(r.request),"Legacy live measurement command differs");
            if(correlated(r.status))require(trace_positive(g[0].status)&&g[0].selected==std::vector<Bytes>{r.response},
                "Legacy live measurement differs from selected source");
        }
    }
}
}
void verify_live_transport(const json::Value& root,HistoricalLive& out,std::span<const HistoricalLiveRow> rows){
    if(out.protocol)legacy_trace(root,out,rows);else can(root,out,rows);
}
}
