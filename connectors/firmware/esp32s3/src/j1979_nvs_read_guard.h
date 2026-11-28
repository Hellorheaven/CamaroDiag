#pragma once
#include "j1979_read_guard.h"

namespace cd {
// Local, single-owner NVS adapter. No erase/initialization/recovery command.
// Commissioning must provision a verified Clean record independently.
class J1979NvsReadGuardStore final:public J1979ReadGuard::Store {
 public:
  bool read(uint8_t* data,size_t size)override;
  bool writeVerified(const uint8_t* data,size_t size)override;
};
}
