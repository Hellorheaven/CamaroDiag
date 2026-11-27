#include "j1979_context.hpp"
#include <algorithm>
#include <array>
#include <bitset>
#include <limits>

namespace cd::j1979 {
namespace {
constexpr std::array<unsigned, 2> config_ids{0x4f, 0x50};
constexpr std::array<unsigned, 8> trim_ids{6,7,8,9,0x55,0x56,0x57,0x58};
constexpr std::array<std::string_view, 3> continuous{"misfire","fuel-system","components"};
constexpr std::array<std::string_view, 8> spark{
    "catalyst","heated-catalyst","evaporative-system","secondary-air","",
    "oxygen-sensor","oxygen-sensor-heater","egr-vvt"};
constexpr std::array<std::string_view, 8> diesel{
    "nmhc-catalyst","nox-aftertreatment","","boost-pressure","",
    "exhaust-gas-sensor","particulate-filter","egr-vvt"};

struct Candidate {
    unsigned count{};
    std::optional<Bytes> data;
    void observe(std::optional<Bytes> response) {
        if (count == 0) data = response;
        else data.reset();
        if (count < 2) ++count;
    }
    [[nodiscard]] std::optional<Bytes> unique() const { return count == 1 ? data : std::nullopt; }
};

std::optional<std::array<std::uint8_t, 4>> four(std::optional<Bytes> value) {
    if (!value || value->size() != 4) return {};
    std::array<std::uint8_t, 4> data{};
    std::copy(value->begin(), value->end(), data.begin());
    return data;
}

std::string_view range_state(RangeState state) {
    switch (state) {
    case RangeState::ecu_configuration: return "ECU_CONFIGURATION";
    case RangeState::default_unsupported: return "DEFAULT_UNSUPPORTED";
    default: return "UNVERIFIED";
    }
}
std::string_view layout_state(LayoutState state) {
    switch (state) {
    case LayoutState::verified: return "VERIFIED";
    case LayoutState::not_advertised: return "NOT_ADVERTISED_BY_SELECTED_ECU";
    default: return "UNVERIFIED";
    }
}

bool text(std::FILE* f, std::string_view value) {
    if (std::fputc('"',f)==EOF) return false;
    for (unsigned char c : value) {
        if (c < 32 || c == 127) return false;
        if ((c=='"' || c=='\\') && std::fputc('\\',f)==EOF) return false;
        if (std::fputc(c,f)==EOF) return false;
    }
    return std::fputc('"',f)!=EOF;
}
bool literal(std::FILE* f, const char* s) { return std::fputs(s,f)!=EOF; }
bool boolean(std::FILE* f, std::optional<bool> value) {
    return literal(f, value ? (*value ? "true" : "false") : "null");
}
bool number(std::FILE* f, std::optional<double> value) {
    return value ? std::fprintf(f,"%.12g",*value)>=0 : literal(f,"null");
}
bool hex(std::FILE* f, Bytes data) {
    constexpr char alphabet[]="0123456789ABCDEF";
    if (std::fputc('"',f)==EOF) return false;
    for (const auto b:data)
        if (std::fputc(alphabet[b>>4],f)==EOF || std::fputc(alphabet[b&15],f)==EOF) return false;
    return std::fputc('"',f)!=EOF;
}
bool comma(std::FILE* f, bool& first) {
    if (first) { first=false; return true; }
    return std::fputc(',',f)!=EOF;
}
bool config_json(std::FILE* f, unsigned pid, const Range& r) {
    if (std::fprintf(f,"{\"pid\":%u,\"state\":",pid)<0 || !text(f,range_state(r.state)) ||
        !literal(f,",\"data_hex\":")) return false;
    if (r.state==RangeState::ecu_configuration ? !hex(f,r.data) : !literal(f,"null")) return false;
    return literal(f,"}");
}
bool signal_json(std::FILE* f, const Signal& s) {
    return literal(f,"{\"name\":") && text(f,s.name) && literal(f,",\"unit\":") && text(f,s.unit) &&
        std::fprintf(f,",\"raw_encoded\":%u,\"number\":",s.raw)>=0 && number(f,s.number) &&
        literal(f,",\"scale_source\":") && text(f,s.scale_source) && literal(f,"}");
}
bool trim_signal_json(std::FILE* f, const TrimSignal& s) {
    return std::fprintf(f,"{\"bank\":%u,\"raw_encoded\":%u,\"name\":",s.bank,s.raw)>=0 &&
        text(f,s.name) && literal(f,",\"number\":") && number(f,s.number) &&
        literal(f,",\"unit\":\"%\",\"attribution\":") &&
        text(f,s.number ? "DEFINED" : "UNVERIFIED_UPPER_BANK") && literal(f,"}");
}
bool cycle_json(std::FILE* f, const std::optional<DriveCycle>& c,
                const std::optional<std::array<std::uint8_t,4>>& baseline) {
    if (!c) return literal(f,"null");
    if (!literal(f,"{\"request_hex\":\"0141\",\"raw_hex\":") || !hex(f,c->raw) ||
        !literal(f,",\"baseline_raw_hex\":") ||
        (baseline ? !hex(f,*baseline) : !literal(f,"null")) ||
        !literal(f,",\"support_verified\":") || !boolean(f,c->support_verified) ||
        !literal(f,",\"compression_ignition\":") || !boolean(f,c->compression_ignition) ||
        !literal(f,",\"consistent\":") || !boolean(f,c->consistent) || !literal(f,",\"monitors\":[")) return false;
    bool first=true;
    for (const auto& m:c->monitors) {
        if (!comma(f,first) || !literal(f,"{\"name\":") || !text(f,m.name) ||
            !literal(f,",\"supported\":") || !boolean(f,m.supported) ||
            !literal(f,",\"enabled\":") || !boolean(f,m.enabled) ||
            !literal(f,",\"complete\":") || !boolean(f,m.complete) ||
            !literal(f,",\"enabled_bit\":") || !boolean(f,m.enabled_bit) ||
            !literal(f,",\"incomplete_bit\":") || !boolean(f,m.incomplete_bit) ||
            !literal(f,",\"consistent\":") || !boolean(f,m.consistent) || !literal(f,"}")) return false;
    }
    return literal(f,"]}");
}
}

bool trim_pid(unsigned pid) { return std::find(trim_ids.begin(),trim_ids.end(),pid)!=trim_ids.end(); }
std::optional<unsigned> dependency(unsigned pid) {
    if (pid==0x10) return 0x50;
    if (pid==0x0b || pid==0x44 || (pid>=0x24 && pid<=0x2b) || (pid>=0x34 && pid<=0x3b)) return 0x4f;
    return {};
}

std::optional<Bytes> payload(const Observation& o) {
    if (!o.accepted || o.request.size()<2 || o.response.size()<=o.request.size()) return {};
    const auto mode=o.request[0];
    if ((mode!=1 && mode!=2) || o.request.size()!=(mode==1?2u:3u) ||
        (mode==2 && o.request[2]!=0) || o.response[0]!=mode+0x40 ||
        !std::equal(o.request.begin()+1,o.request.end(),o.response.begin()+1)) return {};
    return o.response.subspan(o.request.size());
}

Context derive_context(std::span<const Observation> observations) {
    std::array<Candidate,8> pages{};
    std::array<Candidate,2> configs{};
    Candidate layout{}, baseline{}, cycle{};
    for (const auto& o:observations) {
        if (o.request.size()!=2 || o.request[0]!=1) continue;
        const unsigned pid=o.request[1];
        const auto data=payload(o);
        if (o.admission) {
            if (pid%32==0) pages[pid/32].observe(data);
            else if (pid==config_ids[0]) configs[0].observe(data);
            else if (pid==config_ids[1]) configs[1].observe(data);
            else if (pid==0x1d) layout.observe(data);
            else if (pid==1) baseline.observe(data);
        }
        if (pid==0x41) cycle.observe(data);
    }
    std::bitset<256> advertised;
    std::bitset<8> seen;
    bool ended=false;
    for (unsigned page=0;page<pages.size();++page) {
        auto data=four(pages[page].unique());
        if (!data || (page==7 && ((*data)[3]&1))) break;
        seen.set(page);
        for (unsigned i=0;i<31;++i)
            if ((*data)[i/8]&(0x80u>>(i%8))) advertised.set(page*32+i+1);
        if (!((*data)[3]&1)) { ended=true; break; }
    }
    auto resolve=[&](unsigned index) {
        const unsigned pid=config_ids[index];
        const Candidate& candidate=configs[index];
        if (!advertised.test(pid)) {
            if (candidate.count==0 && (ended || seen.test((pid-1)/32)))
                return Range{RangeState::default_unsupported,{}};
            return Range{};
        }
        auto data=four(candidate.unique());
        if (!data || (pid==0x50 && ((*data)[1]||(*data)[2]||(*data)[3]))) return Range{};
        return Range{RangeState::ecu_configuration,*data};
    };
    Context result{};
    result.scaling={resolve(0),resolve(1)};
    const auto page0=four(pages[0].unique());
    if (page0) {
        const auto layout_data=layout.unique();
        if (((*page0)[3]&0x08)==0) {
            if (layout.count==0) result.layout={LayoutState::not_advertised,{}};
        } else if (layout_data && layout_data->size()==1) {
            result.layout={LayoutState::verified,(*layout_data)[0]};
        }
    }
    result.baseline=four(baseline.unique());
    if (const auto data=cycle.unique()) {
        std::optional<Bytes> base;
        if (result.baseline) base=Bytes(*result.baseline);
        result.cycle=drive_cycle(*data,base);
    }
    return result;
}

std::optional<ScalingRecord> scale(const Observation& o, const ScalingContext& ctx) {
    const auto data=payload(o);
    if (!data) return {};
    const unsigned pid=o.request[1];
    const auto dep=dependency(pid);
    if (!dep) return {};
    const auto width=pid==0x0b?1u:(pid==0x10||pid==0x44)?2u:4u;
    if (data->size()!=width) return {};
    const auto& range=*dep==0x4f?ctx.pid4f:ctx.pid50;
    const unsigned a=width==1?(*data)[0]:(unsigned((*data)[0])<<8)|(*data)[1];
    ScalingRecord result{*dep,{}};
    const auto field=[&](std::string name, std::string unit, unsigned raw, unsigned index,
                         double default_scale, double custom_scale, int offset=0, bool signed_custom=false) {
        const bool verified=range.state!=RangeState::unverified;
        const unsigned custom=range.state==RangeState::ecu_configuration?range.data[index]:0;
        std::optional<double> value;
        if (verified) {
            const int encoded=static_cast<int>(raw);
            const int signed_value=encoded>=32768?encoded-65536:encoded;
            value=custom && signed_custom ? signed_value*custom*custom_scale :
                (encoded-offset)*(custom?custom*custom_scale:default_scale);
        }
        result.signals.push_back({std::move(name),std::move(unit),raw,value,
            !verified?"UNVERIFIED":custom?"ECU_CONFIGURATION":"DEFAULT"});
    };
    if (pid==0x0b) field("intake-manifold-absolute-pressure","kPa",a,3,1.0,10.0/255);
    else if (pid==0x10) field("mass-air-flow","g/s",a,0,.01,10.0/65535);
    else if (pid==0x44) field("commanded-equivalence-ratio","ratio",a,0,1.0/32768,1.0/65535);
    else {
        const bool voltage=pid<0x34;
        const unsigned sensor=pid-(voltage?0x24u:0x34u)+1;
        const auto prefix="wideband-o2-sensor-"+std::to_string(sensor)+"-";
        const unsigned b=(unsigned((*data)[2])<<8)|(*data)[3];
        field(prefix+"equivalence-ratio","ratio",a,0,1.0/32768,1.0/65535);
        if (voltage) field(prefix+"voltage","V",b,1,1.0/8192,1.0/65535);
        else field(prefix+"current","mA",b,2,1.0/256,1.0/32768,32768,true);
    }
    return result;
}

std::optional<TrimRecord> trims(const Observation& o, const Layout& layout) {
    const auto data=payload(o);
    if (!data || !trim_pid(o.request[1]) || data->empty() || data->size()>2) return {};
    const unsigned pid=o.request[1];
    const bool secondary=pid>=0x55;
    const bool short_term=pid==6||pid==8||pid==0x55||pid==0x57;
    const unsigned bank=pid==6||pid==7||pid==0x55||pid==0x56?1u:2u;
    const bool upper=layout.state==LayoutState::verified && layout.value &&
        ((*layout.value&(bank==1?0x30:0xc0))!=0);
    TrimRecord result{};
    if (o.request[0]==2) result.frame=o.request[2];
    if (data->size()==2) result.upper_bank_verified=upper;
    const std::string prefix=std::string(short_term?"short":"long")+"-term-"+
        (secondary?"secondary-o2-":"")+"fuel-trim-bank-";
    for (unsigned i=0;i<data->size();++i) {
        const unsigned raw=(*data)[i], number_bank=bank+2*i;
        std::optional<double> value;
        if (i==0||upper) value=(static_cast<int>(raw)-128)*100.0/128;
        result.signals.push_back({number_bank,raw,prefix+std::to_string(number_bank),value});
    }
    return result;
}

std::optional<DriveCycle> drive_cycle(Bytes data,std::optional<Bytes> baseline) {
    if (data.size()!=4 || data[0]!=0 || (data[1]&0x80)) return {};
    const auto b=data[1],c=data[2],d=data[3];
    const bool compression=(b&8)!=0;
    const unsigned reserved=compression?0x14u:0x10u;
    if ((c|d)&reserved) return {};
    const bool valid=baseline && baseline->size()==4 && (((*baseline)[1]&8)!=0)==compression &&
        !((*baseline)[1]&0x80) && !(((*baseline)[2]|(*baseline)[3])&reserved);
    DriveCycle result{};
    std::copy(data.begin(),data.end(),result.raw.begin());
    result.support_verified=valid;
    result.compression_ignition=compression;
    result.consistent=true;
    auto add=[&](auto names,unsigned supported,unsigned enabled,unsigned incomplete) {
        for (unsigned i=0;i<names.size();++i) {
            if (names[i].empty()) continue;
            const unsigned mask=1u<<i;
            Monitor monitor{};
            monitor.name=names[i];
            monitor.enabled_bit=(enabled&mask)!=0;
            monitor.incomplete_bit=(incomplete&mask)!=0;
            if (valid) monitor.supported=(supported&mask)!=0;
            if (monitor.supported==true) {
                monitor.enabled=monitor.enabled_bit;
                monitor.complete=!monitor.incomplete_bit;
            }
            monitor.consistent=monitor.supported!=false || !(monitor.enabled_bit||monitor.incomplete_bit);
            result.consistent=result.consistent&&monitor.consistent;
            result.monitors.push_back(monitor);
        }
    };
    add(continuous,valid?(*baseline)[1]:0,b,b>>4);
    add(compression?diesel:spark,valid?(*baseline)[2]:0,c,d);
    return result;
}

bool write_context_json(std::FILE* f,std::span<const Observation> observations,bool identity_rechecked) {
    if (!f) return false;
    // Unverified identity cannot promote historic data to attributed engineering values.
    const Context context=identity_rechecked?derive_context(observations):Context{};
    if (!literal(f,"{\"identity_rechecked\":") || !boolean(f,identity_rechecked) ||
        !literal(f,",\"physical_vehicle_verified\":false,\"scaling_context\":{\"configuration\":[") ||
        !config_json(f,0x4f,context.scaling.pid4f) || !literal(f,",") ||
        !config_json(f,0x50,context.scaling.pid50) || !literal(f,"],\"records\":[")) return false;
    bool first=true;
    for (const auto& o:observations) {
        const auto record=scale(o,context.scaling);
        if (!record) continue;
        if (!comma(f,first) || !literal(f,"{\"request_hex\":") || !hex(f,o.request) ||
            std::fprintf(f,",\"configuration_pid\":%u,\"signals\":[",record->configuration_pid)<0) return false;
        bool first_signal=true;
        for (const auto& signal:record->signals)
            if (!comma(f,first_signal)||!signal_json(f,signal)) return false;
        if (!literal(f,"]}")) return false;
    }
    if (!literal(f,"]},\"trim_layout\":{\"state\":") || !text(f,layout_state(context.layout.state)) ||
        !literal(f,",\"raw_encoded\":")) return false;
    if (context.layout.value ? std::fprintf(f,"%u",unsigned(*context.layout.value))<0 : !literal(f,"null")) return false;
    if (!literal(f,"},\"drive_cycle\":") || !cycle_json(f,context.cycle,context.baseline) ||
        !literal(f,",\"fuel_trims\":[")) return false;
    first=true;
    for (const auto& o:observations) {
        auto record=trims(o,context.layout);
        if (!record) continue;
        if (!identity_rechecked) for (auto& signal:record->signals) signal.number.reset();
        if (!comma(f,first)||!literal(f,"{\"request_hex\":")||!hex(f,o.request)||!literal(f,",\"frame\":")) return false;
        if (record->frame ? std::fprintf(f,"%u",*record->frame)<0 : !literal(f,"null")) return false;
        if (!literal(f,",\"layout_1d\":")) return false;
        if (context.layout.value ? std::fprintf(f,"%u",unsigned(*context.layout.value))<0 : !literal(f,"null")) return false;
        if (!literal(f,",\"upper_bank_verified\":")||!boolean(f,record->upper_bank_verified)||
            !literal(f,",\"signals\":[")) return false;
        bool first_signal=true;
        for (const auto& signal:record->signals)
            if (!comma(f,first_signal)||!trim_signal_json(f,signal)) return false;
        if (!literal(f,"]}")) return false;
    }
    return literal(f,"]}");
}
}
