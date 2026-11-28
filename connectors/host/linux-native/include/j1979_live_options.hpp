#pragma once
#include <string_view>

namespace cd::liveoptions {
// Exact millisecond conversion for the historical CLI seconds arguments.
// Reject nonrepresentable precision and avoid locale-dependent floating point.
constexpr bool seconds_ms(std::string_view text,unsigned& result) noexcept {
    if(text.empty()||text.size()>12)return false;
    unsigned whole=0,fraction=0,digits=0;
    bool decimal=false,integer=false;
    for(char c:text){
        if(c=='.'&&!decimal&&integer){decimal=true;continue;}
        if(c<'0'||c>'9')return false;
        const auto n=static_cast<unsigned>(c-'0');
        if(decimal){
            if(digits==3){if(n)return false;continue;}
            fraction=fraction*10+n;++digits;
        }else{
            integer=true;
            whole=whole*10+n;
            if(whole>600)return false;
        }
    }
    if(!integer||(decimal&&!digits))return false;
    while(digits<3){fraction*=10;++digits;}
    const auto ms=whole*1000+fraction;
    if(ms==0||ms>600000)return false;
    result=ms;return true;
}
}
