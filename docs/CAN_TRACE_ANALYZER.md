# Offline CAN / UDS trace analyzer

CamaroDiag can inspect text captures without a connected vehicle or diagnostic adapter.

## Supported input

### Vector ASC
The parser accepts standard classic-CAN records with timestamp, channel, arbitration ID, direction, DLC and data bytes, for example:

```text
0.000000 1 7E0 Tx d 8 03 22 F1 90 00 00 00 00
0.001000 1 7E8 Rx d 8 07 62 F1 90 31 47 30 31
```

### ELM327 monitor text

The analyzer accepts header-enabled ELM327 monitor output saved as a text file. Each line must contain a CAN ID, a hexadecimal DLC and the data bytes, for example:

```text
7E0 04 03 22 F1 90
7E8 08 07 62 F1 90 31 47 30 31
```

ELM monitor output usually has no timestamp or TX/RX marker; the analyzer therefore labels its direction as unknown rather than guessing. Enable CAN headers on the adapter before capturing. Not every ELM327 clone supports monitor mode or complete CAN-frame output.

### CSV
CSV files must include a CAN ID column and a data/payload column. Optional columns are timestamp and direction:

```csv
timestamp,can_id,direction,data
0.000,0x7E0,Tx,"03 22 F1 90 00 00 00 00"
0.010,0x7E8,Rx,"07 62 F1 90 31 47 30 31"
```

The parser also accepts semicolon-delimited CSV and separate `data0`…`data15` byte columns.

## Analysis

- Parses 11-bit and 29-bit arbitration IDs.
- Reassembles ISO-TP Single Frames, First Frames and Consecutive Frames.
- Checks consecutive-frame sequence numbers and reports incomplete messages.
- Identifies UDS services, positive responses and negative response codes.
- Lists observed ReadDataByIdentifier (0x22 / 0x62) and WriteDataByIdentifier (0x2E / 0x6E) DIDs with CAN ID and TX/RX direction.
- Ignores ISO-TP Flow Control frames as transport metadata.

## Limits

- Binary Vector BLF is not supported yet. Export it to ASC or CSV first.
- CAN FD escape-length Single Frames and ISO-TP extended-length First Frames are not decoded yet.
- The analyzer does not infer an ECU identity from a CAN ID, pair requests and responses into a verified ECU profile, or decode proprietary signal scaling.
- A DID observed in a trace is only a candidate. It is not automatically added to the native signal catalogue and never becomes executable from trace observation alone.
- Captures may contain VINs, calibration IDs, serial numbers or other sensitive vehicle data. Review and redact traces before sharing them publicly.

No CAN or UDS command is transmitted by this feature. Analysis is entirely local and read-only.
