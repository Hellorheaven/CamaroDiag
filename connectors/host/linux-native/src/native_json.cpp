#include "native_json.hpp"
#include <algorithm>
#include <charconv>
#include <limits>
#include <utility>
namespace cd::json {
namespace {
void require(bool condition,const char* error){if(!condition)throw std::invalid_argument(error);}
template<class T>const T& value(const Value& v){
    const auto* p=std::get_if<T>(&v.data);
    require(p!=nullptr,"Invalid JSON field type");return *p;
}
unsigned codepoint(std::string_view s,std::size_t& p){
    require(p<s.size(),"Truncated UTF-8");
    const unsigned first=static_cast<unsigned char>(s[p++]);
    if(first<128)return first;
    unsigned n=0,result=0,min=0;
    if(first>=0xc2&&first<=0xdf){n=1;result=first&31;min=0x80;}
    else if(first>=0xe0&&first<=0xef){n=2;result=first&15;min=0x800;}
    else if(first>=0xf0&&first<=0xf4){n=3;result=first&7;min=0x10000;}
    else throw std::invalid_argument("Invalid UTF-8 sequence");
    require(n<=s.size()-p,"Truncated UTF-8 continuation");
    while(n--){
        const unsigned b=static_cast<unsigned char>(s[p++]);
        require((b&0xc0)==0x80,"Invalid UTF-8 continuation");result=(result<<6)|(b&63);
    }
    require(result>=min&&result<=0x10ffff&&!(result>=0xd800&&result<=0xdfff),"Noncanonical UTF-8 scalar");
    return result;
}
void append_utf8(std::string& s,unsigned v){
    require(v<=0x10ffff&&!(v>=0xd800&&v<=0xdfff),"Invalid Unicode scalar");
    if(v<0x80)s+=static_cast<char>(v);
    else if(v<0x800){s+=static_cast<char>(0xc0|(v>>6));s+=static_cast<char>(0x80|(v&63));}
    else if(v<0x10000){
        s+=static_cast<char>(0xe0|(v>>12));s+=static_cast<char>(0x80|((v>>6)&63));s+=static_cast<char>(0x80|(v&63));
    }else{
        s+=static_cast<char>(0xf0|(v>>18));s+=static_cast<char>(0x80|((v>>12)&63));
        s+=static_cast<char>(0x80|((v>>6)&63));s+=static_cast<char>(0x80|(v&63));
    }
}
bool ws(unsigned v){
    return (v>=9&&v<=13)||(v>=0x1c&&v<=0x20)||v==0x85||v==0xa0||v==0x1680||
        (v>=0x2000&&v<=0x200a)||v==0x2028||v==0x2029||v==0x202f||v==0x205f||v==0x3000;
}
class Parser{
    std::string_view s_;
    std::size_t p_{},nodes_{};
    static constexpr std::size_t max_nodes=1000000,max_string=1024*1024;
    void space(){
        while(p_<s_.size()&&(s_[p_]==' '||s_[p_]=='\t'||s_[p_]=='\r'||s_[p_]=='\n'))++p_;
    }
    bool take(char c){space();if(p_>=s_.size()||s_[p_]!=c)return false;++p_;return true;}
    void expect(char c){require(take(c),"Unexpected JSON token");}
    unsigned hex4(){
        require(s_.size()-p_>=4,"Truncated Unicode escape");
        unsigned v=0;
        for(unsigned i=0;i<4;++i){
            const char c=s_[p_++];unsigned d=0;
            if(c>='0'&&c<='9')d=unsigned(c-'0');
            else if(c>='a'&&c<='f')d=unsigned(c-'a')+10;
            else if(c>='A'&&c<='F')d=unsigned(c-'A')+10;
            else throw std::invalid_argument("Invalid Unicode escape");
            v=(v<<4)|d;
        }
        return v;
    }
    std::string string(){
        expect('"');std::string out;
        while(p_<s_.size()){
            const auto c=static_cast<unsigned char>(s_[p_++]);
            if(c=='"')return out;
            require(c>=32,"Unescaped JSON control");
            if(c=='\\'){
                require(p_<s_.size(),"Truncated JSON escape");
                const char esc=s_[p_++];
                switch(esc){
                case '"':case '\\':case '/':out+=esc;break;
                case 'b':out+='\b';break;case 'f':out+='\f';break;case 'n':out+='\n';break;
                case 'r':out+='\r';break;case 't':out+='\t';break;
                case 'u':{
                    unsigned v=hex4();
                    if(v>=0xd800&&v<=0xdbff){
                        require(s_.size()-p_>=2&&s_[p_]=='\\'&&s_[p_+1]=='u',"Missing low surrogate");
                        p_+=2;const unsigned low=hex4();
                        require(low>=0xdc00&&low<=0xdfff,"Invalid low surrogate");
                        v=0x10000+((v-0xd800)<<10)+(low-0xdc00);
                    }
                    append_utf8(out,v);break;
                }
                default:throw std::invalid_argument("Invalid JSON escape");
                }
            }else if(c<128)out+=static_cast<char>(c);
            else{
                --p_;const auto begin=p_;(void)codepoint(s_,p_);out.append(s_.substr(begin,p_-begin));
            }
            require(out.size()<=max_string,"JSON string limit");
        }
        throw std::invalid_argument("Unterminated JSON string");
    }
    Value integer(){
        const auto begin=p_;
        if(s_[p_]=='-')++p_;
        require(p_<s_.size()&&s_[p_]>='0'&&s_[p_]<='9',"Invalid JSON integer");
        if(s_[p_]=='0')++p_;
        else while(p_<s_.size()&&s_[p_]>='0'&&s_[p_]<='9')++p_;
        require(p_-begin<=21,"JSON integer width exceeded");
        if(p_<s_.size())require(s_[p_]!='.'&&s_[p_]!='e'&&s_[p_]!='E'&&!(s_[p_]>='0'&&s_[p_]<='9'),
            "Only exact JSON integer fields supported");
        return Value{Integer{std::string(s_.substr(begin,p_-begin))}};
    }
    Value literal(std::string_view word,Value result){
        require(s_.substr(p_,word.size())==word,"Invalid JSON literal");p_+=word.size();return result;
    }
    Value parse_value(unsigned depth){
        space();require(depth<=32&&++nodes_<=max_nodes&&p_<s_.size(),"JSON depth, node or input limit");
        const char c=s_[p_];
        if(c=='"')return Value{string()};
        if(c=='{'){
            ++p_;Value::Object out;
            if(take('}'))return Value{std::move(out)};
            do{
                const auto key=string();expect(':');
                auto v=parse_value(depth+1);
                require(out.emplace(key,std::move(v)).second,"Duplicate JSON key");
            }while(take(','));
            expect('}');return Value{std::move(out)};
        }
        if(c=='['){
            ++p_;Value::Array out;
            if(take(']'))return Value{std::move(out)};
            do{out.push_back(parse_value(depth+1));}while(take(','));
            expect(']');return Value{std::move(out)};
        }
        if(c=='t')return literal("true",Value{true});
        if(c=='f')return literal("false",Value{false});
        if(c=='n')return literal("null",Value{nullptr});
        if(c=='-'||(c>='0'&&c<='9'))return integer();
        throw std::invalid_argument("Unsupported JSON token");
    }
public:
    explicit Parser(std::string_view s):s_(s){}
    Value parse(){auto result=parse_value(0);space();require(p_==s_.size(),"Trailing JSON data");return result;}
};
}
const Value::Object& Value::object()const{return value<Object>(*this);}
const Value::Array& Value::array()const{return value<Array>(*this);}
const std::string& Value::string()const{return value<std::string>(*this);}
bool Value::boolean()const{return value<bool>(*this);}
std::uint64_t Value::unsigned_integer()const{
    const auto& text=value<Integer>(*this).text;std::uint64_t n{};
    const auto [end,error]=std::from_chars(text.data(),text.data()+text.size(),n);
    require(error==std::errc{}&&end==text.data()+text.size(),"Invalid unsigned integer");return n;
}
std::int64_t Value::signed_integer()const{
    const auto& text=value<Integer>(*this).text;std::int64_t n{};
    const auto [end,error]=std::from_chars(text.data(),text.data()+text.size(),n);
    require(error==std::errc{}&&end==text.data()+text.size(),"Invalid signed integer");return n;
}
const Value* Value::find(std::string_view key)const{
    const auto& o=object();const auto it=o.find(key);return it==o.end()?nullptr:&it->second;
}
const Value& Value::at(std::string_view key)const{
    const auto* p=find(key);require(p!=nullptr,"Missing JSON field");return *p;
}
Value parse(std::string_view text,std::size_t max_bytes){
    require(!text.empty()&&text.size()<=max_bytes,"JSON input limit");
    return Parser(text).parse();
}
void fields(const Value& v,std::initializer_list<std::string_view> required,std::initializer_list<std::string_view> optional){
    const auto& o=v.object();
    for(auto key:required)require(o.contains(key),"Missing JSON field");
    for(const auto& [key,item]:o){
        (void)item;
        require(std::find(required.begin(),required.end(),key)!=required.end()||
            std::find(optional.begin(),optional.end(),key)!=optional.end(),"Unknown JSON field");
    }
}
std::string quote(std::string_view text){
    constexpr char hex[]="0123456789abcdef";std::string result="\"";
    for(std::size_t p=0;p<text.size();){
        const auto begin=p;const auto v=codepoint(text,p);
        if(v=='"'||v=='\\'){result+='\\';result+=static_cast<char>(v);}
        else if(v<32){result+="\\u00";result+=hex[v>>4];result+=hex[v&15];}
        else result.append(text.substr(begin,p-begin));
    }
    result+='"';return result;
}
bool valid_text(std::string_view text,std::size_t max){
    if(text.empty())return false;
    try{
        std::size_t count=0,pos=0;unsigned first=0,last=0;
        while(pos<text.size()){
            const auto c=codepoint(text,pos);
            if(!count)first=c;
            last=c;
            if(++count>max||c<32||c==127)return false;
        }
        return !ws(first)&&!ws(last);
    }catch(const std::invalid_argument&){return false;}
}
}
