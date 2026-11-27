#pragma once
#include "j1979_maintenance_task.h"
#include "j1979_fdcan_link.h"
#include "j1979_spi_port.h"

namespace cd {
// Explicit board composition. CAN admission is separate from SPI admission:
// the latter remains valid through the final journal acknowledgement.
class StmJ1979MaintenanceRuntime {
 public:
  StmJ1979MaintenanceRuntime(StmJ1979MaintenanceTask::Board& board,
      J1979MaintenanceJournal::Storage& storage,
      J1979MaintenanceJournal::Scratch& journalScratch,
      FDCAN_HandleTypeDef& can,SPI_HandleTypeDef& spi,
      StmJ1979FdcanLink::Admission canAdmission,
      StmJ1979FdcanLink::Fault canFault,
      StmJ1979SpiPort::Admission spiAdmission,
      StmJ1979SpiPort::Ready ready,
      StmJ1979SpiPort::Fault spiFault,
      J1979MaintenanceCampaign::Evidence& evidence,
      J1979CanReadExchange::Result& exchangeScratch,
      const J1979MaintenanceScope& scope)
      :can_(can,canAdmission,canFault),port_(spi,spiAdmission,ready,spiFault),
       task_(board,storage,journalScratch,can_,evidence,exchangeScratch,scope),
       endpoint_(task_,scope),slave_(port_,endpoint_) {}
  StmJ1979MaintenanceRuntime(const StmJ1979MaintenanceRuntime&)=delete;
  StmJ1979MaintenanceRuntime& operator=(const StmJ1979MaintenanceRuntime&)=delete;
  // SPI owner task only.
  bool begin(){return slave_.begin();}
  bool poll(){return slave_.poll();}
  bool completed(uint32_t bits,bool clean){return port_.completed(slave_,bits,clean);}
  void abort(){slave_.abort();}
  // Separately scheduled maintenance worker only.
  bool work(){return task_.work();}
  maintenance_link::SpiSlave::Phase phase()const{return slave_.phase();}
 private:
  StmJ1979FdcanLink can_;
  StmJ1979SpiPort port_;
  StmJ1979MaintenanceTask task_;
  maintenance_link::Endpoint endpoint_;
  maintenance_link::SpiSlave slave_;
};
}
