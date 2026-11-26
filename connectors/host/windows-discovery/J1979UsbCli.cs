using System.IO.Ports;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
using System.Globalization;
using System.Text.RegularExpressions;

namespace CamaroDiagConnectors.Windows;

internal static class J1979UsbCli
{
    private sealed class SerialLink(SerialPort port):J1979UsbCapture.ILink
    {
        private bool closed;
        private void Current(){if(closed||!port.IsOpen)throw new IOException("USB session closed");}
        public int Read(byte[] data,int offset,int size,int timeoutMs){
            long start=Environment.TickCount64;
            while(true){
                Current();long elapsed=Environment.TickCount64-start;
                if(elapsed<0||elapsed>=timeoutMs)throw new TimeoutException("USB read deadline exceeded");
                port.ReadTimeout=(int)Math.Min(100,timeoutMs-elapsed);
                try{int n=port.Read(data,offset,size);if(n>0)return n;}
                catch(TimeoutException){ /* wait only within the original deadline */ }
            }
        }
        public void Write(byte[] data,int timeoutMs){
            Current();port.WriteTimeout=Math.Min(timeoutMs,1000);
            port.Write(data,0,data.Length);Current(); // no resend
        }
        public void CheckNoBufferedInput(){
            Current();
            if(port.BytesToRead!=0)throw new IOException("Unexpected buffered CU/1 bytes");
        }
        public void Dispose(){if(!closed){closed=true;port.Close();}}
    }
    internal static int Run(string[] args)
    {
        var required=new HashSet<string>(StringComparer.Ordinal)
            {"--port","--key-file","--tx","--rx","--budget-ms","--vehicle","--ecu","--connector","--capture","--output"};
        var values=new Dictionary<string,string>(StringComparer.Ordinal);
        bool ext=false;
        for(int i=0;i<args.Length;i++){
            if(args[i]=="--extended"){if(ext)return 2;ext=true;continue;}
            if(!required.Contains(args[i])||i+1==args.Length||!values.TryAdd(args[i],args[i+1]))return 2;
            i++;
        }
        if(values.Count!=required.Count||!OperatingSystem.IsWindows()||
           !Regex.IsMatch(values["--port"],@"\ACOM[1-9][0-9]{0,3}\z",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)||
           !int.TryParse(values["--budget-ms"],NumberStyles.None,CultureInfo.InvariantCulture,out int budget)||
           !Hex(values["--tx"],out uint tx)||!Hex(values["--rx"],out uint rx)||
           !J1979UsbAuth.PhysicalPair(tx,rx,ext)||budget is <1 or >600000)return 2;
        foreach(string name in new[]{"--vehicle","--ecu","--connector"}){
            string value=values[name];
            if(string.IsNullOrWhiteSpace(value)||value.Length>256||value.Any(char.IsControl))return 2;
        }
        string keyPath=Path.GetFullPath(values["--key-file"]),capturePath=Path.GetFullPath(values["--capture"]),
            reportPath=Path.GetFullPath(values["--output"]);
        if(new[]{keyPath,capturePath,reportPath}.Distinct(StringComparer.OrdinalIgnoreCase).Count()!=3)return 2;
        byte[] key=new byte[32];
        try{
            using(var f=new FileStream(keyPath,FileMode.Open,FileAccess.Read,FileShare.Read)){
                f.ReadExactly(key);if(f.ReadByte()!=-1)throw new IOException("Provisioned credential must contain 32 bytes");
            }
            Console.Write("Qualified hardware and documented physical ECU, stationary vehicle; start read-only test? [yes/no] ");
            if(Console.ReadLine()!="yes")return 2;
            using var output=new FileStream(reportPath,FileMode.CreateNew,FileAccess.Write,FileShare.None);
            J1979UsbCapture.Result result;
            var timer=Stopwatch.StartNew();
            try{
                using var raw=new FileStream(capturePath,FileMode.CreateNew,FileAccess.Write,FileShare.None);
                using var port=new SerialPort(values["--port"],115200,Parity.None,8,StopBits.One){
                    Handshake=Handshake.None,DtrEnable=true,RtsEnable=false,ReadTimeout=100,WriteTimeout=1000
                };
                port.Open();
                result=new J1979UsbCapture(new SerialLink(port)).Run(tx,rx,ext,budget,key,raw);
                raw.Flush(flushToDisk:true);
            }catch(Exception error) when(error is IOException or UnauthorizedAccessException or TimeoutException or InvalidOperationException){
                result=new(false,false,0,error.Message);
            }
            JsonSerializer.Serialize(output,new{
                schema="camarodiag-functional-j1979-native-usb-v1",protocol="SAE-J1979",
                source="LIVE_USB_CU1",host_version=typeof(J1979UsbCli).Assembly.GetName().Version?.ToString(),
                captured_at_utc=DateTimeOffset.UtcNow,duration_ms=timer.ElapsedMilliseconds,
                declared_vehicle=values["--vehicle"],declared_ecu=values["--ecu"],declared_connector=values["--connector"],
                request_can_id=tx.ToString("X",CultureInfo.InvariantCulture),response_can_id=rx.ToString("X",CultureInfo.InvariantCulture),
                extended=ext,budget_ms=budget,device_credential_authenticated=result.DeviceCredentialAuthenticated,
                frame_integrity_verified=result.FrameIntegrityVerified,capture_integrity_verified=result.CaptureIntegrityVerified,
                evidence_authenticated=false,physical_vehicle_verified=false,observed_vin=result.ObservedVin,
                steps=result.Steps.Select(s=>new{sequence=s.Sequence,request_hex=s.RequestHex,response_hex=s.ResponseHex,
                    status=s.Status,duration_ms=s.DurationMs}),
                summary=result.Summary is { } summary?new{
                    end=summary.End,steps=summary.Steps,
                    transport_observed=(summary.Flags&1)!=0,pid_inventory_complete=(summary.Flags&2)!=0,
                    freeze_trigger_observed=(summary.Flags&4)!=0,freeze_frame_present=(summary.Flags&8)!=0,
                    freeze_inventory_complete=(summary.Flags&16)!=0,monitor_inventory_complete=(summary.Flags&32)!=0,
                    control_inventory_complete=(summary.Flags&64)!=0,identity_inventory_complete=(summary.Flags&128)!=0
                }:null,
                records=result.Records,raw_capture_name=Path.GetFileName(capturePath),error=result.Error
            },new JsonSerializerOptions{WriteIndented=true});
            output.Flush(flushToDisk:true);
            Console.WriteLine("Raw CJ/1 and diagnostic evidence saved. Physical provenance not established.");
            return result.CaptureIntegrityVerified&&result.Summary?.End=="COMPLETE"?0:2;
        }catch(Exception error) when(error is IOException or UnauthorizedAccessException or ArgumentException or TimeoutException or InvalidOperationException){
            Console.Error.WriteLine("CU/1 failed: "+error.Message);return 2;
        }finally{CryptographicOperations.ZeroMemory(key);}
    }
    private static bool Hex(string input,out uint value)=>
        uint.TryParse(input.StartsWith("0x",StringComparison.OrdinalIgnoreCase)?input[2..]:input,
            NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out value);
}
