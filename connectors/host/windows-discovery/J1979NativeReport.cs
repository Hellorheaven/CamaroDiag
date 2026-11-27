using System.Globalization;
using System.Text.Json;
using CamaroDiag.J2534Bridge;

namespace CamaroDiagConnectors.Windows;

internal static class J1979NativeReport
{
    internal static string VinMatch(J1979UsbCapture.Result result,string? expectedVin,bool cancelled=false)=>
        expectedVin is null?"NOT_REQUESTED":cancelled||!result.DeviceCredentialAuthenticated||
        !result.CaptureIntegrityVerified||result.ObservedVin is null?"UNVERIFIED":
        string.Equals(result.ObservedVin,expectedVin,StringComparison.Ordinal)?"MATCH":"MISMATCH";

    internal static void Write(Stream output,J1979UsbCapture.Result result,string hostVersion,
        string vehicle,string ecu,string connector,uint tx,uint rx,bool extended,int budget,
        long durationMs,string rawCaptureName,bool cancelled=false,string? expectedVin=null)
    {
        JsonSerializer.Serialize(output,new{
            schema="camarodiag-functional-j1979-native-usb-v1",protocol="SAE-J1979",
            source="LIVE_USB_CU1",host_version=hostVersion,captured_at_utc=DateTimeOffset.UtcNow,
            duration_ms=durationMs,declared_vehicle=vehicle,declared_ecu=ecu,declared_connector=connector,
            request_can_id=tx.ToString("X",CultureInfo.InvariantCulture),response_can_id=rx.ToString("X",CultureInfo.InvariantCulture),
            extended,budget_ms=budget,device_credential_authenticated=result.DeviceCredentialAuthenticated,
            frame_integrity_verified=result.FrameIntegrityVerified,capture_integrity_verified=result.CaptureIntegrityVerified&&!cancelled,
            evidence_authenticated=false,physical_vehicle_verified=false,observed_vin=result.ObservedVin,cancelled,
            declared_vin=expectedVin,vin_match=VinMatch(result,expectedVin,cancelled),
            steps=result.Steps.Select(s=>new{sequence=s.Sequence,request_hex=s.RequestHex,response_hex=s.ResponseHex,
                status=s.Status,duration_ms=s.DurationMs,dtc_codes=s.DtcCodes,
                negative_response_code=s.NegativeResponseCode,
                supported_page=s.InventoryPage is { } page?new{mode=page.Mode,@base=page.Base,
                    supported_ids=page.SupportedIds,has_next_page=page.HasNextPage}:null,
                freeze_trigger=s.FreezeFrameTrigger is { } trigger?new{present=trigger.Present,dtc=trigger.Dtc}:null,
                readiness=s.MonitorReadiness is { } ready?new{mil_on=ready.MilOn,confirmed_dtc_count=ready.ConfirmedDtcCount,
                    compression_ignition=ready.CompressionIgnition,byte_b=ready.ByteB,byte_c=ready.ByteC,byte_d=ready.ByteD,
                    supported_monitors=ready.SupportedMonitors}:null,
                vehicle_info=s.Identification is { } info?new{info_type=info.InfoType,values=info.Values}:null,
                monitor_records=s.MonitorRecords?.Select(m=>new{monitor_id=m.MonitorId,test_id=m.TestId,
                    unit_scaling_id=m.UnitScalingId,value_encoded=m.ValueEncoded,minimum_encoded=m.MinimumEncoded,maximum_encoded=m.MaximumEncoded}),
                measurement=s.PidMeasurement is { } value?new {
                    mode=value.Mode,pid=value.Pid,name=value.Name,number=value.Number,unit=value.Unit
                }:null}),
            extended_vehicle_info=result.Steps.Select(s=>ObdJ1979ExtendedVehicleInfo.TryRaw(s.RequestHex,s.ResponseHex,s.Status))
                .OfType<ObdJ1979ExtendedVehicleInfo.Record>().Select(ObdJ1979ExtendedVehicleInfo.Evidence),
            service_observations=new[]{1,2,3,6,7,8,9,10}.Select(mode=>{
                string hex=mode.ToString("X2",CultureInfo.InvariantCulture);
                var rows=result.Steps.Where(s=>s.RequestHex.StartsWith(hex,StringComparison.Ordinal)).ToArray();
                return new {mode=hex,attempted=rows.Length,passed=rows.Count(s=>s.Status=="PASSED"),
                    raw=rows.Count(s=>s.Status=="RAW"),negative=rows.Count(s=>s.Status=="NEGATIVE"),
                    timeout=rows.Count(s=>s.Status=="TIMEOUT"),failed=rows.Count(s=>s.Status=="FAILED")};
            }),
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
