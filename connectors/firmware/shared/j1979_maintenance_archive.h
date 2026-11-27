#pragma once
#include "j1979_maintenance_storage.h"

namespace cd {
// Read-only recovery export, separate from maintenance execution.
// This class cannot start CAN, grant a command intent, or clear a latch.
// Board-provided admission must guarantee an exclusive read-only lease,
// independently verified TX cutoff, and no active maintenance writer.
class J1979MaintenanceArchive {
 public:
  using Scope=J1979MaintenanceScope;
  using Storage=J1979MaintenanceStorage;
  using Snapshot=Storage::Archive;
  struct Info {
    uint32_t bytes=0;
    uint8_t records=0;
    bool terminal=false,damagedTail=false,recoveryRequired=true;
    bool valid()const{
      return bytes>=47&&bytes<=J1979MaintenanceJournal::MaximumJournal&&records>=1&&records<=4&&
        (!terminal||records>=2)&&((terminal&&!damagedTail)||recoveryRequired);
    }
    bool same(const Info& b)const{
      return bytes==b.bytes&&records==b.records&&terminal==b.terminal&&
        damagedTail==b.damagedTail&&recoveryRequired==b.recoveryRequired;
    }
  };
  class Source {
   public:
    virtual ~Source()=default;
    virtual bool open(const Scope& scope,Info& info)=0;
    virtual bool read(size_t offset,uint8_t* bytes,size_t n)=0;
    virtual bool current()=0;
    virtual bool finish()=0;
    virtual void close()=0;
  };
  class Owner {
   public:
    virtual ~Owner()=default;
    // The scope is selected independently on the board. This grants
    // access to the previous journal only, even if recovery is latched.
    virtual bool acquire(const Scope& scope)=0;
    virtual bool admitted(const Scope& scope)=0;
    virtual void release()=0;
  };
  class LocalSource final:public Source {
   public:
    LocalSource(Storage& storage,Owner& owner,const Scope& approved):
      storage_(storage),owner_(owner),scope_(approved){}
    LocalSource(const LocalSource&)=delete;
    LocalSource& operator=(const LocalSource&)=delete;
    ~LocalSource(){close();}
    bool open(const Scope& scope,Info& info)override{
      info=Info{};
      if(attempted_||closed_||!scope_.same(scope))return false;
      attempted_=true;
      // Release even a partially acquired lease on every exit.
      if(!owner_.acquire(scope_)||!owner_.admitted(scope_)||
         !storage_.archive(snapshot_)||!snapshot_.scope.same(scope_)||!owner_.admitted(scope_)){
        close();return false;
      }
      Info result;
      result.bytes=uint32_t(snapshot_.size);result.records=uint8_t(snapshot_.records);
      result.terminal=snapshot_.terminal;result.damagedTail=snapshot_.damagedTail;
      result.recoveryRequired=snapshot_.recoveryRequired;
      if(!result.valid()){close();return false;}
      open_=true;info=result;return true;
    }
    bool current()override{return open_&&!closed_&&owner_.admitted(scope_);}
    bool read(size_t offset,uint8_t* out,size_t n)override{
      if(!current()||offset!=offset_||!n||!storage_.readArchive(snapshot_,offset,out,n)||!current()){
        close();return false;
      }
      offset_+=n;return true;
    }
    bool finish()override{return current()&&offset_==snapshot_.size;}
    void close()override{
      if(!closed_){closed_=true;open_=false;if(attempted_)owner_.release();}
    }
   private:
    Storage& storage_;Owner& owner_;const Scope scope_;
    Snapshot snapshot_{};size_t offset_=0;
    bool attempted_=false,open_=false,closed_=false;
  };
};
}
