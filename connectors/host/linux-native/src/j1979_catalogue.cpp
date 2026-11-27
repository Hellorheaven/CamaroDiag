#include "j1979_catalogue.hpp"
#include "native_json.hpp"
#include <algorithm>
#include <limits>
#include <tuple>
#include <utility>
namespace cd::catalogue {
namespace {
using json::Value;
void require(bool b,const char* detail){if(!b)throw std::invalid_argument(detail);}
unsigned bounded(const Value& value,unsigned low,unsigned high){
    auto n=value.unsigned_integer();require(n>=low&&n<=high,"Catalogue integer outside bound");return static_cast<unsigned>(n);
}
std::string label(const Value& value,unsigned max=256){
    const auto& s=value.string();require(json::valid_text(s,max),"Invalid catalogue label");return s;
}
Ratio ratio(const Value& v){
    const auto& a=v.array();require(a.size()==2,"Invalid catalogue rational");
    Ratio r{a[0].signed_integer(),a[1].unsigned_integer()};require(r.valid(),"Rational outside bounds");return r;
}
std::uint64_t maximum_raw(unsigned width){
    return width==64?std::numeric_limits<std::uint64_t>::max():(std::uint64_t{1}<<width)-1;
}
std::uint64_t encoded(const Value& v,unsigned width){
    const auto n=v.unsigned_integer();require(n<=maximum_raw(width),"Encoded value outside field width");return n;
}
Bits parse_bits(const Value& v,unsigned payload){
    json::fields(v,{"offset","width","order"});
    Bits b{};b.offset=bounded(v.at("offset"),0,payload*8-1);b.width=bounded(v.at("width"),1,64);
    require(b.offset+b.width<=payload*8,"Signal outside payload");
    const auto& order=v.at("order").string();
    require(order=="MSB0"||order=="LE_BYTES","Unsupported bit order");
    b.order=order=="MSB0"?Order::msb0:Order::le_bytes;
    require(b.order!=Order::le_bytes||(!(b.offset%8)&&!(b.width%8)),"Little-endian fields must use complete bytes");
    return b;
}
Signal signal(const Value& v,unsigned payload){
    json::fields(v,{"name","unit","bits","signed","scale","offset","reference"},{"unavailable","presence","range","enumeration"});
    Signal s{};s.name=label(v.at("name"));s.unit=label(v.at("unit"),64);s.reference=label(v.at("reference"));
    s.bits=parse_bits(v.at("bits"),payload);s.signed_value=v.at("signed").boolean();
    s.scale=ratio(v.at("scale"));s.offset=ratio(v.at("offset"));
    if(const auto* list=v.find("unavailable")){
        require(list->array().size()<=256,"Too many unavailable values");
        for(const auto& item:list->array())require(s.unavailable.insert(encoded(item,s.bits.width)).second,"Duplicate unavailable value");
    }
    if(const auto* list=v.find("enumeration")){
        require(list->array().size()<=256,"Too many enumeration values");
        for(const auto& item:list->array()){
            json::fields(item,{"raw","label"});
            auto raw=encoded(item.at("raw"),s.bits.width);
            require(!s.unavailable.contains(raw)&&s.enumeration.emplace(raw,label(item.at("label"))).second,"Overlapping or duplicate enumeration value");
        }
    }
    if(const auto* presence=v.find("presence")){
        json::fields(*presence,{"bits","mask","equals"});
        auto bits=parse_bits(presence->at("bits"),payload);
        auto mask=encoded(presence->at("mask"),bits.width),equals=encoded(presence->at("equals"),bits.width);
        require(mask&&!(equals&~mask),"Invalid presence condition");
        s.presence=Presence{bits,mask,equals};
    }
    if(const auto* range=v.find("range");range&&!range->null()){
        require(range->array().size()==2,"Invalid engineering range");
        s.minimum=ratio(range->array()[0]);s.maximum=ratio(range->array()[1]);
        require(compare(*s.minimum,*s.maximum)<=0,"Inverted engineering range");
    }
    if(s.enumeration.empty())require(!s.scale.zero(),"Zero numeric scale");
    else require(!s.signed_value&&s.scale.one()&&s.offset.zero()&&!s.minimum,"Enumeration cannot imply numeric scaling");
    return s;
}
Definition definition(const Value& v,std::optional<unsigned> frame){
    json::fields(v,{"pid","payload_bytes","protocols","reference","signals"});
    Definition d{};d.pid=bounded(v.at("pid"),1,255);d.width=bounded(v.at("payload_bytes"),1,frame?4092:4093);
    require(d.pid%32!=0&&(!frame||d.pid!=2),"Support page or freeze trigger cannot be catalogued as measurement");
    d.frame=frame;d.reference=label(v.at("reference"));
    const auto& protocols=v.at("protocols").array();
    require(!protocols.empty()&&protocols.size()<=2,"Invalid protocol scope");
    for(const auto& value:protocols){
        const auto& name=value.string();require(name=="CAN"||name=="LEGACY","Unknown protocol scope");
        auto transport=name=="CAN"?Transport::can:Transport::legacy;
        require(std::find(d.protocols.begin(),d.protocols.end(),transport)==d.protocols.end(),"Duplicate protocol scope");
        require(transport!=Transport::legacy||(!frame&&d.width<=5),"Legacy payload exceeds application envelope");
        d.protocols.push_back(transport);
    }
    require(!frame||d.protocols==std::vector<Transport>{Transport::can},"Freeze definitions require CAN scope");
    const auto& signals=v.at("signals").array();
    require(!signals.empty()&&signals.size()<=64,"Invalid signal count");
    std::set<std::string> names;
    for(const auto& row:signals){
        auto s=signal(row,d.width);require(names.insert(s.name).second,"Duplicate signal name");d.signals.push_back(std::move(s));
    }
    return d;
}
}
bool valid_digest(std::string_view s){
    return s.size()==64&&std::all_of(s.begin(),s.end(),[](char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f');});
}
std::uint64_t Bits::extract(Bytes bytes)const{
    if(width<1||width>64||offset>bytes.size()*8||width>bytes.size()*8-offset||
        (order!=Order::msb0&&order!=Order::le_bytes)||
        (order==Order::le_bytes&&(offset%8||width%8)))throw std::invalid_argument("Invalid signal bit extraction");
    std::uint64_t v=0;
    if(order==Order::le_bytes){
        for(unsigned i=0;i<width/8;++i)v|=std::uint64_t(bytes[offset/8+i])<<(i*8);
    }else for(unsigned i=0;i<width;++i){
        const unsigned pos=offset+i;v=(v<<1)|((bytes[pos/8]>>(7-pos%8))&1);
    }
    return v;
}
Catalogue parse_catalogue(std::string_view text,Mode mode,std::string_view pin){
    require(valid_digest(pin),"Independent reference SHA-256 required");
    const auto root=json::parse(text,max_catalogue);
    if(mode==Mode::live)json::fields(root,{"schema","edition","reference_name","reference_sha256","definitions"});
    else if(mode==Mode::freeze)json::fields(root,{"schema","edition","reference_name","reference_sha256","frames"});
    else throw std::invalid_argument("Invalid catalogue mode");
    require(root.at("schema").string()==(mode==Mode::live?"camarodiag-j1979-pid-catalogue-v1":"camarodiag-j1979-freeze-catalogue-v1"),
        "Catalogue mode/schema mismatch");
    Catalogue c{};c.mode=mode;c.edition=label(root.at("edition"));c.reference_name=label(root.at("reference_name"));
    c.reference_sha256=root.at("reference_sha256").string();require(c.reference_sha256==pin,"Reference pin mismatch");
    std::set<std::tuple<unsigned,Transport,std::optional<unsigned>>> seen;
    auto definitions=[&](const Value& rows,std::optional<unsigned> frame){
        require(!rows.array().empty()&&rows.array().size()<=247,"Invalid definition count");
        for(const auto& row:rows.array()){
            auto d=definition(row,frame);
            for(auto protocol:d.protocols)require(seen.emplace(d.pid,protocol,frame).second,"Ambiguous PID/protocol/frame definition");
            c.definitions.push_back(std::move(d));
        }
    };
    if(mode==Mode::live)definitions(root.at("definitions"),{});
    else{
        const auto& frames=root.at("frames").array();
        require(!frames.empty()&&frames.size()<=8,"Invalid freeze frame catalogue count");
        std::set<unsigned> used;
        for(const auto& frame:frames){
            json::fields(frame,{"frame","definitions"});
            const auto n=bounded(frame.at("frame"),0,255);
            require(used.insert(n).second,"Duplicate catalogue frame");definitions(frame.at("definitions"),n);
        }
    }
    return c;
}
const Definition* Catalogue::find(unsigned pid,Transport transport,std::optional<unsigned> frame)const{
    if((mode==Mode::live&&frame)||(mode==Mode::freeze&&!frame))return nullptr;
    const auto it=std::find_if(definitions.begin(),definitions.end(),[&](const Definition& d){
        return d.pid==pid&&d.frame==frame&&std::find(d.protocols.begin(),d.protocols.end(),transport)!=d.protocols.end();
    });
    return it==definitions.end()?nullptr:&*it;
}
SignalResult project_signal(const Signal& s,Bytes bytes){
    SignalResult r{};r.name=s.name;r.unit=s.unit;r.reference=s.reference;
    if(s.presence&&(s.presence->bits.extract(bytes)&s.presence->mask)!=s.presence->equals){
        r.status="NOT_PRESENT";return r;
    }
    const auto raw=s.bits.extract(bytes);r.raw=std::to_string(raw);
    if(s.unavailable.contains(raw)){r.status="UNAVAILABLE";return r;}
    if(!s.enumeration.empty()){
        auto it=s.enumeration.find(raw);
        if(it==s.enumeration.end())r.status="UNDEFINED_ENUMERATION";
        else{r.status="ENUMERATED";r.enumeration=it->second;}
        return r;
    }
    r.value=exact_value(raw,s.bits.width,s.signed_value,s.scale,s.offset,s.minimum,s.maximum);
    r.status=r.value->outside?"OUTSIDE_DECLARED_RANGE":"DECODED";return r;
}
}
