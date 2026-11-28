#pragma once
#include "j1979_can_read_session.h"

namespace cd {
// Link supplied by a board-specific, qualified transport. No implicit
// installation, enablement, reconfiguration or bus recovery here.
class J1979CanReadLink {
 public:
  using Frame=J1979CanReadSession::Frame;
  enum class Receive { Data, NoFrame, Fault };
  virtual ~J1979CanReadLink()=default;
  virtual bool active()=0;
  virtual uint32_t monotonicMs()=0;
  virtual bool discardReceive()=0;
  // Return success only if the frame was actually sent, not merely queued.
  virtual bool send(const Frame& frame,uint32_t timeoutMs)=0;
  virtual Receive receive(Frame& frame,uint32_t timeoutMs)=0;
};

class J1979CanReadExchange {
 public:
  using Session=J1979CanReadSession;
  using State=Session::State;
  struct Result {
    State state=State::Fault;
    uint8_t response[4095]{};
    size_t responseSize=0;
    uint32_t elapsedMs=0;
  };
  static void run(J1979CanReadLink& link,uint32_t tx,uint32_t rx,bool extended,
                  const uint8_t* request,size_t size,uint32_t timeout,Result& output,
                  const J1979FreezeScope* freeze=nullptr,const J1979LiveScope* live=nullptr){
    output.state=State::Fault;output.responseSize=0;output.elapsedMs=0;
    Session session(tx,rx,extended,freeze,live);
    Session::Frame outgoing,received,flow;
    const uint32_t start=link.monotonicMs();
    if(!link.active()||!session.begin(request,size,start,timeout,outgoing))return;
    if(!link.discardReceive()||!link.active())return;
    uint32_t spent=uint32_t(link.monotonicMs()-start);
    if(spent>=timeout){output.state=State::Timeout;output.elapsedMs=spent;return;}
    if(!link.send(outgoing,timeout-spent)){output.elapsedMs=uint32_t(link.monotonicMs()-start);return;}
    // Bounded even if a faulty clock never advances or a driver floods data.
    for(unsigned iterations=0;iterations<8192;++iterations){
      const uint32_t now=link.monotonicMs();
      output.elapsedMs=uint32_t(now-start);
      output.state=session.poll(now);
      if(output.state!=State::Waiting)break;
      if(!link.active()){session.abort();output.state=State::Fault;break;}
      const uint32_t remaining=timeout-output.elapsedMs;
      const auto status=link.receive(received,remaining<20u?remaining:20u);
      if(status==J1979CanReadLink::Receive::Fault){session.abort();output.state=State::Fault;break;}
      if(status==J1979CanReadLink::Receive::NoFrame)continue;
      if(!link.active()){session.abort();output.state=State::Fault;break;}
      bool flowRequired=false;
      output.state=session.feed(received,link.monotonicMs(),flow,flowRequired);
      if(flowRequired){
        spent=uint32_t(link.monotonicMs()-start);
        if(spent>=timeout||!link.active()||!link.send(flow,timeout-spent)){
          session.abort();output.state=State::Fault;break;
        }
      }
      if(output.state!=State::Waiting)break;
    }
    if(output.state==State::Waiting){session.abort();output.state=State::Fault;}
    output.elapsedMs=uint32_t(link.monotonicMs()-start);
    if(output.state==State::Positive||output.state==State::Negative||output.state==State::Raw){
      output.responseSize=session.responseSize();
      std::memcpy(output.response,session.response(),output.responseSize);
    }
  }
};
}
