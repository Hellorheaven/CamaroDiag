#pragma once
#include "j1979_cm1.hpp"
#include <array>
#include <span>
#include <optional>

namespace cd::cu45 {
enum class Mode : std::uint8_t { maintenance=4, archive=5 };
using Nonce=std::array<std::uint8_t,32>;
using Hello=std::array<std::uint8_t,67>;
using Challenge=std::array<std::uint8_t,68>;
using Proof=std::array<std::uint8_t,36>;
using Envelope=std::array<std::uint8_t,12>;
using ScopeWire=std::array<std::uint8_t,cm1::scope_size>;
bool valid_scope(const cm1::Scope&);
bool encode_scope(const cm1::Scope&,ScopeWire&);
bool hello(Mode,const cm1::Scope&,const Nonce&,Hello&);
bool proof(Mode,const Hello&,const Challenge&,std::span<const std::uint8_t,32>,Proof&);
struct Declaration {
    std::size_t length{};
    unsigned records{};
    bool terminal{},damaged{},recovery{};
};
std::optional<Declaration> declaration(Mode,const Envelope&);
bool receipt(const Envelope&,cm1::Bytes);
std::optional<cm1::Inspection> inspect(Mode,const cm1::Scope&,const Declaration&,cm1::Bytes);
}
