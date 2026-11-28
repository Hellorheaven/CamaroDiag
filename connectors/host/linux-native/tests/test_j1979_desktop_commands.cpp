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
    for(const auto& path:{capture,report,second})assert(std::filesystem::remove(path));
    assert(rmdir(name)==0);
}
