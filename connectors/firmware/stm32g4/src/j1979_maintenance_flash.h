#pragma once
#include <Arduino.h>
#include <stm32g4xx_hal_flash.h>
#include "../../shared/j1979_maintenance_flash_medium.h"

namespace cd {
// Concrete internal-flash driver, NOT a partition allocator. No default
// addresses. Linker/bootloader reservations must be supplied and qualified
// by the board owner. If no suitably isolated region exists, use external
// storage and leave this driver uninstantiated.
class StmJ1979MaintenanceFlash final:public J1979MaintenanceFlashMedium::Flash {
 public:
  using Medium=J1979MaintenanceFlashMedium;
  using Region=Medium::Region;
  class Board {
   public:
    virtual ~Board()=default;
    virtual bool reserved(const Region& guard,const Region& slots)=0;
    // Must hold an exclusive flash writer lease, and verify that writing
    // this region cannot stall the emergency-stop/consent watchdog.
    virtual bool storageAvailable()=0;
    virtual bool programAllowed()=0;
    virtual void inhibitDiagnosticTx()=0;
  };
  StmJ1979MaintenanceFlash(Board& board,const Region& guard,const Region& slots):
    board_(board),guard_(guard),slots_(slots){}
  StmJ1979MaintenanceFlash(const StmJ1979MaintenanceFlash&)=delete;
  StmJ1979MaintenanceFlash& operator=(const StmJ1979MaintenanceFlash&)=delete;
  bool reserved(const Region& guard,const Region& slots)override;
  bool read(uint32_t address,uint8_t* out,size_t n)override;
  bool program(uint32_t address,const uint8_t* bytes,size_t n)override;
  bool sync()override;
  void inhibit()override{board_.inhibitDiagnosticTx();}
 private:
  bool inside(uint32_t address,size_t n)const;
  static bool same(const Region& a,const Region& b){return a.start==b.start&&a.size==b.size;}
  bool fault(){failed_=true;inhibit();return false;}
  Board& board_;const Region guard_,slots_;bool failed_=false;
};
}
