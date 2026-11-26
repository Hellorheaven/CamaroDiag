#pragma once
#include "../../shared/j1979_can_read_exchange.h"
#include <driver/twai.h>

namespace cd {
// Adapter for an already initialized, exclusively owned ESP-IDF 4.x TWAI
// session. No pins, transceiver enable, driver installation, start, recovery
// or USB dispatch are performed here. Board profile qualification remains
// the caller's responsibility. Discovery firmware never constructs this.
class TwaiJ1979ReadLink final : public J1979CanReadLink {
 public:
  using Admission=bool(*)();
  explicit TwaiJ1979ReadLink(Admission qualifiedExclusiveSession)
    :admission_(qualifiedExclusiveSession){}
  bool active()override;
  uint32_t monotonicMs()override;
  bool discardReceive()override;
  bool send(const Frame& frame,uint32_t timeoutMs)override;
  Receive receive(Frame& frame,uint32_t timeoutMs)override;
 private:
  bool snapshot(twai_status_info_t& status);
  bool noLoss(const twai_status_info_t& status)const;
  Admission admission_;
  bool baseline_=false, faulted_=false;
  bool fail(){faulted_=true;return false;}
  uint32_t missed_=0,overrun_=0,failed_=0,busErrors_=0;
};
}
