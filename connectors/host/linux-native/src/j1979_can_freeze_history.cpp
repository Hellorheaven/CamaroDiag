#include "j1979_can_freeze_history.hpp"
#include "j1979_native_report_detail.hpp"
#include "j1979_can.h"
#include <algorithm>
#include <chrono>
#include <sstream>
#include <set>

void cd::network::history::validate_historical_timestamp(const cd::json::Value& v){
    // ISO 8601 offset-bearing metadata only; never infer a monotonic bus clock.
    const auto& s=v.string();
    cd::network::history::detail::require(s.size()>=20&&s.size()<=64,"Invalid freeze timestamp length");
    const auto digit=[&](std::size_t i){return i<s.size()&&s[i]>='0'&&s[i]<='9';};
    const auto num=[&](std::size_t p,std::size_t count){
        unsigned n=0;for(std::size_t i=p;i<p+count;++i){cd::network::history::detail::require(digit(i),"Invalid freeze timestamp digit");n=n*10+s[i]-'0';}
        return n;
    };
    cd::network::history::detail::require(s[4]=='-'&&s[7]=='-'&&(s[10]=='T'||s[10]==' ')&&s[13]==':'&&s[16]==':',"Invalid timestamp separators");
    const std::chrono::year_month_day date{std::chrono::year(static_cast<int>(num(0,4))),
        std::chrono::month(num(5,2)),std::chrono::day(num(8,2))};
    cd::network::history::detail::require(date.ok()&&num(11,2)<=23&&num(14,2)<=59&&num(17,2)<=59,"Invalid timestamp calendar fields");
    std::size_t i=19;
    if(i<s.size()&&(s[i]=='.'||s[i]==',')){
        ++i;cd::network::history::detail::require(digit(i),"Empty timestamp fraction");while(digit(i))++i;
    }
    if(i+1==s.size()&&s[i]=='Z')return;
    cd::network::history::detail::require(i+6==s.size()&&(s[i]=='+'||s[i]=='-')&&s[i+3]==':'&&num(i+1,2)<=23&&num(i+4,2)<=59,
        "Timestamp lacks valid timezone");
}

