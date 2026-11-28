#include "j1979_desktop_commands.hpp"
#include "j1979_can.h"
#include "j1979_isotp.h"
#include <algorithm>
#include <charconv>
#include <filesystem>
#include <set>
#include <stdexcept>
namespace cd::desktop {
namespace {
using K=FieldKind;
void require(bool yes,std::string_view reason){if(!yes)throw std::invalid_argument(std::string(reason));}
Field f(std::string key,std::string label,std::string flag,K kind,std::string hint={},bool required=true,unsigned min=0,unsigned max=0){
    return {std::move(key),std::move(label),std::move(flag),std::move(hint),kind,required,min,max};
}
Field vin(){return f("vin","VIN attendu","--vin",K::vin,"17 caractères");}
Field tx(){return f("tx","CAN TX (hex)","--tx",K::can_id,"7E0");}
Field rx(){return f("rx","CAN RX (hex)","--rx",K::can_id,"7E8");}
Field interface(){return f("interface","Interface SocketCAN","--interface",K::text,"can0");}
Field port(){return f("port","Port série","--port",K::text,"/dev/ttyUSB0");}
Field profile(){return f("profile","Profil ELM CAN (6–9)","--profile",K::profile,"6");}
Field protocol(){return f("protocol","Protocole legacy (1–5)","--protocol",K::protocol,"2");}
Field source(){return f("source","Source legacy (hex, 2 chiffres)","--source",K::can_id,"10");}
Field output(){return f("output","Nouveau fichier de sortie","--output",K::output_file,"");}
Field report(){return f("report","Rapport source","--report",K::input_file,"");}
Field capture(bool physical){return f("capture",physical?"Nouvelle capture":"Capture source","--capture",physical?K::output_file:K::input_file,"");}
Field budget(unsigned initial=180000){return f("budget","Budget (ms)","--budget-ms",K::decimal,std::to_string(initial),false,1000,600000);}
Field cycles(){return f("cycles","Nombre de cycles","--cycles",K::decimal,"30",false,1,1000);}
Field interval(){return f("interval","Intervalle (ms)","--interval-ms",K::decimal,"1000",false,100,60000);}
Field pids(){return f("pids","PID hex séparés par espaces","--pid",K::pids,"0C 0D");}
Field frames(){return f("frames","Frames hex séparées par espaces","--frame",K::frames,"00");}
Field key(){return f("key","Fichier de clé local","--key-file",K::input_file,"");}
Field journal(bool physical){return f("journal",physical?"Nouveau journal SCJ":"Journal SCJ","--journal",physical?K::output_file:K::input_file,"");}
Field directory(bool physical){return f("directory",physical?"Nouveau répertoire de journaux":"Répertoire des journaux","--journal-directory",physical?K::new_directory:K::existing_directory,"");}
Field extended(){return f("extended","Adressage CAN 29 bits","--extended",K::check,"",false);}
Field raw(){return f("raw","Conserver uniquement les réponses brutes","--raw-only",K::check,"",false);}
Field window(){return f("window","Fenêtre découverte (ms)","--window-ms",K::decimal,"1500",false,1,30000);}
Field adapter_journal(){return f("adapter","Nouveau journal adaptateur EJR","--adapter-journal",K::output_file,"",false);}
Field campaign(){return f("campaign","Nouvelle capture ECP","--campaign-capture",K::output_file,"",false);}
Field projection(){return f("projection","Projection numérique","--projection",K::input_file,"");}
constexpr std::string_view physical="--confirm-physical-channel";
constexpr std::string_view functional="--confirm-functional-scope";
Operation op(std::string id,std::string title,std::string description,std::string cmd,
             std::vector<Field> fields,std::string_view consent={}){
    return {std::move(id),std::move(title),std::move(description),std::move(cmd),std::string(consent),!consent.empty(),std::move(fields)};
}
unsigned parse(std::string_view v,int base,unsigned min,unsigned max,std::string_view what){
    unsigned n=0;
    const auto [end,error]=std::from_chars(v.data(),v.data()+v.size(),n,base);
    require(!v.empty()&&error==std::errc{}&&end==v.data()+v.size()&&n>=min&&n<=max,what);
    return n;
}
std::vector<std::string> words(std::string_view v){
    std::vector<std::string> out;
    for(std::size_t at=0;at<v.size();){
        while(at<v.size()&&(v[at]==' '||v[at]=='\t'||v[at]==','))++at;
        auto end=at;
        while(end<v.size()&&v[end]!=' '&&v[end]!='\t'&&v[end]!=',')++end;
        if(end>at)out.emplace_back(v.substr(at,end-at));
        at=end;
    }
    return out;
}
std::string quoted(std::string_view v){
    std::string result="'";
    for(char c:v){if(c=='\'')result+="'\\''";else result+=c;}
    result+="'";return result;
}
bool clean(std::string_view s){
    return !s.empty()&&s.size()<=4096&&s.front()!='-'&&
        std::none_of(s.begin(),s.end(),[](unsigned char c){return c<32||c==127;});
}
bool is_path(K k){return k==K::input_file||k==K::output_file||k==K::new_directory||k==K::existing_directory;}
}
const std::vector<Operation>& operations(){
    static const std::vector<Operation> ops{
        op("can-discover","CAN — Découverte","Une requête fonctionnelle PID 00 ; ne sélectionne aucun ECU automatiquement.","--j1979-can-discover",{interface(),extended(),window(),output()},functional),
        op("can-scan","CAN — Lecture ECU","VIN, DTC, inventaires et services annoncés. Journal SCJ obligatoire dans l’interface.","--j1979-can",{interface(),tx(),rx(),vin(),budget(),journal(true),output()},physical),
        op("can-live","CAN — Mesures live","Acquisition brute bornée avec relecture d’identité ; projection numérique séparée.","--j1979-live",{interface(),tx(),rx(),vin(),pids(),cycles(),interval(),budget(),journal(true),output()},physical),
        op("can-network","CAN — Plusieurs ECU","Découverte puis campagne ordonnée des ECU explicitement choisis.","--j1979-can-network",{interface(),extended(),f("targets","RX sélectionnés (hex)","--rx",K::can_ids,"7E8 7E9"),vin(),window(),budget(),directory(true),output()},functional),
        op("can-freeze","CAN — Trames figées","Lecture Mode 02 sur le calculateur choisi.","--j1979-freeze-can",{interface(),tx(),rx(),vin(),extended(),frames(),pids(),budget(60000),capture(true),output()},physical),
        op("elm-scan","ELM CAN — Lecture ECU","Profil ELM imposé, réponses ISO-TP, journal adaptateur facultatif.","--j1979-elm-can",{port(),profile(),tx(),rx(),vin(),budget(),adapter_journal(),campaign(),output()},physical),
        op("elm-freeze","ELM CAN — Trames figées","Lecture Mode 02 sur un profil ELM fixé et un calculateur choisi.","--j1979-elm-can-freeze",{port(),profile(),tx(),rx(),vin(),extended(),frames(),pids(),budget(60000),capture(true),adapter_journal(),output()},physical),
        op("elm-live","ELM CAN — Mesures live","Acquisition CAN via adaptateur série, sélection physique.","--j1979-elm-live",{port(),profile(),tx(),rx(),vin(),pids(),cycles(),interval(),budget(),adapter_journal(),campaign(),output()},physical),
        op("legacy-discover","Legacy — Découverte","J1850, ISO 9141 ou KWP : un protocole sélectionné.","--j1979-legacy-discover",{port(),protocol(),f("baud","Vitesse série","--baud",K::decimal,"38400",false,1200,1000000),output()},physical),
        op("legacy-scan","Legacy — Lecture source","Source de réponse choisie ; pas d’adressage physique revendiqué.","--j1979-legacy",{port(),protocol(),source(),vin(),budget(),capture(true),output()},physical),
        op("legacy-live","Legacy — Mesures live","Mesures de la source sélectionnée, avec trace LLR.","--j1979-legacy-live",{port(),protocol(),source(),vin(),pids(),cycles(),interval(),budget(),raw(),capture(true),output()},physical),
        op("legacy-freeze","Legacy — Trames figées","Mode 02, source de réponse sélectionnée.","--j1979-legacy-freeze",{port(),protocol(),source(),vin(),frames(),pids(),budget(),capture(true),output()},physical),
        op("usb-scan","USB natif — Lecture ECU","Capture CJ/1 via connecteur natif et clé locale.","--j1979-usb",{port(),key(),tx(),rx(),vin(),extended(),budget(60000),capture(true),output()},physical),
        op("usb-freeze","USB natif — Trames figées","Capture CF/1 via connecteur natif.","--j1979-freeze-native",{port(),key(),tx(),rx(),vin(),frames(),pids(),budget(),capture(true),output()},physical),
        op("usb-live","USB natif — Mesures live","Capture CL/1 via connecteur natif.","--j1979-live-usb-native",{port(),key(),tx(),rx(),vin(),extended(),pids(),cycles(),interval(),budget(),capture(true),output()},physical),
        op("inspect-scj","Hors ligne — Inspection SCJ","Rejoue les callbacks sans connexion au véhicule.","inspect-socketcan-journal",{journal(false),tx(),rx(),vin(),output()}),
        op("recover-scj","Hors ligne — Récupération SCJ","Reconstruit un rapport seulement si le journal contient un terminal vérifié.","recover-socketcan-report",{journal(false),tx(),rx(),vin(),output()}),
        op("verify-scj","Hors ligne — Vérification SCJ","Compare le journal à son rapport exact.","verify-socketcan-journal",{journal(false),report(),tx(),rx(),vin(),output()}),
        op("verify-network","Hors ligne — Vérification réseau","Rattache chaque journal SCJ au rapport réseau et à la découverte.","verify-network-journals",{report(),directory(false),interface(),vin(),output()}),
        op("project-live","Hors ligne — Projection live","Reconstitue les valeurs et statistiques après vérification d’identité.","project-native-live",{report(),tx(),rx(),vin(),output()}),
        op("csv-live","Hors ligne — CSV CAN","Export des échantillons et signaux ; données manquantes non remplacées par zéro.","export-native-live-csv",{report(),tx(),rx(),vin(),output()}),
        op("verify-projection","Hors ligne — Vérification projection","Vérifie une projection numérique contre le rapport brut.","verify-native-live-projection",{report(),projection(),tx(),rx(),vin(),output()}),
        op("csv-legacy","Hors ligne — CSV legacy","Projection depuis la capture LLR, pas depuis des valeurs déjà éditées.","export-legacy-live-csv",{capture(false),protocol(),source(),vin(),output()}),
        op("verify-cj1","Hors ligne — Vérification CJ/1","Vérifie le rapport embarqué historique contre la capture.","verify-j1979-capture",{capture(false),report(),vin(),output()}),
        op("verify-cf1","Hors ligne — Vérification CF/1","Vérifie le rapport historique de trames figées contre la capture.","verify-native-freeze",{capture(false),report(),vin(),output()}),
        op("verify-legacy","Hors ligne — Scan legacy historique","Rejeu de la source sélectionnée et des champs dérivés.","verify-legacy",{report(),vin(),output()}),
        op("verify-live","Hors ligne — Live historique","Rejeu d’un rapport historique et de ses statistiques.","verify-live",{report(),vin(),output()}),
        op("verify-freeze","Hors ligne — Freeze historique CAN","Reconstruction de la recette et des déclencheurs.","verify-freeze",{report(),vin(),output()}),
        op("verify-legacy-freeze","Hors ligne — Freeze historique legacy","Reconstruction des échanges de la source sélectionnée.","verify-legacy-freeze",{report(),vin(),output()})
    };
    return ops;
}
Invocation prepare(const Operation& supplied,const Values& values){
    const auto& ops=operations();
    const auto found=std::find_if(ops.begin(),ops.end(),[&](const Operation& o){return o.id==supplied.id;});
    require(found!=ops.end(),"Commande non autorisée");
    const auto& op=*found;
    Invocation invocation{op.command,{op.command},op.physical,op.confirmation};
    for(const auto& [k,v]:values){
        (void)v;require(std::any_of(op.fields.begin(),op.fields.end(),[&](const Field& a){return a.key==k;}),"Champ inattendu");
    }
    std::map<std::string,unsigned> ids;
    std::set<std::filesystem::path> paths;
    unsigned selected_count=0,cycle_count=30;
    for(const auto& field:op.fields){
        const auto it=values.find(field.key);
        const std::string value=it==values.end()?"":it->second;
        if(field.kind==K::check){
            require(value.empty()||value=="true"||value=="false","Case à cocher invalide");
            if(value=="true")invocation.args.push_back(field.flag);
            continue;
        }
        if(value.empty()){require(!field.required,"Champ requis : "+field.label);continue;}
        require(clean(value),"Valeur invalide : "+field.label);
        if(field.kind==K::vin)require(value.size()==17&&j1979_can_valid_vin(value.c_str()),"VIN invalide");
        else if(field.kind==K::text){
            if(field.key=="interface")require(j1979_isotp_valid_interface(value.c_str()),"Interface SocketCAN invalide");
            if(field.key=="port")require(value.starts_with("/dev/"),"Port série local /dev requis");
        }else if(field.kind==K::can_id){
            ids[field.key]=parse(value,16,0,0x1fffffff,"Identifiant CAN invalide");
            if(field.key=="source")require(value.size()==2&&ids.at(field.key)>0&&ids.at(field.key)<255&&ids.at(field.key)!=0xf1,"Source legacy invalide");
        }else if(field.kind==K::decimal||field.kind==K::protocol||field.kind==K::profile){
            const auto n=parse(value,10,field.kind==K::protocol?1:field.kind==K::profile?6:field.minimum,
                field.kind==K::protocol?5:field.kind==K::profile?9:field.maximum,"Valeur numérique hors limites");
            if(field.key=="cycles")cycle_count=n;
        }else if(field.kind==K::pids||field.kind==K::frames||field.kind==K::can_ids){
            const auto tokens=words(value);std::set<unsigned> seen;
            require(!tokens.empty()&&tokens.size()<=(field.kind==K::frames?8u:32u),"Liste vide ou trop longue");
            for(const auto& token:tokens){
                const auto n=parse(token,16,field.kind==K::pids?1:0,field.kind==K::can_ids?0x1fffffffu:255u,"Élément hexadécimal invalide");
                require(seen.insert(n).second,"Élément dupliqué");
                if(field.kind==K::pids)require(n%32!=0&&(!(op.id.find("freeze")!=std::string::npos)||n!=2),"Un PID de données est requis, sans bitmap ni déclencheur Mode 02");
                if(field.kind==K::can_ids){
                    const bool ext=values.contains("extended")&&values.at("extended")=="true";
                    require(ext?(n&0x1fffff00u)==0x18daf100u&&(n&255u)!=255u:n>=0x7e8&&n<=0x7ef,
                        "Réponse CAN hors famille sélectionnée");
                }
                if(field.kind!=K::can_ids&&token.size()==1){invocation.args.push_back(field.flag);invocation.args.push_back("0"+token);}
                else{invocation.args.push_back(field.flag);invocation.args.push_back(token);}
            }
            if(field.kind==K::pids)selected_count=static_cast<unsigned>(tokens.size());
            continue;
        }else if(is_path(field.kind)){
            namespace fs=std::filesystem;
            std::error_code ec;
            auto path=fs::absolute(fs::path(value),ec).lexically_normal();require(!ec,"Chemin invalide");
            auto status=fs::symlink_status(path,ec);
            const bool input=field.kind==K::input_file||field.kind==K::existing_directory;
            if(input){
                require(!ec&&(field.kind==K::input_file?fs::is_regular_file(status):fs::is_directory(status)),"Entrée locale régulière requise");
            }else{
                require((!ec&&!fs::exists(status))||ec==std::errc::no_such_file_or_directory,"La destination existe déjà");
                ec.clear();require(fs::is_directory(path.parent_path(),ec)&&!ec,"Répertoire parent inexistant");
            }
            require(paths.insert(path).second,"Chemins de preuve et sortie distincts requis");
            invocation.args.push_back(field.flag);invocation.args.push_back(path.string());continue;
        }
        invocation.args.push_back(field.flag);invocation.args.push_back(value);
    }
    if(ids.contains("tx")&&ids.contains("rx"))
        require(j1979_can_physical_pair(ids.at("tx"),ids.at("rx")),"Paire CAN physique invalide");
    require(selected_count*cycle_count<=10000,"Nombre total d’échantillons supérieur à 10 000");
    if(ids.contains("tx")&&ids.contains("rx")&&std::any_of(op.fields.begin(),op.fields.end(),[](const Field& a){return a.key=="extended";})){
        const bool extended=values.contains("extended")&&values.at("extended")=="true";
        require(extended==(ids.at("tx")>0x7ff),"Adressage 11/29 bits incohérent");
    }
    if(op.physical)invocation.args.push_back(op.confirmation);
    return invocation;
}
std::string preview(const Invocation& invocation){
    std::string result="camarodiag-native";
    for(const auto& a:invocation.args){result+=' ';result+=quoted(a);}
    return result;
}
}
