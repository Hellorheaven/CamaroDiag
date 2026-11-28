#include "j1979_live_options.hpp"
#include "j1979_desktop_commands.hpp"
#include <algorithm>
#include <cassert>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <set>
#include <stdexcept>
#include <unistd.h>
using namespace cd::desktop;
namespace {
const Operation& find(std::string_view id){
    const auto& all=operations();
    const auto p=std::find_if(all.begin(),all.end(),[&](const auto& o){return o.id==id;});
    assert(p!=all.end());return *p;
}
template<class F>void rejects(F f){bool denied=false;try{f();}catch(const std::invalid_argument&){denied=true;}assert(denied);}
}
int main(){
    for(const auto* name:{"can-live","elm-live","legacy-live"}){
        const auto& fields=find(name).fields;
        const auto quiet=std::find_if(fields.begin(),fields.end(),[](const auto& field){return field.key=="quiet";});
        assert(quiet!=fields.end()&&quiet->flag=="--quiet"&&!quiet->required);
    }

    unsigned milliseconds=99;
    assert(cd::liveoptions::seconds_ms("1.125",milliseconds)&&milliseconds==1125);
    assert(cd::liveoptions::seconds_ms("0.1000",milliseconds)&&milliseconds==100);
    assert(cd::liveoptions::seconds_ms("600",milliseconds)&&milliseconds==600000);
    for(auto invalid:{"0","600.001","1.0001","NaN","-1","1e2","1,5","1.","999999999999",""})
        assert(!cd::liveoptions::seconds_ms(invalid,milliseconds));

    char name[]="/tmp/camarodiag-desktop-XXXXXX";
    assert(mkdtemp(name));
    const std::string directory=name;
    Values values{{"interface","can0"},{"tx","7E0"},{"rx","7E8"},{"vin","1G1FA1RXXR0123456"},
        {"pids","0C 0D"},{"cycles","30"},{"interval","1000"},{"journal",directory+"/session.scj"},{"output",directory+"/report.json"}};
    auto ready=prepare(find("can-live"),values);
    assert(ready.physical&&ready.args.front()=="--j1979-live"&&ready.args.back()=="--confirm-physical-channel");
    assert(std::count(ready.args.begin(),ready.args.end(),"--pid")==2);
    assert(preview(ready).find("'--j1979-live'")!=std::string::npos);
    auto bad=values;bad["tx"]="7DF";rejects([&]{(void)prepare(find("can-live"),bad);});
    bad=values;bad["pids"]="0C 0C";rejects([&]{(void)prepare(find("can-live"),bad);});
    bad=values;bad["pids"]="00";rejects([&]{(void)prepare(find("can-live"),bad);});
    bad=values;bad["vin"]="invalid";rejects([&]{(void)prepare(find("can-live"),bad);});
    bad=values;bad["output"]=bad["journal"];rejects([&]{(void)prepare(find("can-live"),bad);});
    bad=values;bad["write"]="04";rejects([&]{(void)prepare(find("can-live"),bad);});
    bad=values;bad["journal"]="";rejects([&]{(void)prepare(find("can-live"),bad);});
    bad=values;bad["output"]=directory;rejects([&]{(void)prepare(find("can-live"),bad);});
    auto numeric=values;
    numeric["numeric-output"]=directory+"/numeric.json";numeric["csv-output"]=directory+"/samples.csv";
    ready=prepare(find("can-live"),numeric);
    assert(std::count(ready.args.begin(),ready.args.end(),"--projection-output")==1);
    assert(std::count(ready.args.begin(),ready.args.end(),"--csv")==1);
    numeric["csv-output"]=numeric["journal"];rejects([&]{(void)prepare(find("can-live"),numeric);});
    auto manipulated=find("can-live");manipulated.command="--j1979-maintenance-can";
    assert(prepare(manipulated,values).args.front()=="--j1979-live");
    Values network{{"interface","can0"},{"targets","7E8 7E9"},{"vin",values["vin"]},
        {"directory",directory+"/journals"},{"output",directory+"/network.json"}};
    ready=prepare(find("can-network"),network);
    assert(ready.args.back()=="--confirm-functional-scope"&&std::count(ready.args.begin(),ready.args.end(),"--rx")==2);
    network["targets"]="7E8 18DAF110";rejects([&]{(void)prepare(find("can-network"),network);});
    Values offline{{"journal",directory+"/absent.scj"},{"tx","7E0"},{"rx","7E8"},{"vin",values["vin"]},{"output",directory+"/proof.json"}};
    rejects([&]{(void)prepare(find("inspect-scj"),offline);});
    Values freeze{{"interface","can0"},{"tx","7E0"},{"rx","7E8"},{"vin",values["vin"]},
        {"frames","0"},{"pids","C"},{"capture",directory+"/freeze.cf"},{"output",directory+"/freeze.json"}};
    ready=prepare(find("can-freeze"),freeze);
    assert(std::find(ready.args.begin(),ready.args.end(),"00")!=ready.args.end());
    assert(std::find(ready.args.begin(),ready.args.end(),"0C")!=ready.args.end());
    freeze["pids"]="02";rejects([&]{(void)prepare(find("can-freeze"),freeze);});
    freeze["pids"]="0C";freeze["tx"]="18DA10F1";freeze["rx"]="18DAF110";
    rejects([&]{(void)prepare(find("can-freeze"),freeze);});
    freeze["extended"]="true";assert(prepare(find("can-freeze"),freeze).physical);
    // Every command ID and CLI command is unique; every invocation has a destination.
    std::set<std::string> operation_ids,commands;
    for(const auto& op:operations()){
        assert(operation_ids.insert(op.id).second);
        assert(commands.insert(op.command).second);
        std::set<std::string> fields;
        for(const auto& field:op.fields)assert(fields.insert(field.key).second);
        assert(fields.contains("output"));
        if(!op.physical)assert(op.confirmation.empty());
    }
    const auto capture=directory+"/native.cl";
    const auto report=directory+"/native.json";
    const auto second=directory+"/second.json";
    for(const auto& path:{capture,report,second}){std::ofstream file(path);file<<"fixture";}
    const auto proof=directory+"/verified.json";
    Values verify{{"capture",capture},{"report",report},{"output",proof},{"vin",values["vin"]}};
    ready=prepare(find("verify-native-live"),verify);
    assert(!ready.physical&&ready.confirmation.empty());
    assert((ready.args==std::vector<std::string>{"--verify-j1979-live-native",capture,report,proof,values["vin"]}));
    const auto normative=directory+"/reference.pdf",catalogue=directory+"/catalogue.json";
    for(const auto& path:{normative,catalogue}){std::ofstream file(path);file<<"synthetic fixture";}
    const std::string pin(64,'a');
    Values cat{{"catalogue-kind","live"},{"catalogue",catalogue},{"reference",normative},{"pin",pin},{"output",proof}};
    assert((prepare(find("inspect-catalogue"),cat).args==std::vector<std::string>{
        "--inspect-j1979-catalogue","live",catalogue,normative,pin,proof}));
    cat["catalogue-kind"]="cl1";cat["capture"]=capture;cat["vin"]=values["vin"];
    assert((prepare(find("project-catalogue"),cat).args==std::vector<std::string>{
        "--project-j1979-catalogue","cl1",catalogue,normative,pin,capture,values["vin"],proof}));
    cat["projection"]=report;
    assert((prepare(find("verify-catalogue"),cat).args==std::vector<std::string>{
        "--verify-j1979-catalogue","cl1",catalogue,normative,pin,capture,values["vin"],report,proof}));
    cat["catalogue-kind"]="llr1";
    rejects([&]{(void)prepare(find("verify-catalogue"),cat);});
    cat["protocol"]="2";cat["source"]="10";
    const auto legacy=prepare(find("verify-catalogue"),cat);
    assert(legacy.args[legacy.args.size()-2]=="2"&&legacy.args.back()=="10"&&!legacy.physical);
    cat["catalogue-kind"]="cf1";rejects([&]{(void)prepare(find("verify-catalogue"),cat);});
    cat.erase("protocol");cat.erase("source");cat["pin"]=std::string(64,'G');
    rejects([&]{(void)prepare(find("verify-catalogue"),cat);});
    cat["pin"]=pin;cat["reference"]=catalogue;
    rejects([&]{(void)prepare(find("verify-catalogue"),cat);});
    Values live_evidence{{"scj",capture},{"tx","7E0"},{"rx","7E8"},{"vin",values["vin"]},{"output",proof}};
    assert(prepare(find("project-live-evidence"),live_evidence).args.front()=="project-live-evidence");
    assert(prepare(find("csv-live-evidence"),live_evidence).args.front()=="export-live-evidence-csv");
    live_evidence["profile"]="6";
    rejects([&]{(void)prepare(find("project-live-evidence"),live_evidence);});
    live_evidence.erase("scj");live_evidence["ecp"]=capture;
    assert(prepare(find("project-live-evidence"),live_evidence).args.front()=="project-live-evidence");
    live_evidence["projection"]=report;
    assert(prepare(find("verify-live-evidence"),live_evidence).args.front()=="verify-live-evidence");
    live_evidence["cl1"]=second;rejects([&]{(void)prepare(find("verify-live-evidence"),live_evidence);});
    Values legacy_lj{{"journal",capture},{"protocol","2"},{"source","10"},{"vin",values["vin"]},{"output",proof}};
    assert((prepare(find("project-legacy-live-journal"),legacy_lj).args==std::vector<std::string>{
        "--project-j1979-legacy-live-journal","2","10",values["vin"],capture,proof}));
    assert(prepare(find("csv-legacy-live-journal"),legacy_lj).args.front()=="--export-j1979-legacy-live-journal-csv");
    rejects([&]{(void)prepare(find("verify-legacy-live-journal"),legacy_lj);});
    legacy_lj["report"]=report;
    assert(prepare(find("verify-legacy-live-journal"),legacy_lj).args.front()=="--verify-j1979-legacy-live-journal");
    Values usb_live{{"port","/dev/ttyACM0"},{"key",report},{"tx","7E0"},{"rx","7E8"},{"vin",values["vin"]},
        {"pids","0C"},{"capture",directory+"/new.cl"},{"output",directory+"/usb.json"},
        {"numeric-output",directory+"/usb-numeric.json"},{"csv-output",directory+"/usb.csv"}};
    assert(prepare(find("usb-live"),usb_live).physical);
    usb_live["csv-output"]=usb_live["key"];
    rejects([&]{(void)prepare(find("usb-live"),usb_live);});
    Values usb_numeric{{"capture",capture},{"tx","7E0"},{"rx","7E8"},{"vin",values["vin"]},{"output",proof}};
    auto usb_inv=prepare(find("project-cl1"),usb_numeric);
    assert(!usb_inv.physical&&usb_inv.args.front()=="project-cl1-live");
    assert(prepare(find("csv-cl1"),usb_numeric).args.front()=="export-cl1-live-csv");
    rejects([&]{(void)prepare(find("verify-cl1-projection"),usb_numeric);});
    usb_numeric["projection"]=report;
    assert(prepare(find("verify-cl1-projection"),usb_numeric).args.front()=="verify-cl1-live-projection");
    usb_numeric.erase("projection");
    assert(prepare(find("project-cl1-scalar"),usb_numeric).args.front()=="project-cl1-scalar");
    assert(prepare(find("csv-cl1-scalar"),usb_numeric).args.front()=="export-cl1-scalar-csv");
    rejects([&]{(void)prepare(find("verify-cl1-scalar"),usb_numeric);});
    usb_numeric["projection"]=report;
    assert(prepare(find("verify-cl1-scalar"),usb_numeric).args.front()=="verify-cl1-scalar");
    usb_numeric["rx"]="7E9";rejects([&]{(void)prepare(find("verify-cl1-projection"),usb_numeric);});
    auto invalid=verify;invalid["output"]=report;
    rejects([&]{(void)prepare(find("verify-native-live"),invalid);});
    invalid=verify;invalid["capture"]=directory+"/missing.cl";
    rejects([&]{(void)prepare(find("verify-native-live"),invalid);});
    invalid=verify;invalid["vin"]="--confirm-physical-channel";
    rejects([&]{(void)prepare(find("verify-native-live"),invalid);});
    invalid=verify;invalid["confirm"]="true";
    rejects([&]{(void)prepare(find("verify-native-live"),invalid);});
    Values compare{{"report",report},{"second-report",second},{"tx","7E0"},{"rx","7E8"},{"vin",values["vin"]},{"output",proof}};
    ready=prepare(find("compare-native-live"),compare);
    assert(std::count(ready.args.begin(),ready.args.end(),"--report")==2);
    assert(ready.args.front()=="compare-native-live"&&!ready.physical);
    compare["second-report"]=report;
    rejects([&]{(void)prepare(find("compare-native-live"),compare);});
    compare.erase("second-report");
    rejects([&]{(void)prepare(find("compare-native-live"),compare);});
    compare["cl1"]=capture;
    ready=prepare(find("compare-native-live"),compare);
    assert(std::count(ready.args.begin(),ready.args.end(),"--cl1")==1);
    assert(std::count(ready.args.begin(),ready.args.end(),"--report")==1);
    compare["second-cl1"]=capture;
    rejects([&]{(void)prepare(find("compare-native-live"),compare);});
    compare.erase("second-cl1");compare.erase("report");compare["second-cl1"]=second;
    ready=prepare(find("compare-native-live"),compare);
    assert(std::count(ready.args.begin(),ready.args.end(),"--cl1")==2);
    compare.erase("second-cl1");compare["scj"]=second;
    ready=prepare(find("compare-native-live"),compare);
    assert(std::count(ready.args.begin(),ready.args.end(),"--scj")==1);
    compare.erase("scj");compare["ecp"]=second;
    rejects([&]{(void)prepare(find("compare-native-live"),compare);});
    compare["profile"]="6";ready=prepare(find("compare-native-live"),compare);
    assert(std::count(ready.args.begin(),ready.args.end(),"--ecp")==1);
    Values legacy{{"protocol","2"},{"source","10"},{"vin",values["vin"]},{"capture",capture},{"report",report},{"output",proof}};
    ready=prepare(find("verify-legacy-live-capture"),legacy);
    assert((ready.args==std::vector<std::string>{"--verify-j1979-legacy-live","2","10",values["vin"],capture,report,proof}));
    legacy["source"]="F1";rejects([&]{(void)prepare(find("verify-legacy-live-capture"),legacy);});
    Values mutation{{"report",report},{"vin",values["vin"]},{"output",proof}};
    ready=prepare(find("verify-can-mutation"),mutation);
    assert(!ready.physical&&std::count(ready.args.begin(),ready.args.end(),"--baseline")==0);
    mutation["baseline"]=second;ready=prepare(find("verify-can-mutation"),mutation);
    assert(std::count(ready.args.begin(),ready.args.end(),"--baseline")==1);
    Values elm{{"port","/dev/ttyUSB0"},{"profile","6"},{"tx","7E0"},{"rx","7E8"},{"vin",values["vin"]},
        {"adapter-capture",directory+"/adapter.ecr"},{"adapter",directory+"/adapter.ejr"},{"campaign",directory+"/campaign.ecp"},{"output",proof},{"baud","38400"}};
    ready=prepare(find("elm-scan"),elm);
    assert(std::count(ready.args.begin(),ready.args.end(),"--adapter-capture")==1);
    assert(std::count(ready.args.begin(),ready.args.end(),"--baud")==1);
    auto elm_live=elm;elm_live["pids"]="0C 0D";elm_live["numeric-output"]=directory+"/numeric.json";
    rejects([&]{(void)prepare(find("elm-live"),elm_live);});
    elm_live["native-output"]=directory+"/raw.json";elm_live["csv-output"]=directory+"/samples.csv";
    assert(prepare(find("elm-live"),elm_live).physical);
    auto without=elm_live;without.erase("campaign");rejects([&]{(void)prepare(find("elm-live"),without);});
    without=elm_live;without.erase("adapter");rejects([&]{(void)prepare(find("elm-live"),without);});
    elm["profile"]="7";rejects([&]{(void)prepare(find("elm-scan"),elm);});
    elm["tx"]="18DA10F1";elm["rx"]="18DAF110";assert(prepare(find("elm-scan"),elm).physical);
    elm["adapter-capture"]=elm["adapter"];rejects([&]{(void)prepare(find("elm-scan"),elm);});
    Values legacy_read{{"port","/dev/ttyUSB0"},{"protocol","2"},{"source","10"},{"vin",values["vin"]},
        {"capture",directory+"/legacy.lsr"},{"output",directory+"/legacy.json"}};
    assert(prepare(find("legacy-scan"),legacy_read).physical);
    auto legacy_live=legacy_read;
    legacy_live["pids"]="0C";legacy_live["csv-output"]=directory+"/legacy.csv";
    ready=prepare(find("legacy-live"),legacy_live);
    assert(std::count(ready.args.begin(),ready.args.end(),"--csv")==1);
    legacy_live["csv-output"]=legacy_live["capture"]+".lj";
    rejects([&]{(void)prepare(find("legacy-live"),legacy_live);});
    legacy_live["csv-output"]=legacy_live["output"];
    rejects([&]{(void)prepare(find("legacy-live"),legacy_live);});
    auto legacy_collision=legacy_read;legacy_collision["output"]=legacy_read["capture"]+".lj";
    rejects([&]{(void)prepare(find("legacy-scan"),legacy_collision);});
    const auto sidecar=legacy_read["capture"]+".lj";
    {std::ofstream file(sidecar);file<<"existing";}
    rejects([&]{(void)prepare(find("legacy-scan"),legacy_read);});
    assert(std::filesystem::remove(sidecar));
    assert(symlink((directory+"/missing").c_str(),sidecar.c_str())==0);
    rejects([&]{(void)prepare(find("legacy-scan"),legacy_read);});
    assert(unlink(sidecar.c_str())==0);
    for(const auto& path:{capture,report,second})assert(std::filesystem::remove(path));
    assert(rmdir(name)==0);
}
