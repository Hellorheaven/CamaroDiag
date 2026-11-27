#include "j1979_cu45_session.hpp"
#include <algorithm>
#include <limits>
#include <openssl/crypto.h>
#include <utility>

namespace cd::cu45 {
namespace {
template<class T> struct Secret{
    T value{};
    ~Secret(){OPENSSL_cleanse(value.data(),value.size());}
};
class Exchange{
    Link& link_;
    const Clock& clock_;
    const Current& current_;
    const Sink& sink_;
    Result& r_;
public:
    Exchange(Link& link,const Clock& clock,const Current& current,const Sink& sink,Result& result)
        :link_(link),clock_(clock),current_(current),sink_(sink),r_(result){}
    std::uint64_t deadline(unsigned budget){
        const auto now=clock_();
        if(!current_()||now>std::numeric_limits<std::uint64_t>::max()-budget)
            throw TransportError("SESSION_REVOKED_OR_CLOCK_INVALID");
        return now+budget;
    }
    void check(std::uint64_t end){
        if(!current_())throw TransportError("CONTEXT_REVOKED");
        if(clock_()>=end)throw TransportError("DEADLINE_EXCEEDED");
    }
    void write(cm1::Bytes bytes,std::uint64_t end){
        check(end);link_.write(bytes,end);check(end);
    }
    void exact(std::span<std::uint8_t> bytes,std::uint64_t end,bool retain=false){
        while(!bytes.empty()){
            check(end);
            const auto n=link_.read(bytes,end);
            if(!n||n>bytes.size())throw TransportError("TRUNCATED_OR_INVALID_TRANSFER");
            if(retain){
                const auto chunk=bytes.first(n);
                if(chunk.size()>cm1::max_journal-r_.journal.size())throw TransportError("JOURNAL_LIMIT");
                // Original bytes remain available even if cancellation follows this read.
                r_.journal.insert(r_.journal.end(),chunk.begin(),chunk.end());
                sink_(chunk);
            }
            bytes=bytes.subspan(n);check(end);
        }
    }
    void quiet(std::uint64_t end){check(end);link_.quiet(end);check(end);}
};
}
Session::Session(Link& link,Clock clock,Current current)
    :link_(link),clock_(std::move(clock)),current_(std::move(current)){
    if(!clock_||!current_)throw std::invalid_argument("Explicit session clock and context required");
}
Result Session::run(Mode mode,const cm1::Scope& scope,std::span<const std::uint8_t,32> key,
                    const Nonce& nonce,Sink sink,bool operator_authorized){
    if(used_)throw std::invalid_argument("Fresh single-use CU/4 or CU/5 session required");
    if(!operator_authorized||!sink||!valid_scope(scope))
        throw std::invalid_argument("Explicit operator authorization and canonical scope required");
    Secret<Hello> request;
    if(!hello(mode,scope,nonce,request.value))throw std::invalid_argument("Invalid CU/4-5 hello");
    used_=true;
    Result result{};result.mode=mode;result.scope=scope;
    const auto begin=clock_();
    Exchange x(link_,clock_,current_,sink,result);
    try{
        Secret<Challenge> challenge;
        Secret<Proof> answer;
        const auto auth=x.deadline(5000);
        x.write(request.value,auth);x.exact(challenge.value,auth);
        if(!proof(mode,request.value,challenge.value,key,answer.value))
            throw TransportError("DEVICE_CREDENTIAL_REJECTED");
        result.authenticated=true;
        x.quiet(auth);
        x.check(auth);
        // A write failure after this point is ambiguous; never retry the proof.
        result.proof_attempted=true;
        x.write(answer.value,auth);
        result.proof_sent=true;
        const auto end=x.deadline(mode==Mode::archive?40000:scope.budget+40000);
        Envelope envelope{};
        x.exact(envelope,end);
        result.declared=declaration(mode,envelope);
        if(!result.declared)throw TransportError("INVALID_RESULT_DECLARATION");
        result.journal.reserve(result.declared->length);
        std::array<std::uint8_t,4096> buffer{};
        std::size_t left=result.declared->length;
        while(left){
            const auto n=std::min(left,buffer.size());
            x.exact(std::span(buffer).first(n),end,true);left-=n;
        }
        if(mode==Mode::archive){
            Envelope closing{};x.exact(closing,end);
            if(!receipt(closing,result.journal))throw TransportError("ARCHIVE_RECEIPT_MISMATCH");
            result.receipt_verified=true;
        }
        x.quiet(end);
        result.inspection=inspect(mode,scope,*result.declared,result.journal);
        if(!result.inspection)throw TransportError("JOURNAL_SCOPE_OR_REPLAY_MISMATCH");
        x.check(end);
    }catch(const std::exception& e){result.error=e.what();}
    try{link_.finish();}
    catch(const std::exception& e){if(result.error.empty())result.error=e.what();}
    result.cancelled=!current_();
    if(result.cancelled&&result.error.empty())result.error="CONTEXT_REVOKED";
    const auto end=clock_();
    if(end<begin&&result.error.empty())result.error="MONOTONIC_CLOCK_REGRESSED";
    result.elapsed_ms=end>=begin?end-begin:0;
    if(!result.error.empty())result.inspection.reset();
    return result;
}
}
