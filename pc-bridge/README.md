# CamaroDiag J2534 PC Bridge

This Windows companion connects CamaroDiag on Android to an installed SAE J2534 PassThru driver, including the VXDIAG VCX SE and VCX Nano when their Windows J2534 driver is installed and registered.

## Current scope

- Windows 10/11 x64 console application (.NET 8).
- Discovers J2534 providers registered under `HKLM\SOFTWARE\PassThruSupport.04.04` or `PassThruSupport.05.00`.
- Uses the provider's `FunctionLibrary` DLL through the standard J2534 API.
- Opens ISO 15765-2 HS-CAN (11/29-bit, 250/500 kbit/s) or J2534-2 SW-ISO15765 GMLAN (11/29-bit, 62.5 kbit/s preset).
- Creates a J2534 flow-control filter and sends/receives complete UDS payloads; the J2534 provider handles ISO-TP framing and flow control.
- One Android client at a time, protected by a random pairing token.
- Read-only service gate: UDS 0x22 (ReadDataByIdentifier), 0x19 (ReadDTCInformation), and 0x3E (TesterPresent) only.

This is a generic J2534 bridge, not a VXDIAG-specific driver. The VCX hardware driver remains installed on the Windows PC. It does not replace VX Manager or the vendor J2534 DLL.

## Build

Install the .NET 8 SDK, then run from this directory:

```powershell
dotnet build .\CamaroDiag.J2534Bridge\CamaroDiag.J2534Bridge.csproj -c Release
dotnet publish .\CamaroDiag.J2534Bridge\CamaroDiag.J2534Bridge.csproj -c Release -r win-x64 --self-contained false
```

If the installed vendor PassThru DLL is 32-bit only, publish and run an x86 build instead:

```powershell
dotnet publish .\CamaroDiag.J2534Bridge\CamaroDiag.J2534Bridge.csproj -c Release -r win-x86 --self-contained false
```

The process architecture must match the native J2534 DLL architecture.

## Run and pair

1. Install the correct VXDIAG Windows driver/VX Manager for the exact VCX SE or Nano variant.
2. Connect the VCX to the PC and make sure Windows recognizes it.
3. Run `CamaroDiag.J2534Bridge.exe` from the publish folder. Select the registered J2534 provider if more than one is listed.
4. The console displays a random pairing token and TCP port (default `35001`).
5. Put the PC and Android device on the same trusted private Wi-Fi/LAN. Find the PC IPv4 address with `ipconfig`.
6. In CamaroDiag > ECU / Calibration, enter the PC IPv4 address, port and token, then enter the physical ECU CAN IDs and connect.
7. Allow the bridge through Windows Defender Firewall on Private networks only if prompted.

Do not expose port 35001 to the Internet or configure router port forwarding. The pairing token is sent over the local TCP connection; use a trusted private network.

## Hardware and protocol limitations

- The bridge currently enables HS-CAN ISO15765 at 250/500 kbit/s and optional SW-ISO15765 at the J2534-2 62.5 kbit/s preset when the provider accepts protocol ID 0x8007. SW-CAN/GMLAN single-wire and CAN FD are not enabled by this generic J2534 path.
- The VCX SE is the preferred VXDIAG candidate for the J2534 PC bridge; VXDIAG lists SAE-J2411 SWCAN support for VCX SE, but the installed PassThru provider must expose J2534-2 SW_ISO15765_PS for this app to use it. VCX Nano variants differ by hardware, pins and transport; SW-CAN support is not assumed and depends on the exact Nano and registered Windows driver.
- The third-party OpenVCX project is an alternative J2534 DLL for certain USB VCX Nano units, not an Android bridge and not a universal VCX SE driver. Its maintainer documents HS-CAN/MS-CAN and ISO15765 but not SW-CAN or CAN FD. It is not bundled or redistributed here.
- A driver registering successfully does not prove that every Camaro ECU/network is supported. Confirm ignition state, bus pins, request/response IDs, CAN bitrate and ECU response on the vehicle.
- No OEM seed/key, memory layout, checksum, flash sequence, recovery procedure or ECU write service is included. The bridge blocks those UDS services even if the installed driver can technically transmit them.

## Wire protocol

UTF-8 JSON Lines over TCP. Every request carries the pairing token.

- `open`: opens the selected registered provider, ISO15765 channel, and a flow-control filter for the request/response CAN ID pair.
- `exchange`: sends one UDS payload and returns the matching ECU payload.
- `sendOnly`: sends an allowed TesterPresent payload without waiting for a response.
- `close`: releases the J2534 channel.

The Android transport in CamaroDiag implements this protocol; no ELM command emulation is used.
