/* PlatformIO compiles source files in src/. Import the shared portable
 * ISO/SAE C implementation as a single C translation unit, not as a
 * C++ wrapper or a duplicated fork. No discovery entrypoint or vehicle
 * communication is enabled merely by linking these pure functions.
 */
#include "../../shared/j1979_native_c.c"
