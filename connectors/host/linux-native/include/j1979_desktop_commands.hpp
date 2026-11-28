#pragma once
#include <map>
#include <string>
#include <string_view>
#include <vector>

namespace cd::desktop {
enum class FieldKind {text,vin,can_id,can_ids,pids,frames,decimal,protocol,profile,input_file,output_file,new_directory,existing_directory,check};
struct Field {
    std::string key,label,flag,placeholder;
    FieldKind kind{};
    bool required{};
    unsigned minimum{},maximum{};
};
struct Operation {
    std::string id,title,description,command,confirmation;
    bool physical{};
    std::vector<Field> fields;
};
using Values=std::map<std::string,std::string,std::less<>>;
struct Invocation {
    std::string command;
    std::vector<std::string> args;
    bool physical{};
    std::string confirmation;
};
const std::vector<Operation>& operations();
Invocation prepare(const Operation&,const Values&);
// A display-only preview, never passed to a shell.
std::string preview(const Invocation&);
}
