#pragma once
#include "j1979_legacy.hpp"
namespace cd::legacy {
enum class ApplicationShape {invalid,negative,positive,raw};
ApplicationShape pid_shape(Bytes request,Bytes response);
}
