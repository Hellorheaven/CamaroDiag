#ifndef CAMARODIAG_J1979_CONTEXT_HPP
#define CAMARODIAG_J1979_CONTEXT_HPP
#include <array>
#include <cstdint>
#include <cstdio>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace cd::j1979 {
using Bytes = std::span<const std::uint8_t>;

/* Non-owning views into a single selected ECU's immutable observations.
 * The caller must not combine sessions, controllers or anonymous responders. */
struct Observation {
    Bytes request;
    Bytes response;
    bool accepted{};
    bool admission{true};
};

enum class RangeState { unverified, default_unsupported, ecu_configuration };
struct Range {
    RangeState state{RangeState::unverified};
    std::array<std::uint8_t, 4> data{};
};
enum class LayoutState { unverified, not_advertised, verified };
struct Layout {
    LayoutState state{LayoutState::unverified};
    std::optional<std::uint8_t> value;
};

struct ScalingContext {
    Range pid4f;
    Range pid50;
};
struct Signal {
    std::string name;
    std::string unit;
    unsigned raw{};
    std::optional<double> number;
    std::string_view scale_source;
};
struct ScalingRecord {
    unsigned configuration_pid{};
    std::vector<Signal> signals;
};
struct TrimSignal {
    unsigned bank{};
    unsigned raw{};
    std::string name;
    std::optional<double> number;
};
struct TrimRecord {
    std::optional<unsigned> frame;
    std::optional<bool> upper_bank_verified;
    std::vector<TrimSignal> signals;
};
struct Monitor {
    std::string_view name;
    std::optional<bool> supported;
    std::optional<bool> enabled;
    std::optional<bool> complete;
    bool enabled_bit{};
    bool incomplete_bit{};
    bool consistent{};
};
struct DriveCycle {
    std::array<std::uint8_t, 4> raw{};
    bool support_verified{};
    bool compression_ignition{};
    bool consistent{};
    std::vector<Monitor> monitors;
};
struct Context {
    ScalingContext scaling;
    Layout layout;
    std::optional<std::array<std::uint8_t, 4>> baseline;
    std::optional<DriveCycle> cycle;
};

std::optional<unsigned> dependency(unsigned pid);
bool trim_pid(unsigned pid);
std::optional<Bytes> payload(const Observation& observation);
Context derive_context(std::span<const Observation> observations);
std::optional<ScalingRecord> scale(const Observation& observation, const ScalingContext& context);
std::optional<TrimRecord> trims(const Observation& observation, const Layout& layout);
std::optional<DriveCycle> drive_cycle(Bytes data, std::optional<Bytes> baseline);

/* Write a JSON object (without leading separator). It supplements raw data,
 * never replaces it and never asserts physical ECU provenance. */
bool write_context_json(std::FILE* stream, std::span<const Observation> observations, bool identity_rechecked);
}
#endif
