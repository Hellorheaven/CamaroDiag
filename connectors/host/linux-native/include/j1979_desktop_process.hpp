#pragma once
#include "j1979_desktop_commands.hpp"
#include <cstdint>
#include <string>
#include <sys/types.h>
namespace cd::desktop {
// All process management is local. No shell, no elevated privileges, no
// automatic response to consent prompts and no implicit restart.
class Process {
    int master_{-1};
    pid_t child_{-1};
    bool physical_{},prompt_seen_{},approved_{},cancelled_{},eof_{};
    std::string prompt_tail_;
    void close_master()noexcept;
public:
    Process()=default;
    ~Process();
    Process(const Process&)=delete;
    Process& operator=(const Process&)=delete;
    void start(const std::string& executable,const Invocation&);
    int descriptor()const noexcept{return master_;}
    bool running()const noexcept{return child_>0;}
    bool awaiting_consent()const noexcept{return running()&&physical_&&prompt_seen_&&!approved_&&!cancelled_;}
    bool cancelled()const noexcept{return cancelled_;}
    bool eof()const noexcept{return eof_;}
    std::string drain();
    bool approve();
    void cancel()noexcept;
    // Nonblocking reap. Exit code 128+signal for abnormal termination.
    bool poll(int& status);
};
}
