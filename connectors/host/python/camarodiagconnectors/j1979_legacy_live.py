"""Live numeric Mode 01 on one explicitly selected legacy response source.

The ELM link sends standardized functional requests on the documented
legacy network. Selection filters verified response headers; it cannot
restrict transmission to one ECU. The CAN application decoder is reused
for shared numeric payload shapes and a separately reconstructed VIN,
never for legacy headers, addressing, or evidence of CAN transport.
"""
from __future__ import annotations

import re
import time

from .j1979_legacy import PROTOCOLS
from .j1979_legacy_discovery import valid_source
from .j1979_legacy_frames import response_frames
from .j1979_live import Plan, record, legacy_numeric_pid
from .j1979_scaling_context import CONFIG_PIDS


class LegacyLiveLink:
    """Bound each logical query, including legacy VIN fragment acquisition.

    Preserve every original response and checksum-verified frame before
    normalizing a selected application payload. A missing reply never falls
    back to another ECU, the adapter's automatic protocol or cached data.
    """
    def __init__(self, exchange, protocol_check, protocol, source, *,
                 clock=time.monotonic, require_current=lambda:None):
        if protocol not in PROTOCOLS or not valid_source(source):
            raise ValueError("Explicit legacy protocol and response source required")
        self.exchange=exchange
        self.protocol_check=protocol_check
        self.protocol=protocol
        self.source=source
        self.clock=clock
        self.require_current=require_current
        self.exchanges=[]
        self.observed_protocol=None
        self.other_sources=set()
        self.logical_count=0

    def _remaining(self, deadline, maximum):
        self.require_current()
        left=deadline-self.clock()
        if left<=0:
            raise TimeoutError("Legacy logical query deadline")
        return min(maximum,left)

    def _read(self, command, deadline, logical):
        if not (command in ("0100","0900","0901","0902") or
            re.fullmatch(r"01[0-9A-F]{2}",command) and
            (int(command[2:],16)%32==0 or int(command[2:],16) in CONFIG_PIDS or legacy_numeric_pid(int(command[2:],16)))):
            raise ValueError("Unsupported request in legacy live recording")
        row={"logical_query":logical,"request_hex":command,"raw_response":None,
             "frames":[],"selected_records_hex":[],"status":"NOT_TESTED","detail":None}
        self.exchanges.append(row)
        try:
            raw=self.exchange(command,self._remaining(deadline,4.0))
            row["raw_response"]=raw
            self.require_current()
            if self.clock()>=deadline:
                row["detail"]="LATE_RESPONSE"
                raise TimeoutError("Late legacy response")
            frames=response_frames(self.protocol,command,raw)
            row["frames"]=[frame.evidence() for frame in frames]
            selected=[frame.payload for frame in frames if frame.source==self.source]
            self.other_sources.update(frame.source for frame in frames if frame.source!=self.source)
            row["selected_records_hex"]=[payload.hex().upper() for payload in selected]
            if not selected:
                row["status"]="TIMEOUT"
                row["detail"]="NO_SELECTED_RESPONSE"
                raise TimeoutError("No response from selected legacy source")
            row["status"]="CORRELATED"
            return selected
        except KeyboardInterrupt:
            row["detail"]="CANCELLED"
            raise
        except TimeoutError:
            if row["detail"] is None:
                row["detail"]="TIMEOUT"
            if row["status"]!="CORRELATED":
                row["status"]="TIMEOUT"
            raise
        except (OSError,ValueError,RuntimeError) as exc:
            row.update(status="FAILED",detail=type(exc).__name__)
            raise

    def _single(self, command, deadline, logical):
        records=self._read(command,deadline,logical)
        if len(records)!=1:
            self.exchanges[-1].update(status="FAILED",detail="AMBIGUOUS_SELECTED_SOURCE")
            raise ValueError("Multiple records from selected source")
        return records[0]

    def _probe(self, deadline, logical):
        row={"logical_query":logical,"request_hex":"ATDPN","raw_response":None,
             "frames":[],"selected_records_hex":[],"status":"NOT_TESTED","detail":None}
        self.exchanges.append(row)
        try:
            observed=self.protocol_check(self._remaining(deadline,2.0))
            row["observed_protocol"]=observed
            self.require_current()
            if self.clock()>=deadline:
                row["detail"]="LATE_PROTOCOL"
                raise TimeoutError("Late legacy protocol probe")
            if observed!=self.protocol:
                row.update(status="FAILED",detail="PROTOCOL_MISMATCH")
                raise ValueError("Legacy protocol changed")
            self.observed_protocol=observed
            row["status"]="PASSED"
        except KeyboardInterrupt:
            row["detail"]="CANCELLED"
            raise
        except (OSError,ValueError,RuntimeError) as exc:
            row["status"]="FAILED"
            row["detail"]=row["detail"] or type(exc).__name__
            raise

    @staticmethod
    def _terminal_negative(payload, mode):
        return len(payload)==3 and payload[:2]==bytes((0x7F,mode)) and payload[2] not in (0,0x78)

    def _vin(self, deadline, logical):
        # First establish a fresh response on the fixed legacy variant.
        initial=self._single("0100",deadline,logical)
        if len(initial)!=6 or initial[:2]!=b"\x41\x00":
            self.exchanges[-1].update(status="FAILED",detail="INITIAL_PID00_UNVERIFIED")
            raise ValueError("Initial legacy PID00 unverified")
        self.exchanges[-1]["status"]="PASSED"
        self._probe(deadline,logical)
        page=self._single("0900",deadline,logical)
        if self._terminal_negative(page,9):
            self.exchanges[-1]["status"]="NEGATIVE"
            return page
        if len(page)!=7 or page[:3]!=b"\x49\x00\x01" or page[3]&0xC0!=0xC0:
            self.exchanges[-1].update(status="FAILED",detail="VIN_INFO_TYPES_NOT_VERIFIED")
            raise ValueError("Legacy VIN count and data not advertised")
        self.exchanges[-1]["status"]="PASSED"
        count=self._single("0901",deadline,logical)
        if self._terminal_negative(count,9):
            self.exchanges[-1]["status"]="NEGATIVE"
            return count
        if count!=b"\x49\x01\x05":
            self.exchanges[-1].update(status="FAILED",detail="VIN_COUNT_INVALID")
            raise ValueError("Expected five numbered legacy VIN fragments")
        self.exchanges[-1]["status"]="PASSED"
        rows=self._read("0902",deadline,logical)
        if len(rows)==1 and self._terminal_negative(rows[0],9):
            self.exchanges[-1]["status"]="NEGATIVE"
            return rows[0]
        if len(rows)!=5 or any(len(row)!=7 or row[:3]!=bytes((0x49,2,index))
                              for index,row in enumerate(rows,1)):
            self.exchanges[-1].update(status="FAILED",detail="VIN_FRAGMENT_SEQUENCE_INVALID")
            raise ValueError("Incomplete or ambiguous selected-source VIN")
        data=b"".join(row[3:] for row in rows)
        if data[:3]!=bytes(3) or not re.fullmatch(rb"[A-HJ-NPR-Z0-9]{17}",data[3:]):
            self.exchanges[-1].update(status="FAILED",detail="VIN_PADDING_OR_ASCII_INVALID")
            raise ValueError("Invalid legacy VIN")
        self.exchanges[-1]["status"]="PASSED"
        # Preserve the original five records above. This normalized payload is
        # only for the common identity comparator; it was not an ISO-TP reply.
        return b"\x49\x02\x01"+data[3:]

    def query(self, request, timeout):
        if not 0<timeout<=20:
            raise ValueError("Invalid legacy live logical deadline")
        logical=self.logical_count
        self.logical_count+=1
        deadline=self.clock()+timeout
        if request==b"\x09\x02":
            return self._vin(deadline,logical)
        if len(request)!=2 or request[0]!=1 or not (request[1]%32==0 or legacy_numeric_pid(request[1])):
            raise ValueError("Only Mode 01 numeric values and support pages allowed")
        return self._single(request.hex().upper(),deadline,logical)


