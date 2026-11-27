#pragma once
#include "j1979_cj1.hpp"
#include "cf1_usb.h"
#include <array>
#include <span>
namespace cd::cu1 {
using Nonce=std::array<std::uint8_t,32>;
using Hello=std::array<std::uint8_t,49>;
using Challenge=std::array<std::uint8_t,68>;
using Proof=std::array<std::uint8_t,36>;
bool hello(const cj1::Profile&,const Nonce&,Hello&);
bool proof(const Hello&,const Challenge&,std::span<const std::uint8_t,32>,Proof&);
int capture(const char* port,const cj1::Profile&,std::span<const std::uint8_t,32> key,
            const char* destination,cf1_usb_capture_result&,char* error,std::size_t capacity);
}
