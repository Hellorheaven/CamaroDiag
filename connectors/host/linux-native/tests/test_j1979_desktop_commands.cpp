#include "j1979_desktop_commands.hpp"
#include <algorithm>
#include <cassert>
#include <cstdlib>
#include <filesystem>
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
    assert(rmdir(name)==0);
}
