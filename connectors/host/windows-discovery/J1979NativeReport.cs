using System.Globalization;
using System.Text.Json;

namespace CamaroDiagConnectors.Windows;

internal static class J1979NativeReport
{
    internal static void Write(Stream output,J1979UsbCapture.Result result,string hostVersion,
        string vehicle,string ecu,string connector,uint tx,uint rx,bool extended,int budget,
        long durationMs,string rawCaptureName,bool cancelled=false)
    {
        JsonSerializer.Serialize(output,new{
            schema="camarodiag-functional-j1979-native-usb-v1",protocol="SAE-J1979",
            source="LIVE_USB_CU1",host_version=hostVersion,captured_at_utc=DateTimeOffset.UtcNow,
            duration_ms=durationMs,declared_vehicle=vehicle,declared_ecu=ecu,declared_connector=connector,
            request_can_id=tx.ToString("X",CultureInfo.InvariantCulture),response_can_id=rx.ToString("X",CultureInfo.InvariantCulture),
            extended,budget_ms=budget,device_credential_authenticated=result.DeviceCredentialAuthenticated,
            frame_integrity_verified=result.FrameIntegrityVerified,capture_integrity_verified=result.CaptureIntegrityVerified&&!cancelled,
            evidence_authenticated=false,physical_vehicle_verified=false,observed_vin=result.ObservedVin,cancelled,
            steps=result.Steps.Select(s=>new{sequence=s.Sequence,request_hex=s.RequestHex,response_hex=s.ResponseHex,
                status=s.Status,duration_ms=s.DurationMs,dtc_codes=s.DtcCodes,
                negative_response_code=s.NegativeResponseCode}),
            summary=result.Summary is { } summary?new{
                end=summary.End,steps=summary.Steps,
                transport_observed=(summary.Flags&1)!=0,pid_inventory_complete=(summary.Flags&2)!=0,
                freeze_trigger_observed=(summary.Flags&4)!=0,freeze_frame_present=(summary.Flags&8)!=0,
                freeze_inventory_complete=(summary.Flags&16)!=0,monitor_inventory_complete=(summary.Flags&32)!=0,
                control_inventory_complete=(summary.Flags&64)!=0,identity_inventory_complete=(summary.Flags&128)!=0
            }:null,
            records=result.Records,raw_capture_name=rawCaptureName,error=result.Error
        },new JsonSerializerOptions{WriteIndented=true});
    }
}
