# Native CF/1 test boundaries

This is a synthetic regression fixture; it never probes a live connector.
The fixture covers: valid plan-only prefix; full recipe observations without
terminal; partial terminal payload; record CRC corruption; sequence mutation;
wrong independently supplied VIN; malformed VIN letters; request substitution
with a recomputed CRC; terminal present; incomplete plan; and safe non-promotion.

Tests are deliberately not executed during integration. The repository's
final protocol gate will invoke the native build and these assertions together
with Kotlin, C#, firmware and integration suites once the entire protocol is
ready for closure.
