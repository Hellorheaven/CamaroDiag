# Native Camaro signal catalogue

## Status

The first-party catalogue is introduced separately from the imported OBDb dataset:

- Canonical runtime model: `CamaroSignalCatalog.kt`
- Asset: `camaro-native-signalset-v1.json`
- Canonical model tests: `CamaroSignalCatalogTest.kt`
- The earlier `NativeCamaroSignalCatalog.kt` model and its tests are retained for compatibility with the initial catalogue foundation; new definitions must use the canonical JSON schema and model.

The canonical native asset intentionally starts empty. This is deliberate: no Camaro-specific DID, ECU CAN address, scaling rule or model-year applicability is promoted to a first-party definition without evidence. An empty verified catalogue is safer than plausible-looking but unverified diagnostic data.

## Existing OBDb catalogue is preserved

The existing community catalogue remains available through `ObdbCamaroSignalCatalog.kt` and `camaro-obdb-signalset-v3.json`. Its existing UI, decoding, attribution and CC BY-SA 4.0 licensing are not removed or silently relicensed by this work.

Do not copy, translate, rename, reformat or derive records from the OBDb JSON into the native asset and call them independent. If OBDb-derived material is ever distributed in adapted form, retain the required attribution and ShareAlike obligations. The OBDb data and its licence remain separate from first-party code and data.

## Promotion requirements

A native definition may become executable only when all of the following are recorded:

1. Exact Camaro model year and engine/RPO applicability.
2. UDS service and DID, with a complete and documented response layout.
3. Physical request and response CAN IDs in valid 11-bit format, independently confirmed for that configuration.
4. Bit offset, bit length, byte/bit order, signedness, scale, offset, unit and any sentinel values supported by a repeatable decoding test vector.
5. At least one traceable evidence reference and a named vehicle configuration on which the signal was verified.
6. A test covering the raw response bytes and expected decoded value.

The canonical runtime model deliberately enables only UDS ReadDataByIdentifier (0x22) definitions with valid physical addressing. Other services, 29-bit addressing, extended addressing, session/security requirements and write operations remain out of scope until separately implemented and validated.

## Evidence hierarchy

Prefer, in order:

- GM service information or OEM documentation that is legitimately available for the exact vehicle/controller.
- SAE/ISO standards for standardized protocol semantics (these do not, by themselves, prove a Camaro-specific DID or CAN address).
- Captured request/response traces from a clearly identified Camaro configuration, with repeatable decoding.
- Independent corroboration from another matching vehicle/configuration.

A community database can be useful as a research lead, but is not sufficient by itself to mark a first-party Camaro signal as vehicle-verified.

## Safety boundary

A missing definition or missing ECU response must remain unavailable. Never infer an ECU address from a familiar GM module name, extrapolate a DID across model years, or turn a nominal calibration family into proof of a controller identity. No BCM, TCM, EBCM/ABS, IPC, SDM or other enhanced module address is asserted by this catalogue.

Calibration binary reads, table edits, security access, writes and flashing are not enabled by this signal catalogue.