namespace cd::network::history {
namespace {
using namespace detail;
constexpr std::size_t max_raw=256*1024,max_combined=16*1024*1024;
std::string_view trim(std::string_view s){
    const auto ws=[](char c){return c==' '||c=='\t'||c=='\r'||c=='\n'||c=='\f'||c=='\v';};
    while(!s.empty()&&ws(s.front()))s.remove_prefix(1);
    while(!s.empty()&&ws(s.back()))s.remove_suffix(1);
    return s;
}
std::string upper(std::string_view s){
    std::string out(s);for(char& c:out)if(c>='a'&&c<='z')c=static_cast<char>(c-'a'+'A');return out;
}
std::vector<std::string> lines(std::string_view s){
    require(s.size()<=max_raw,"Historical adapter transcript exceeds bound");
    std::vector<std::string> out;
    std::size_t start=0;
    for(std::size_t i=0;i<=s.size();++i){
        if(i<s.size()&&s[i]!='\r'&&s[i]!='\n'&&s[i]!='>')continue;
        const auto line=trim(s.substr(start,i-start));start=i+1;
        if(!line.empty())out.push_back(upper(line));
    }
    return out;
}
std::optional<unsigned> probe(const Value& v){
    if(v.null())return {};
    auto row=lines(v.string());
    std::erase(row,std::string("ATDPN"));
    if(row.size()!=1||row[0].size()!=1||row[0][0]<'6'||row[0][0]>'9')return {};
    return static_cast<unsigned>(row[0][0]-'0');
}
std::vector<std::string_view> tokens(std::string_view s){
    std::vector<std::string_view> out;
    while(!(s=trim(s)).empty()){
        const auto p=s.find_first_of(" \t");out.push_back(s.substr(0,p));
        if(p==std::string_view::npos)break;
        s.remove_prefix(p+1);
    }
    return out;
}
std::optional<unsigned> hexnum(std::string_view s){
    unsigned n{};
    const auto [p,e]=std::from_chars(s.data(),s.data()+s.size(),n,16);
    return !s.empty()&&e==std::errc{}&&p==s.data()+s.size()?std::optional<unsigned>{n}:std::nullopt;
}
// Historical Android CAF0/ATH1/ATS1/ATD0 format has a different contract
// from the stricter native live decoder: preserve wrong-service payloads
// for application classification, without accepting them as good replies.
Bytes decode(std::string_view raw,Pair pair){
    const bool extended=pair.tx>0x7ff;
    const auto width=extended?8u:3u;
    Bytes output;
    unsigned expected=0,sequence=1;
    bool complete=false;
    for(const auto& line:lines(raw)){
        if(line.starts_with("FC:"))continue;
        for(auto bad:{"NO DATA","CAN ERROR","BUS ERROR","BUFFER FULL","STOPPED","UNABLE TO CONNECT","?"})
            require(line.find(bad)==std::string::npos,"Adapter diagnostic exchange failed");
        const auto fields=tokens(line);
        if(fields.empty())continue;
        std::size_t count=1;std::string header(fields[0]);
        if(extended&&fields.size()>=4&&std::all_of(fields.begin(),fields.begin()+4,[](auto p){return p.size()==2&&hexnum(p).has_value();})){
            count=4;header.clear();for(std::size_t i=0;i<4;++i)header+=fields[i];
        }
        if(header.size()!=width||hexnum(header)!=pair.rx)continue;
        require(fields.size()>count&&fields.size()-count<=8&&!complete,"Malformed or multiple selected CAN replies");
        Bytes frame;
        for(std::size_t i=count;i<fields.size();++i){
            const auto b=hexnum(fields[i]);
            require(fields[i].size()==2&&b&&*b<=255,"Malformed selected CAN frame");
            frame.push_back(static_cast<std::uint8_t>(*b));
        }
        switch(frame[0]>>4){
        case 0:
            require(!expected,"Unexpected selected CAN single frame");
            expected=frame[0]&15;
            require(expected>=1&&expected<=7&&frame.size()>=expected+1,"Truncated selected CAN single frame");
            output.assign(frame.begin()+1,frame.begin()+1+expected);break;
        case 1:
            require(!expected&&frame.size()==8,"Invalid selected CAN first frame");
            expected=(unsigned(frame[0]&15)<<8)|frame[1];
            require(expected>=8&&expected<=4095,"Invalid selected CAN first-frame length");
            output.assign(frame.begin()+2,frame.end());break;
        case 2:{
            require(expected>7&&output.size()<expected&&(frame[0]&15)==sequence,"Unexpected selected CAN sequence");
            const auto remaining=std::min<std::size_t>(7,expected-output.size());
            require(frame.size()>=remaining+1,"Truncated selected CAN consecutive frame");
            output.insert(output.end(),frame.begin()+1,frame.begin()+1+remaining);
            sequence=(sequence+1)&15;break;
        }
        default:throw std::invalid_argument("Unexpected selected CAN PCI");
        }
        complete=expected&&output.size()==expected;
    }
    require(complete,"No complete response from selected CAN ECU");
    return output;
}
bool elm_replay(const Value& root,const HistoricalFreeze& result,Pair pair,unsigned profile){
    const auto& v=root.at("observed_protocol");
    std::optional<unsigned> observed;
    if(!v.null()){
        const auto n=v.unsigned_integer();require(n>=6&&n<=9,"Invalid historical ELM protocol");observed=static_cast<unsigned>(n);
    }
    require(probe(root.at("raw_protocol_response"))==observed,"ELM protocol transcript differs");
    const auto& trace=root.at("adapter_exchanges").array();
    const auto& steps=result.replayed.at("steps").array();
    require(trace.size()==steps.size(),"Missing Android freeze adapter exchange");
    std::size_t total=0;
    for(std::size_t i=0;i<steps.size();++i){
        const auto& row=trace[i];const auto& step=steps[i];
        json::fields(row,{"step","request_hex","raw_response"});
        require(row.at("step").unsigned_integer()==i&&row.at("request_hex").string()==step.at("request_hex").string(),
            "Historical adapter step order differs");
        const auto attempted=step.at("transmission_attempted").boolean();
        if(attempted)require(observed==profile,"Unverified ELM CAN profile before request");
        const auto& raw=row.at("raw_response");
        if(!raw.null()){
            const auto& s=raw.string();require(attempted&&s.size()<=max_raw&&s.size()<=max_combined-total,
                "Invalid historical adapter transcript");
            total+=s.size();
        }
        const auto& outcome=step.at("transport_outcome").string();
        if(outcome=="RECEIVED"||outcome=="LATE_RESPONSE"){
            require(!raw.null()&&legacy::hex(decode(raw.string(),pair))==step.at("response_hex").string(),
                "Historical CAN freeze payload differs from selected ISO-TP transcript");
        }else if(outcome=="TIMEOUT"&&!raw.null()){
            require(lines(raw.string())==std::vector<std::string>{"NO DATA"},"Historical CAN timeout contradicts adapter transcript");
        }
    }
    return observed==profile;
}
std::string id(unsigned v){std::ostringstream o;o<<std::uppercase<<std::hex<<v;return o.str();}
}
std::optional<unsigned> historical_elm_can_probe(const json::Value& v){return probe(v);}
std::vector<std::uint8_t> historical_elm_can_payload(std::string_view raw,Pair pair){return decode(raw,pair);}
bool historical_elm_no_data(std::string_view raw){return lines(raw)==std::vector<std::string>{"NO DATA"};}
CanFreezeInspection inspect_historical_can_freeze(const json::Value& root,std::optional<std::string> expected){
    CanFreezeInspection out{};
    out.transport=root.at("transport").string();
    const bool socket=out.transport=="LINUX_SOCKETCAN_ISOTP",windows=out.transport=="WINDOWS_J2534_ISOTP",
        android=out.transport=="ANDROID_ELM_ISOTP";
    require(socket||windows||android,"Unsupported historical CAN freeze transport");
    std::set<std::string_view> keys{"schema","protocol","transport","version","tx_can_id","rx_can_id",
        "physical_channel_confirmed_by_operator","started_at_utc","finished_at_utc","transport_problem",
        "execution_completed","result"};
    if(socket)keys.insert("interface");
    else if(windows)keys.insert({"adapter","profile"});
    else keys.insert({"adapter","profile","raw_protocol_response","observed_protocol","adapter_exchanges"});
    require(root.object().size()==keys.size(),"Unexpected CAN freeze report fields");
    for(auto k:keys)require(root.find(k)!=nullptr,"Missing CAN freeze report field");
    require(root.at("schema").string()=="camarodiag-functional-j1979-freeze-v1"&&
        root.at("protocol").string()=="SAE-J1979","Invalid historical CAN freeze schema");
    const auto& version=root.at("version").string();
    require(!version.empty()&&version.size()<=128,"Invalid historical host version");
    out.pair={address(root.at("tx_can_id")),address(root.at("rx_can_id"))};
    require(j1979_can_physical_pair(out.pair.tx,out.pair.rx),"Invalid historical freeze physical pair");
    unsigned profile=0;
    if(socket){
        const auto& label=root.at("interface").string();
        require(!label.empty()&&label.size()<=15&&std::all_of(label.begin(),label.end(),[](unsigned char c){
            return (c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='_'||c=='.'||c=='-';
        }),"Invalid SocketCAN interface");
    }else{
        const auto& adapter=root.at("adapter").string();
        require(!adapter.empty()&&adapter.size()<=1024,"Invalid CAN adapter label");
        const auto n=root.at("profile").unsigned_integer();
        require(windows?(n>=33&&n<=36):(n>=6&&n<=9),"Invalid historical CAN profile");
        profile=static_cast<unsigned>(n);
        require((out.pair.tx>0x7ff)==(windows?(n==34||n==36):(n==7||n==9)),"CAN profile differs from physical pair");
    }
    require(root.at("physical_channel_confirmed_by_operator").boolean(),"Missing historical CAN channel selection");
    validate_historical_timestamp(root.at("started_at_utc"));validate_historical_timestamp(root.at("finished_at_utc"));
    out.transport_problem=nullable(root.at("transport_problem"),64);
    require(out.transport_problem.empty()||out.transport_problem=="TRANSPORT_ERROR"||out.transport_problem=="CANCELLED",
        "Unknown historical CAN transport problem");
    out.independent_vin=expected.has_value();
    out.result=replay_historical_freeze(root.at("result"),std::move(expected));
    const bool verified=!android||elm_replay(root,out.result,out.pair,profile);
    out.adapter_transcript_replayed=android;
    out.execution_completed=out.result.completed&&out.transport_problem.empty()&&verified;
    require(root.at("execution_completed").boolean()==out.execution_completed,"Historical CAN freeze completion differs");
    out.all_frames_stable=out.execution_completed&&out.result.all_frames_stable;
    return out;
}
std::string historical_can_freeze_report(const CanFreezeInspection& s,std::string_view digest){
    std::ostringstream o;
    o<<"{\"schema\":\"camarodiag-j1979-freeze-inspection-v1\",\"consistent\":true,"
        "\"execution_completed\":"<<(s.execution_completed?"true":"false")<<",\"all_frames_stable\":"<<(s.all_frames_stable?"true":"false")
        <<",\"source_transport\":"<<json::quote(s.transport)<<",\"tx_can_id\":"<<json::quote(id(s.pair.tx))
        <<",\"rx_can_id\":"<<json::quote(id(s.pair.rx))<<",\"adapter_transcript_replayed\":"<<(s.adapter_transcript_replayed?"true":"false")
        <<",\"expected_vin_independently_supplied\":"<<(s.independent_vin?"true":"false")
        <<",\"transport_problem\":"<<(s.transport_problem.empty()?"null":json::quote(s.transport_problem))
        <<",\"authenticated\":false,\"physical_vehicle_verified\":false,\"whole_vehicle_coverage_verified\":false,"
        "\"timing_independently_verified\":false,\"permits_programming\":false,\"source_sha256\":"
        <<(digest.empty()?"null":json::quote(digest))<<",\"replayed\":"<<encode_historical_json(s.result.replayed)<<"}\n";
    return o.str();
}
}
