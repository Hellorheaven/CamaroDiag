# Third-party data and references

## OBDb — Chevrolet Camaro signal set

- Source repository: https://github.com/OBDb/Chevrolet-Camaro
- Imported file: `signalsets/v3/default.json`
- Source commit: `6771593698dff29cc0c6e8d6219ff360b2891883`
- License: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)
- License text: [third_party/obdb/LICENSE-CC-BY-SA-4.0.txt](third_party/obdb/LICENSE-CC-BY-SA-4.0.txt)

The signal-set JSON is included as community-contributed vehicle data. Its original identifiers, signal names, command addresses, year filters and scaling definitions are preserved. CamaroDiag adds a parser/decoder and UI around this source data. Attribution is retained here and in the diagnostic workspace. The data is not OEM-certified; individual definitions may be incomplete or inapplicable to a particular VIN, ECU hardware number, CAL ID or OS. Unsupported CAN addressing is deliberately not guessed or transmitted.

## OpenVCX — reference only, not bundled

- Repository: https://github.com/erik683/OpenVCX
- The project describes an independent Windows J2534 DLL for certain USB VCX Nano variants.
- No OpenVCX source, binary or driver code is copied into CamaroDiag.
- Its own documentation reports HS-CAN/MS-CAN and ISO15765 work on the maintainer's tested Nano, while SW-CAN and CAN FD are not supported by that tested hardware. This is not a compatibility guarantee for every VCX Nano or Camaro.

The CamaroDiag PC bridge uses the installed, registered J2534 provider selected on the Windows PC. OpenVCX is only a possible alternative provider for a compatible USB Nano, subject to its own license and hardware requirements.

## monitoramento_Carro — reviewed, not copied

- Repository: https://github.com/Carlosh007/monitoramento_Carro
- Its dashboard and telemetry screens were reviewed for UI ideas.
- The repository's own source uses hard-coded/simulated telemetry and sample DTCs; it does not provide a real OBD/J2534 transport.
- No source code or assets are copied into CamaroDiag.
