#pragma once
#include "j1979_spi_port.h"
#include "../../shared/j1979_maintenance_archive_link.h"
#include "../../shared/j1979_maintenance_archive_owner.h"

namespace cd {
// No FDCAN object, start method or maintenance worker exists in this
// composition. The qualified owner verifies physical TX cutoff first.
class StmJ1979MaintenanceArchiveRuntime {
 public:
  StmJ1979MaintenanceArchiveRuntime(J1979MaintenanceStorage& storage,J1979MaintenanceArchiveOwner::Board& board,
      archive_link::Endpoint::Connection& connection,SPI_HandleTypeDef& spi,
      StmJ1979SpiPort::Admission admission,StmJ1979SpiPort::Ready ready,StmJ1979SpiPort::Fault fault,
      const J1979MaintenanceScope& scope):
    owner_(board,scope),source_(storage,owner_,scope),endpoint_(source_,connection,scope),
    port_(spi,admission,ready,fault),slave_(port_,endpoint_){}
  bool begin(){return slave_.begin();}
  bool poll(){return slave_.poll();}
  bool completed(uint32_t bits,bool clean){return port_.completed(slave_,bits,clean);}
  void abort(){slave_.abort();}
  archive_link::SpiSlave::Phase phase()const{return slave_.phase();}
 private:
  J1979MaintenanceArchiveOwner owner_;J1979MaintenanceArchive::LocalSource source_;archive_link::Endpoint endpoint_;
  StmJ1979SpiPort port_;archive_link::SpiSlave slave_;
};
}
