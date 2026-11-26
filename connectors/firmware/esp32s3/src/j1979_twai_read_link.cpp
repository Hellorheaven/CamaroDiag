#include "j1979_twai_read_link.h"
#include <esp_timer.h>
#include <freertos/FreeRTOS.h>
#include <cstring>

namespace cd {
namespace {
TickType_t ticks(uint32_t ms){
  TickType_t value=pdMS_TO_TICKS(ms);
  return value?value:1;
}
constexpr uint32_t failureAlerts=TWAI_ALERT_TX_FAILED|TWAI_ALERT_BUS_OFF|
    TWAI_ALERT_RX_QUEUE_FULL|TWAI_ALERT_BUS_ERROR;
}

bool TwaiJ1979ReadLink::snapshot(twai_status_info_t& status){
  return !faulted_&&admission_&&admission_()&&twai_get_status_info(&status)==ESP_OK&&
    status.state==TWAI_STATE_RUNNING;
}
bool TwaiJ1979ReadLink::noLoss(const twai_status_info_t& status)const{
  return baseline_&&status.rx_missed_count==missed_&&status.rx_overrun_count==overrun_&&
    status.tx_failed_count==failed_&&status.bus_error_count==busErrors_;
}
bool TwaiJ1979ReadLink::active(){
  twai_status_info_t status{};
  return (snapshot(status)&&(!baseline_||noLoss(status)))||fail();
}
uint32_t TwaiJ1979ReadLink::monotonicMs(){
  return static_cast<uint32_t>(esp_timer_get_time()/1000);
}
bool TwaiJ1979ReadLink::discardReceive(){
  twai_status_info_t status{};
  // Keep the original loss counters throughout this exclusive campaign.
  // Rebaselining for each request would hide an error between exchanges.
  // Caller owns the entire channel, including alerts and TX/RX queues.
  if(!snapshot(status)||(baseline_&&!noLoss(status))||status.msgs_to_tx!=0)return fail();
  if(!baseline_){
    missed_=status.rx_missed_count;overrun_=status.rx_overrun_count;
    failed_=status.tx_failed_count;busErrors_=status.bus_error_count;
    baseline_=true;
  }
  if(twai_clear_receive_queue()!=ESP_OK)return fail();
  uint32_t stale=0;
  const esp_err_t cleared=twai_read_alerts(&stale,0);
  if(cleared!=ESP_OK&&cleared!=ESP_ERR_TIMEOUT)return fail();
  if((stale&failureAlerts)!=0||!snapshot(status)||!noLoss(status)||status.msgs_to_tx!=0)return fail();
  return true;
}
bool TwaiJ1979ReadLink::send(const Frame& frame,uint32_t timeoutMs){
  twai_status_info_t status{};
  if(timeoutMs<1||timeoutMs>4000||frame.length!=8||
     frame.id>(frame.extended?0x1fffffffu:0x7ffu)||
     !snapshot(status)||!noLoss(status)||status.msgs_to_tx!=0)return fail();
  uint32_t stale=0;
  const esp_err_t cleared=twai_read_alerts(&stale,0);
  if((cleared!=ESP_OK&&cleared!=ESP_ERR_TIMEOUT)||(stale&failureAlerts)!=0)return fail();
  twai_message_t msg{};
  msg.identifier=frame.id;msg.extd=frame.extended?1:0;
  msg.ss=1; // single-shot, no driver-level automatic retry
  msg.data_length_code=frame.length;
  std::memcpy(msg.data,frame.data,frame.length);
  const uint32_t start=monotonicMs();
  if(!admission_()||twai_transmit(&msg,ticks(timeoutMs))!=ESP_OK)return fail();
  // TWAI transmit only queues a message. Require a fresh TX_SUCCESS alert.
  for(unsigned polls=0;polls<256;++polls){
    const uint32_t elapsed=uint32_t(monotonicMs()-start);
    if(elapsed>=timeoutMs||!snapshot(status)||!noLoss(status))return fail();
    uint32_t alerts=0;
    const uint32_t remaining=timeoutMs-elapsed;
    const esp_err_t rc=twai_read_alerts(&alerts,ticks(remaining<20u?remaining:20u));
    if(rc==ESP_ERR_TIMEOUT)continue;
    if(rc!=ESP_OK||(alerts&failureAlerts)!=0)return fail();
    if((alerts&TWAI_ALERT_TX_SUCCESS)!=0)
      return (snapshot(status)&&noLoss(status)&&status.msgs_to_tx==0)||fail();
  }
  return fail();
}
J1979CanReadLink::Receive TwaiJ1979ReadLink::receive(Frame& frame,uint32_t timeoutMs){
  twai_status_info_t status{};
  if(!timeoutMs||timeoutMs>20||!snapshot(status)||!noLoss(status)){fail();return Receive::Fault;}
  twai_message_t msg{};
  const esp_err_t rc=twai_receive(&msg,ticks(timeoutMs));
  if(!snapshot(status)||!noLoss(status)){fail();return Receive::Fault;}
  if(rc==ESP_ERR_TIMEOUT)return Receive::NoFrame;
  if(rc!=ESP_OK){fail();return Receive::Fault;}
  if(msg.rtr||msg.self)return Receive::NoFrame;
  if(msg.data_length_code<1||msg.data_length_code>8||
     msg.identifier>(msg.extd?0x1fffffffu:0x7ffu)){fail();return Receive::Fault;}
  frame=Frame{};frame.id=msg.identifier;frame.extended=msg.extd!=0;
  frame.length=msg.data_length_code;std::memcpy(frame.data,msg.data,frame.length);
  return Receive::Data;
}
}
