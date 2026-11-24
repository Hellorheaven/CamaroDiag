# CamaroDiag — 2012 LFX FlexFuel (read-only)

## What this observation does

For a selected **2012 Camaro with the LFX engine**, CamaroDiag reads fuel information **actually advertised** by the ECU through SAE Mode 01 OBD-II. This is a one-time survey and never sends a programming command.

## Equipment and connection

- A compatible, physically connected ELM/STN OBD adapter, or a physical Windows J2534 bridge with approved standard OBD read capability.
- The 2012 Gen 5 Camaro LFX vehicle selected in the garage.
- An ECU that actually responds to the requested PIDs. Not every 2012 LFX controller or calibration is assumed to support the ethanol PID.

## How to use it

1. Connect a real adapter and select the vehicle.
2. Open **Calibration Workshop → 2012 LFX FlexFuel**.
3. Select **Read ECU ethanol and fuel trims (read-only)**.
4. Inspect **ECU-reported ethanol content** (PID 52), plus bank 1 and bank 2 short/long-term fuel trims (PIDs 06, 07, 08 and 09).
5. For repeated live data where supported, use **Live Telemetry**. The workshop survey does not continuously poll the ECU.

Each PID is either shown with a value from a valid reply, **not advertised by the ECU**, or **advertised but no valid reply**. Missing information is **never displayed as a fake 0% reading**. The screen warns when the selected VIN has not been matched against an independently read physical ECU identity.

## Observation history and CSV export

You can press **Read ECU ethanol and fuel trims** repeatedly during the same connection. The workshop keeps **up to the 512 most recent observations** in memory, displays the number of valid ethanol readings and the observed minimum/maximum, and offers an **Export fuel readings (CSV)** action.

The CSV includes the selected VIN, session identifier, read source, timestamps, PIDs, availability, reported percentages and raw bytes when present. Unsupported PIDs and invalid/missing replies remain explicitly distinguishable: an empty numerical cell does not mean zero.

Changing vehicles or connections, or starting a nonchronological series, resets the current history. Only observations still held in memory are exported. This is not durable telemetry recording or continuous automatic acquisition.

**Qualification**: compilation and physical hardware testing of this export path remain pending on the current PR. These statistics do not establish FlexFuel activation.

## What it does not prove

A plausible ethanol reading does not establish a fitted physical ethanol sensor, enabled FlexFuel calibration, E85 compatibility, or valid ECU map. STFT/LTFT fuel trims are not ethanol measurements. Catalog ECU service part numbers do not identify the installed firmware.

**This operation does not enable E85, write any ECU value, change calibration maps or restore firmware.** A physical vehicle/hardware validation is needed before claiming such compatibility.
