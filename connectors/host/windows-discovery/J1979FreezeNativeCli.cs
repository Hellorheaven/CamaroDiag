using System.Globalization;
using System.IO.Ports;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using CamaroDiag.J2534Bridge;

namespace CamaroDiagConnectors.Windows;

internal static class J1979FreezeNativeCli
{
    internal const string CaptureCommand="--j1979-freeze-native";
    internal const string InspectCommand="--inspect-j1979-freeze-native";
    internal const string PrefixCommand="--inspect-j1979-freeze-native-prefix";
    internal const string VerifyPrefixCommand="--verify-j1979-freeze-native-prefix";
    // The executable entry point and direct CLI self-tests must use the same router.
    internal static bool IsCommand(string value)=>value is
        CaptureCommand or InspectCommand or PrefixCommand or VerifyPrefixCommand;
    private static readonly JsonSerializerOptions PrefixJsonOptions=new(){
        PropertyNamingPolicy=JsonNamingPolicy.SnakeCaseLower,WriteIndented=true
    };
    private static (byte[] Raw,string Hash) ReadPrefixSource(string path)
    {
        using var file=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read);
        if(file.Length is <1 || file.Length>J1979FreezeNative.MaximumCapture)
            throw new InvalidDataException("CF/1 prefix exceeds source limit");
        byte[] bytes=new byte[checked((int)file.Length)];
        file.ReadExactly(bytes);
        if(file.ReadByte()!=-1)throw new InvalidDataException("CF/1 changed during read");
        return (bytes,Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant());
    }
    private static void DistinctPaths(params string[] paths)
    {
        if(paths.Select(Path.GetFullPath).Distinct(StringComparer.OrdinalIgnoreCase).Count()!=paths.Length)
            throw new ArgumentException("Evidence paths must be distinct");
    }
    private static byte[] ReadPrefixReport(string path)
    {
        using var file=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read);
        if(file.Length is <1 or >67108864)
            throw new InvalidDataException("CF/1 prefix report exceeds byte limit");
        byte[] bytes=new byte[checked((int)file.Length)];
        file.ReadExactly(bytes);
        if(file.ReadByte()!=-1)throw new InvalidDataException("CF/1 report changed during read");
        return bytes;
    }
    private static void RequireUnambiguousJson(JsonElement node)
    {
        switch(node.ValueKind)
        {
            case JsonValueKind.Object:
                var keys=new HashSet<string>(StringComparer.Ordinal);
                foreach(var item in node.EnumerateObject())
                {
                    if(!keys.Add(item.Name))
                        throw new InvalidDataException("Duplicate key in CF/1 prefix report");
                    RequireUnambiguousJson(item.Value);
                }
                break;
            case JsonValueKind.Array:
                foreach(var item in node.EnumerateArray())RequireUnambiguousJson(item);
                break;
        }
    }
    internal static Dictionary<string,object?> InspectPrefix(string source,string output,string vin,
        Func<bool>? current=null)
    {
        if(current is not null&&!current())throw new OperationCanceledException("CF/1 session expired");
        DistinctPaths(source,output);
        var (raw,sha)=ReadPrefixSource(source);
        var result=J1979FreezeNative.InspectPrefix(raw,vin);
        var again=ReadPrefixSource(source);
        if(sha!=again.Hash||!raw.AsSpan().SequenceEqual(again.Raw))
            throw new InvalidDataException("CF/1 changed during prefix inspection");
        result["capture_sha256"]=sha;
        byte[] bytes=JsonSerializer.SerializeToUtf8Bytes(result,PrefixJsonOptions);
        if(current is not null&&!current())throw new OperationCanceledException("CF/1 session expired");
        using var file=new FileStream(output,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        file.Write(bytes);file.Flush(true);
        return result;
    }
    internal static Dictionary<string,object?> VerifyPrefix(string source,string report,string output,string vin,
        Func<bool>? current=null)
    {
        if(current is not null&&!current())throw new OperationCanceledException("CF/1 session expired");
        DistinctPaths(source,report,output);
        var (raw,sha)=ReadPrefixSource(source);
        var expected=J1979FreezeNative.InspectPrefix(raw,vin);
        expected["capture_sha256"]=sha;
        byte[] sourceJson=JsonSerializer.SerializeToUtf8Bytes(expected,PrefixJsonOptions);
        byte[] reportBytes=ReadPrefixReport(report);
        string reportSha=Convert.ToHexString(SHA256.HashData(reportBytes)).ToLowerInvariant();
        using(var original=JsonDocument.Parse(reportBytes,new JsonDocumentOptions{MaxDepth=64}))
        using(var recomputed=JsonDocument.Parse(sourceJson,new JsonDocumentOptions{MaxDepth=64}))
        {
            RequireUnambiguousJson(original.RootElement);
            if(!JsonNode.DeepEquals(JsonNode.Parse(original.RootElement.GetRawText()),
                                    JsonNode.Parse(recomputed.RootElement.GetRawText())))
                throw new InvalidDataException("CF/1 prefix report differs from independent replay");
        }
        var again=ReadPrefixSource(source);
        if(sha!=again.Hash||!raw.AsSpan().SequenceEqual(again.Raw)||
           !reportBytes.AsSpan().SequenceEqual(ReadPrefixReport(report)))
            throw new InvalidDataException("CF/1 evidence changed during verification");
        var result=new Dictionary<string,object?>{
            ["schema"]="camarodiag-j1979-native-freeze-prefix-verification-v1",
            ["consistent"]=true,["prefix_integrity_verified"]=true,
            ["capture_integrity_verified"]=false,["execution_completed"]=false,
            ["physical_vehicle_verified"]=false,["permits_programming"]=false,
            ["capture_sha256"]=sha,["report_sha256"]=reportSha,
            ["complete_records"]=expected["complete_records"],
            ["complete_observations"]=expected["complete_observations"],
            ["last_complete_offset"]=expected["last_complete_offset"],
            ["discarded_tail_bytes"]=expected["discarded_tail_bytes"],
            ["interruption"]=expected["interruption"]
        };
        byte[] outputBytes=JsonSerializer.SerializeToUtf8Bytes(result,PrefixJsonOptions);
        if(current is not null&&!current())throw new OperationCanceledException("CF/1 session expired");
        using var target=new FileStream(output,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        target.Write(outputBytes);target.Flush(true);
        return result;
    }
    internal sealed record Options(string Port,string KeyPath,string CapturePath,string OutputPath,
        J1979FreezeNative.Profile Profile);

    internal static Options Parse(string[] args)
    {
        var values=new Dictionary<string,string>(StringComparer.Ordinal);
        var frames=new List<int>();var pids=new List<int>();
        bool extended=false,confirmed=false;
        for(int i=0;i<args.Length;i++)
        {
            string name=args[i];
            if(name=="--extended"){if(extended)throw new ArgumentException("Duplicate extended flag");extended=true;continue;}
            if(name=="--confirm-physical-channel"){if(confirmed)throw new ArgumentException("Duplicate confirmation");confirmed=true;continue;}
            if(name is not ("--port" or "--key-file" or "--capture" or "--output" or "--tx" or "--rx" or
                "--vin" or "--frame" or "--pid" or "--budget-ms")||i+1>=args.Length||args[i+1].StartsWith("--",StringComparison.Ordinal))
                throw new ArgumentException("Unknown or incomplete CU/2 option");
            string value=args[++i];
            if(name is "--frame" or "--pid")
            {
                if(value.Length is <1 or >2||!byte.TryParse(value,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out byte b))
                    throw new ArgumentException("Expected hexadecimal byte");
                var target=name=="--frame"?frames:pids;
                if(target.Count>=(name=="--frame"?8:64))throw new ArgumentException("Selection too large");
                target.Add(b);
            }
            else if(!values.TryAdd(name,value))throw new ArgumentException("Duplicate CU/2 option");
        }
        string Required(string name)=>values.TryGetValue(name,out string? value)&&!string.IsNullOrWhiteSpace(value)
            ?value:throw new ArgumentException("Missing "+name);
        uint CanId(string name)
        {
            string value=Required(name);
            if(value.StartsWith("0x",StringComparison.OrdinalIgnoreCase))value=value[2..];
            if(!uint.TryParse(value,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out uint id))
                throw new ArgumentException("Invalid physical CAN ID");
            return id;
        }
        if(!confirmed||!Regex.IsMatch(Required("--port"),@"\ACOM[1-9][0-9]{0,3}\z",
            RegexOptions.IgnoreCase|RegexOptions.CultureInvariant))throw new ArgumentException("Explicit COM and confirmation required");
        if(!int.TryParse(values.GetValueOrDefault("--budget-ms","180000"),NumberStyles.None,CultureInfo.InvariantCulture,out int budget))
            throw new ArgumentException("Invalid campaign budget");
        var profile=new J1979FreezeNative.Profile(CanId("--tx"),CanId("--rx"),extended,
            new(Required("--vin"),frames.ToArray(),pids.ToArray(),budget)).Validate();
        string key=Path.GetFullPath(Required("--key-file")),capture=Path.GetFullPath(Required("--capture")),
            output=Path.GetFullPath(Required("--output"));
        if(new[]{key,capture,output}.Distinct(StringComparer.OrdinalIgnoreCase).Count()!=3)
            throw new ArgumentException("Credential and evidence paths must differ");
        return new(Required("--port"),key,capture,output,profile);
    }

    internal static J1979FreezeNative.Report Capture(Options options,Func<bool> current)
    {
        var profile=options.Profile.Validate();
        byte[] key=new byte[32];
        try
        {
            if(!current())throw new OperationCanceledException();
            using(var credential=new FileStream(options.KeyPath,FileMode.Open,FileAccess.Read,FileShare.Read))
            {
                credential.ReadExactly(key);
                if(credential.ReadByte()!=-1)throw new IOException("Invalid credential size");
            }
            if(!current())throw new OperationCanceledException();
            using var output=new FileStream(options.OutputPath,FileMode.CreateNew,FileAccess.Write,FileShare.None);
            var report=new J1979FreezeNative.Report("camarodiag-functional-j1979-native-freeze-v1","SAE-J1979",
                "LIVE_USB_CU2",false,false,false,false,0,"USB not started",profile,null,null);
            bool created=false;
            try
            {
                using var raw=new FileStream(options.CapturePath,FileMode.CreateNew,FileAccess.Write,FileShare.None);
                created=true;
                if(!current())throw new OperationCanceledException();
                using var serial=new SerialPort(options.Port,115200,Parity.None,8,StopBits.One)
                {
                    Handshake=Handshake.None,DtrEnable=true,RtsEnable=false,ReadTimeout=100,WriteTimeout=1000
                };
                serial.Open();
                report=new J1979FreezeUsbCapture(new J1979SerialPortLink(serial,current)).Run(profile,key,raw,current);
                raw.Flush(true);
            }
            catch(Exception e) when(e is InvalidDataException or IOException or TimeoutException or UnauthorizedAccessException or
                InvalidOperationException or OperationCanceledException)
            {
                report=report with {CaptureIntegrityVerified=false,Error=e.Message,
                    Cancelled=e is OperationCanceledException||!current()};
            }
            if(!current())report=report with {CaptureIntegrityVerified=false,Cancelled=true};
            string? digest=null;
            if(created)
            {
                try
                {
                    using var saved=new FileStream(options.CapturePath,FileMode.Open,FileAccess.Read,FileShare.Read);
                    var verified=ReplayPersisted(saved,profile);
                    digest=verified.Digest;
                    if(report.CaptureIntegrityVerified&&(!verified.Report.CaptureIntegrityVerified||
                        J1979FreezeNative.EncodeReport(verified.Report with {Source=report.Source,
                            DeviceCredentialAuthenticated=report.DeviceCredentialAuthenticated,
                            ClientProofSent=report.ClientProofSent,Cancelled=report.Cancelled})!=J1979FreezeNative.EncodeReport(report)))
                        report=report with {CaptureIntegrityVerified=false,Error="Persisted capture differs from live replay"};
                }
                catch(Exception e) when(e is IOException or InvalidDataException or UnauthorizedAccessException)
                {report=report with {CaptureIntegrityVerified=false,Error=e.Message};}
            }
            Write(output,report,digest,created?Path.GetFileName(options.CapturePath):null);
            return report;
        }
        finally{CryptographicOperations.ZeroMemory(key);}
    }

    private static (J1979FreezeNative.Report Report,string Digest) ReplayPersisted(FileStream input,J1979FreezeNative.Profile? expected=null)
    {
        if(input.Length>J1979FreezeNative.MaximumCapture)throw new InvalidDataException("CF/1 capture exceeds bound");
        var digest=Convert.ToHexString(SHA256.HashData(input)).ToLowerInvariant();input.Position=0;
        var report=J1979FreezeNative.Decode(input,expected);input.Position=0;
        if(digest!=Convert.ToHexString(SHA256.HashData(input)).ToLowerInvariant())
            throw new InvalidDataException("CF/1 changed during replay");
        return (report,digest);
    }

    private static void Write(FileStream output,J1979FreezeNative.Report report,string? digest=null,string? name=null)
    {
        using var writer=new StreamWriter(output,leaveOpen:true);
        writer.WriteLine(J1979FreezeNative.EncodeReport(report,digest,name));writer.Flush();output.Flush(true);
    }

    internal static J1979FreezeNative.Report Inspect(string source,string destination,Func<bool>? current=null)
    {
        if(Path.GetFullPath(source).Equals(Path.GetFullPath(destination),StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Capture and report paths must differ");
        if(current is not null&&!current())throw new OperationCanceledException();
        using var input=new FileStream(source,FileMode.Open,FileAccess.Read,FileShare.Read);
        if(input.Length>J1979FreezeNative.MaximumCapture)throw new InvalidDataException("CF/1 capture exceeds bound");
        var (report,digest)=ReplayPersisted(input);
        if(current is not null&&!current())throw new OperationCanceledException();
        using var output=new FileStream(destination,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        Write(output,report,digest,Path.GetFileName(source));return report;
    }

    internal static int Run(string[] args)
    {
        try
        {
            if(args.Length==0)return 2;
            if(args[0]==PrefixCommand&&args.Length==4)
            {
                var value=InspectPrefix(args[1],args[3],args[2]);
                Console.WriteLine(JsonSerializer.Serialize(value,PrefixJsonOptions));
                return 0;
            }
            if(args[0]==VerifyPrefixCommand&&args.Length==5)
            {
                // CLI contract: source, saved report, new verification output, independent VIN.
                var value=VerifyPrefix(args[1],args[2],args[3],args[4]);
                Console.WriteLine(JsonSerializer.Serialize(value,PrefixJsonOptions));
                return 0;
            }
            J1979FreezeNative.Report report;
            if(args[0]==InspectCommand)
            {
                if(args.Length!=3)throw new ArgumentException("Expected source and new report");
                report=Inspect(args[1],args[2]);
            }
            else
            {
                if(args[0]!=CaptureCommand||!OperatingSystem.IsWindows())return 2;
                var options=Parse(args[1..]);
                Console.Write("Qualified locally approved connector, stationary vehicle, exact VIN and scope? [yes/no] ");
                if(Console.ReadLine()!="yes")return 2;
                using var cancel=new CancellationTokenSource();
                ConsoleCancelEventHandler handler=(_,e)=>{e.Cancel=true;cancel.Cancel();};
                Console.CancelKeyPress+=handler;
                try{report=Capture(options,()=>!cancel.IsCancellationRequested);}
                finally{Console.CancelKeyPress-=handler;}
            }
            Console.WriteLine(J1979FreezeNative.EncodeReport(report));
            return report.CaptureIntegrityVerified&&!report.Cancelled&&report.Summary?.AllFramesStable==true?0:2;
        }
        catch(Exception e) when(e is InvalidDataException or ArgumentException or IOException or UnauthorizedAccessException or
            TimeoutException or InvalidOperationException or OperationCanceledException or JsonException)
        {Console.Error.WriteLine(e.Message);return 2;}
    }
}
