#include "j1979_projection.hpp"
#include <locale>
#include <sstream>

namespace cd::j1979 {
bool known_shape(const Observation& observation) {
    if(observation.request.size()!=2||observation.request[0]!=1)return true;
    const unsigned pid=observation.request[1];
    if(multi_width(pid)&&!multi_signal(observation))return false;
    if(status_width(pid)&&!status_record(observation))return false;
    return true;
}
bool write_projections(std::FILE* out,std::span<const Observation> rows,bool identity_rechecked) {
    if(!out)return false;
    const auto context=identity_rechecked?derive_context(rows):Context{};
    std::ostringstream json;json.imbue(std::locale::classic());
    json<<",\"multi_signal_records\":";
    write_multi_json(json,rows,identity_rechecked?&context.scaling:nullptr);
    json<<",\"status_records\":";
    write_status_json(json,rows);
    if(!json)return false;
    const auto serialized=json.str();
    return serialized.size()<=64u*1024u*1024u&&
        std::fwrite(serialized.data(),1,serialized.size(),out)==serialized.size();
}
}
