#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace cd::cm1 {
using Bytes=std::span<const std::uint8_t>;
constexpr std::size_t header_size=12, scope_size=31, summary_size=20, row_size=18;
constexpr std::size_t max_rows=16, max_reply=512;
constexpr std::size_t max_record=header_size+scope_size+summary_size+max_rows*(row_size+max_reply)+4;
constexpr std::size_t max_journal=4*max_record, max_report=1024*1024;
struct Scope {
    unsigned operation{};
    bool extended{};
    std::uint32_t tx{},rx{},budget{};
    std::string vin;
    bool operator==(const Scope&) const=default;
};
struct Event {
    unsigned stage{},status{},timeout{};
    std::uint32_t start{},end{};
    std::vector<std::uint8_t> request,response,wire;
};
struct Summary {
    unsigned outcome{},reason{},count{},nrc{},a{},b{};
    std::uint32_t duration{};
    bool operator==(const Summary&) const=default;
};
struct Inspection {
    Scope scope;
    std::vector<unsigned> records;
    std::vector<Event> observations;
    std::optional<Summary> terminal;
    bool baseline{},intent{},prefix{};
    std::size_t verified_bytes{},total_bytes{};
    unsigned next_kind{};
    [[nodiscard]] bool recovery_required() const {return !terminal || (terminal->b&32)!=0;}
};
/* Offline only; no serial access, no command authorization, no retry. */
Inspection inspect(Bytes raw,std::string_view independent_vin,bool prefix);
std::string report(const Inspection& evidence,std::string_view journal_digest);
}
