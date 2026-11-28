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
