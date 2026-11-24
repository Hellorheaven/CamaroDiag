"""USB serial discovery CLI for the isolated CamaroDiagConnectors prototype.

Port enumeration and --help do not open any device. Explicit verification
sends only public prototype discovery requests, never ECU traffic.
"""

import argparse
import json

from .protocol import DiscoveryClient
from .binary_discovery import BinaryDiscoveryClient


def _port_summary(info) -> str:
    """One escaped JSON line; untrusted USB product descriptions cannot inject lines."""
    vid = getattr(info, "vid", None)
    pid = getattr(info, "pid", None)
    return json.dumps({
        "port": str(info.device),
        "description": str(getattr(info, "description", "") or ""),
        "vid": f"{vid:04X}" if isinstance(vid, int) and 0 <= vid <= 65535 else None,
        "pid": f"{pid:04X}" if isinstance(pid, int) and 0 <= pid <= 65535 else None,
        "authenticated": False,
        "ecu_access": False,
    }, ensure_ascii=True, sort_keys=True)


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(
        description="CamaroDiagConnectors USB discovery (never enables ECU access)",
        epilog="Examples: --list-ports | --port COM5 verify | --port /dev/ttyACM0 verify --binary"
    )
    parser.add_argument("--port", help="Explicit serial port, e.g. COM5 or /dev/ttyACM0")
    parser.add_argument("command", nargs="?", choices=["ping", "caps", "status", "verify"])
    parser.add_argument("--binary", action="store_true",
                        help="Explicit binary prototype firmware mode; verify only")
    parser.add_argument("--list-ports", action="store_true",
                        help="List local serial ports without opening or probing any device")
    args = parser.parse_args(argv)
    if args.list_ports:
        if args.command or args.port or args.binary:
            parser.error("--list-ports cannot be combined with a port, a command, or --binary")
        try:
            from serial.tools import list_ports  # type: ignore[import-untyped]
        except ImportError as exc:
            raise SystemExit("Install pyserial: python -m pip install pyserial") from exc
        for info in sorted(list_ports.comports(), key=lambda item: str(item.device).casefold()):
            print(_port_summary(info))
        return
    if not args.port or args.command is None:
        parser.error("An explicit --port and one discovery command are required")
    if args.binary and args.command != "verify":
        parser.error("Binary prototype requires verify; reset the board before each new session")
    try:
        import serial  # type: ignore[import-untyped]
    except ImportError as exc:
        raise SystemExit("Install pyserial: python -m pip install pyserial") from exc
    with serial.Serial(args.port, baudrate=115200, timeout=2, write_timeout=2) as port:
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