def record_legacy(exchange,protocol_check,protocol,source,plan:Plan,*,
                  clock=time.monotonic,sleep=time.sleep,require_current=lambda:None,
                  on_observation=lambda _:None):
    plan.validate()
    if not all(legacy_numeric_pid(pid) for pid in plan.pids):
        raise ValueError("Selected PID exceeds supported legacy application width")
    link=LegacyLiveLink(exchange,protocol_check,protocol,source,
                        clock=clock,require_current=require_current)
    report=record(link.query,plan,clock=clock,sleep=sleep,require_current=require_current,
                  on_observation=on_observation,
                  request_timeout=lambda request:20.0 if request==b"\x09\x02" else 4.0)
    # The scheduler has distinct admission and sample arrays. Sort their
    # actual acquisition order by the logical query number, not timestamps
    # (millisecond-rounded values can collide). Each logical query yields one
    # row in the scheduler; transport rows can be more numerous.
    report.update(scope="FUNCTIONAL_SELECTED_RESPONSE_LIVE",
                  expected_protocol=protocol,observed_protocol=link.observed_protocol,
                  protocol_name=PROTOCOLS[protocol],
                  selected_response_source=f"{source:02X}",
                  other_response_sources=[f"{x:02X}" for x in sorted(link.other_sources)],
                  response_attribution="CHECKSUM_VERIFIED_LEGACY_HEADER",
                  response_hex_representation="NORMALIZED_APPLICATION_PAYLOAD",
                  physically_addressed=False,transport_exchanges=link.exchanges)
    return report
