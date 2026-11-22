"""Read-only USB serial discovery CLI for the ESP32-S3 prototype."""

import argparse

from .protocol import DiscoveryClient
from .binary_discovery import BinaryDiscoveryClient
from .checkpoint import retrieve_checkpoint, save_checkpoint


def main() -> None:
    parser = argparse.ArgumentParser(description="CamaroDiagConnectors USB discovery (no ECU writes)")
    parser.add_argument("--port", required=True, help="Serial port, e.g. COM5")
    parser.add_argument("command", choices=["ping", "caps", "status", "verify", "checkpoint"])
    parser.add_argument("--binary", action="store_true", help="Explicit binary prototype firmware mode; verify only")
    parser.add_argument("--output", help="New CDJ/1 file (checkpoint command only)")
    parser.add_argument("--isolated", action="store_true", help="Confirm dedicated isolated checkpoint firmware, no ECU connected")
    args = parser.parse_args()
    if args.command == "checkpoint" and (not args.output or not args.isolated or args.binary):
        parser.error("Checkpoint requires --isolated --output FILE and the dedicated service build")
    if args.command != "checkpoint" and (args.output or args.isolated):
        parser.error("Checkpoint options only apply to checkpoint command")
    if args.binary and args.command != "verify":
        parser.error("Binary prototype requires verify; reset the board before each new session")
    try:
        import serial  # type: ignore[import-untyped]
    except ImportError as exc:
        raise SystemExit("Install pyserial: python -m pip install pyserial") from exc
    with serial.Serial(args.port, baudrate=115200, timeout=2, write_timeout=2) as port:
        if args.command == "checkpoint":
            record = retrieve_checkpoint(port)
            if record is None:
                print("No NVS checkpoint; this does not authorize programming")
                return
            save_checkpoint(args.output, record)
            print("Saved unauthenticated CDJ/1 evidence; inspect in CamaroDiag Expert, never auto-resume")
            return
        if args.binary:
            for response in BinaryDiscoveryClient(port).verify_read_only():
                print(response)
            print("Binary prototype verified (not cryptographically authenticated)")
            return
        client = DiscoveryClient(port)
        if args.command == "verify":
            client.verify_read_only()
            print("Verified prototype mode: all capabilities disabled")
        else:
            print(client.execute(args.command.upper()))


if __name__ == "__main__":
    main()
