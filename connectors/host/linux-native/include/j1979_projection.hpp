#pragma once
#include "j1979_multi.hpp"
#include "j1979_status.hpp"
#include <cstdio>
namespace cd::j1979 {
// Strict validation of the known catalogue on correlated Mode 01 responses.
// Unknown definitions remain RAW rather than acquiring a guessed width.
bool known_shape(const Observation&);
bool write_projections(std::FILE*,std::span<const Observation>,bool identity_rechecked);
}
