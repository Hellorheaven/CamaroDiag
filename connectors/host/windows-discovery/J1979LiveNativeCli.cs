using System.Globalization;
using System.IO.Ports;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Text.Json;

namespace CamaroDiagConnectors.Windows;

internal static class J1979LiveNativeCli
{
    internal const string CaptureCommand="--j1979-live-native",InspectCommand="--inspect-j1979-live-native",CsvCommand="--export-j1979-live-native-csv",PrefixCommand="--inspect-j1979-live-prefix";
    internal const string ScalarCommand="--project-j1979-live-native",ScalarCsvCommand="--export-j1979-live-native-values-csv";
    internal const string VerifyScalarCommand="--verify-j1979-live-native-projection";
    internal sealed record Options(string Port,string KeyPath,string CapturePath,string OutputPath,J1979LiveNative.Profile Profile);

    internal static Options Parse(string[] args)
    {
        var values=new Dictionary<string,string>(StringComparer.Ordinal);
        var pids=new List<int>();bool extended=false,confirmed=false;
        for(int i=0;i<args.Length;i++)
        {
            string name=args[i];
            if(name=="--extended"){if(extended)throw new ArgumentException("Duplicate extended flag");extended=true;continue;}
            if(name=="--confirm-physical-channel")
            {if(confirmed)throw new ArgumentException("Duplicate confirmation");confirmed=true;continue;}
            if(name is not ("--port" or "--key-file" or "--capture" or "--output" or "--tx" or "--rx" or
                "--vin" or "--pid" or "--cycles" or "--interval-ms" or "--budget-ms")||
                i+1>=args.Length||args[i+1].StartsWith("--",StringComparison.Ordinal))
                throw new ArgumentException("Unknown or incomplete CU/3 option");
            string value=args[++i];
            if(name=="--pid")
            {
                if(value.Length!=2||!byte.TryParse(value,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out byte pid)||
                    pids.Count>=32)throw new ArgumentException("Expected selected hexadecimal byte");
                pids.Add(pid);
            }
            else if(!values.TryAdd(name,value))throw new ArgumentException("Duplicate CU/3 option");
        }
        string Required(string name)=>values.TryGetValue(name,out var value)&&!string.IsNullOrWhiteSpace(value)?
            value:throw new ArgumentException("Missing "+name);
        int Number(string name,string fallback)=>int.TryParse(values.GetValueOrDefault(name,fallback),
            NumberStyles.None,CultureInfo.InvariantCulture,out var n)?n:throw new ArgumentException("Invalid "+name);
        uint Id(string name)
        {
            string value=Required(name);
            if(value.StartsWith("0x",StringComparison.OrdinalIgnoreCase))value=value[2..];
            if(value.Length is <1 or >8||!uint.TryParse(value,NumberStyles.AllowHexSpecifier,
                CultureInfo.InvariantCulture,out var id))throw new ArgumentException("Invalid physical CAN ID");
            return id;
        }
        string port=Required("--port");
        if(!confirmed||!Regex.IsMatch(port,@"\ACOM[1-9][0-9]{0,3}\z",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant))
            throw new ArgumentException("Explicit COM and confirmation required");
        var profile=new J1979LiveNative.Profile(Id("--tx"),Id("--rx"),extended,Required("--vin"),
            pids.ToArray(),Number("--cycles","30"),Number("--interval-ms","1000"),Number("--budget-ms","180000")).Validate();
        string key=Path.GetFullPath(Required("--key-file")),capture=Path.GetFullPath(Required("--capture")),
            output=Path.GetFullPath(Required("--output"));
        if(new[]{key,capture,output}.Distinct(StringComparer.OrdinalIgnoreCase).Count()!=3)
            throw new ArgumentException("Credential and evidence paths must differ");
        return new(port,key,capture,output,profile);
    }

    private static (J1979LiveNative.Report Report,string Digest) ReplayPersisted(FileStream input,J1979LiveNative.Profile? expected=null)
    {
        if(input.Length>J1979LiveNative.MaximumCapture)throw new InvalidDataException("CL/1 exceeds capture byte limit");
        string hash=Convert.ToHexString(SHA256.HashData(input)).ToLowerInvariant();
        input.Position=0;var report=J1979LiveNative.Decode(input,expected);
        input.Position=0;
        if(hash!=Convert.ToHexString(SHA256.HashData(input)).ToLowerInvariant())
            throw new InvalidDataException("CL/1 capture changed during replay");
        return (report,hash);
    }
    private static void Write(FileStream output,J1979LiveNative.Report report,string? digest=null,string? name=null,
        IReadOnlyDictionary<string,object?>? metadata=null)
    {
        using var writer=new StreamWriter(output,leaveOpen:true);
        writer.WriteLine(J1979LiveNative.EncodeReport(report,digest,name,metadata));
        writer.Flush();output.Flush(true);
    }
    private static J1979LiveNative.Report Failure(J1979LiveNative.Report report,Exception e,bool cancelled=false)=>
        report with{CaptureIntegrityVerified=false,Summary=null,Result=null,
            Cancelled=report.Cancelled||cancelled||e is OperationCanceledException,Error=e.Message};
    internal static J1979LiveNative.Report Capture(Options options,Func<bool> current)
    {
        var profile=options.Profile.Validate();
        var started=DateTime.UtcNow;
        byte[] key=new byte[32];
        try
        {
            if(!current())throw new OperationCanceledException("Owner revoked");
            using(var credential=new FileStream(options.KeyPath,FileMode.Open,FileAccess.Read,FileShare.Read))
            {
                if((File.GetAttributes(options.KeyPath)&(FileAttributes.ReparsePoint|FileAttributes.Directory))!=0)
                    throw new InvalidDataException("Regular credential file required");
                credential.ReadExactly(key);
                if(credential.ReadByte()!=-1)throw new IOException("Invalid credential size");
            }
            if(!current())throw new OperationCanceledException("Owner revoked");
            using var output=new FileStream(options.OutputPath,FileMode.CreateNew,FileAccess.Write,FileShare.None);
            using var empty=new MemoryStream();
            var report=J1979LiveNative.Decode(empty,profile) with{Source="LIVE_USB_CU3"};
            bool created=false;
            try
            {
                using var raw=new FileStream(options.CapturePath,FileMode.CreateNew,FileAccess.Write,FileShare.None);
                created=true;
                if(!current())throw new OperationCanceledException("Owner revoked");
                using var serial=new SerialPort(options.Port,115200,Parity.None,8,StopBits.One){
                    Handshake=Handshake.None,DtrEnable=true,RtsEnable=false,ReadTimeout=100,WriteTimeout=1000};
                serial.Open();
                report=new J1979LiveUsbCapture(new J1979SerialPortLink(serial,current)).Run(profile,key,raw,current);
                raw.Flush(true);
            }
            catch(Exception e) when(e is InvalidDataException or IOException or TimeoutException or UnauthorizedAccessException or
                InvalidOperationException or OperationCanceledException or ArgumentException)
            {report=Failure(report,e,!current());}
            string? digest=null;
            if(created)
            {
                try
                {
                    using var saved=new FileStream(options.CapturePath,FileMode.Open,FileAccess.Read,FileShare.Read);
                    var persisted=ReplayPersisted(saved,profile);digest=persisted.Digest;
                    if(report.CaptureIntegrityVerified)
                    {
                        var adjusted=persisted.Report with{
                            Source=report.Source,DeviceCredentialAuthenticated=report.DeviceCredentialAuthenticated,
                            ClientProofSent=report.ClientProofSent,Cancelled=report.Cancelled,HostElapsedMs=report.HostElapsedMs};
                        if(!persisted.Report.CaptureIntegrityVerified||
                            J1979LiveNative.EncodeReport(adjusted)!=J1979LiveNative.EncodeReport(report))
                            throw new InvalidDataException("Persisted CL/1 differs from live replay");
                    }
                }
                catch(Exception e) when(e is IOException or InvalidDataException or UnauthorizedAccessException or OverflowException)
                {report=Failure(report,e);}
            }
            if(!current())report=Failure(report,new OperationCanceledException("Owner revoked"),true);
            var finished=DateTime.UtcNow;
            var version=typeof(J1979LiveNativeCli).Assembly.GetName().Version?.ToString()??"source";
            var metadata=new Dictionary<string,object?>{
                ["version"]=version,["declared_connector"]=options.Port,
                ["declared_ecu"]=$"CAN {profile.Tx:X}/{profile.Rx:X}",
                ["started_at_utc"]=started.ToString("O",CultureInfo.InvariantCulture),
                ["finished_at_utc"]=(finished<started?started:finished).ToString("O",CultureInfo.InvariantCulture),
                ["observed_vin"]=report.CaptureIntegrityVerified&&report.Summary?.InitialIdentityVerified==true?profile.Vin:null
            };
            Write(output,report,digest,created?Path.GetFileName(options.CapturePath):null,metadata);
            return report;
        }
        finally{CryptographicOperations.ZeroMemory(key);}
    }

    internal static J1979LiveNative.Report Inspect(string source,string vin,string destination,Func<bool>? current=null)
    {
        if(!J1979LiveNative.ValidVin(vin)||Path.GetFullPath(source).Equals(Path.GetFullPath(destination),StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Independent VIN and distinct new output required");
        if(current is not null&&!current())throw new OperationCanceledException("Owner revoked");
        using var input=new FileStream(source,FileMode.Open,FileAccess.Read,FileShare.Read);
        var (report,digest)=ReplayPersisted(input);
        if(report.Profile?.ExpectedVin!=vin)throw new InvalidDataException("CL/1 plan differs from independently supplied VIN");
        if(current is not null&&!current())throw new OperationCanceledException("Owner revoked");
        using var output=new FileStream(destination,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        Write(output,report,digest,Path.GetFileName(source));return report;
    }

    internal static byte[] Csv(J1979LiveNative.Report report)
    {
        if(!report.CaptureIntegrityVerified||report.Profile is null||report.Summary is null||report.Result is null)
            throw new InvalidDataException("Verified CL/1 required");
        var p=report.Profile;var result=report.Result;
        var builder=new StringBuilder("cycle,pid_hex,elapsed_ms,status,response_hex,request_can_id,response_can_id,vin,source_completed\r\n");
        foreach(var row in result.Samples)
        {
            builder.Append(row.Cycle.ToString(CultureInfo.InvariantCulture)).Append(',')
                .Append(row.Pid.ToString("X2",CultureInfo.InvariantCulture)).Append(',')
                .Append(row.ElapsedMs.ToString(CultureInfo.InvariantCulture)).Append(',')
                .Append(row.Status).Append(',').Append(row.ResponseHex??"").Append(',')
                .Append(p.RequestCanId).Append(',').Append(p.ResponseCanId).Append(',')
                .Append(p.ExpectedVin).Append(',').Append(result.Completed?"true":"false").Append("\r\n");
            if(builder.Length>40*1024*1024)throw new InvalidDataException("Native CSV size limit exceeded");
        }
        return Encoding.ASCII.GetBytes(builder.ToString());
    }
    internal static J1979LiveNative.Report ExportCsv(string source,string vin,string destination,Func<bool>? current=null)
    {
        if(!J1979LiveNative.ValidVin(vin)||Path.GetFullPath(source).Equals(Path.GetFullPath(destination),StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Independent VIN and distinct new output required");
        if(current is not null&&!current())throw new OperationCanceledException("Owner revoked");
        using var input=new FileStream(source,FileMode.Open,FileAccess.Read,FileShare.Read);
        var (report,digest)=ReplayPersisted(input);
        if(report.Profile?.ExpectedVin!=vin)throw new InvalidDataException("CL/1 plan differs from independently supplied VIN");
        byte[] csv=Csv(report);input.Position=0;
        if(digest!=Convert.ToHexString(SHA256.HashData(input)).ToLowerInvariant())
            throw new InvalidDataException("CL/1 changed during export");
        if(current is not null&&!current())throw new OperationCanceledException("Owner revoked");
        using var output=new FileStream(destination,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        output.Write(csv);output.Flush(true);return report;
    }

    internal static J1979LiveScalarProjection.Projection ExportScalar(string source,string vin,string destination,bool csv,Func<bool>? current=null)
    {
        if(!J1979LiveNative.ValidVin(vin)||Path.GetFullPath(source).Equals(Path.GetFullPath(destination),StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Independent VIN and distinct new output required");
        if(current is not null&&!current())throw new OperationCanceledException("Owner revoked");
        if((File.GetAttributes(source)&(FileAttributes.ReparsePoint|FileAttributes.Directory))!=0)
            throw new InvalidDataException("Regular capture file required");
        using var input=new FileStream(source,FileMode.Open,FileAccess.Read,FileShare.Read);
        var (replay,digest)=ReplayPersisted(input);
        var projection=J1979LiveScalarProjection.Project(replay,vin);
        byte[] bytes=csv?J1979LiveScalarProjection.Csv(projection,digest):EncodeScalarJson(projection,digest);
        if(bytes.Length>MaximumProjectionBytes)throw new InvalidDataException("Projection exceeds byte limit");
        input.Position=0;
        if(digest!=Convert.ToHexString(SHA256.HashData(input)).ToLowerInvariant())
            throw new InvalidDataException("CL/1 changed during projection");
        if(current is not null&&!current())throw new OperationCanceledException("Owner revoked");
        using var output=new FileStream(destination,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        output.Write(bytes);output.Flush(true);
        return projection;
    }

    private const int MaximumProjectionBytes=64*1024*1024;

    private static byte[] EncodeScalarJson(J1979LiveScalarProjection.Projection projection,string digest)
    {
        var options=new JsonSerializerOptions{PropertyNamingPolicy=JsonNamingPolicy.SnakeCaseLower,WriteIndented=true};
        var payload=JsonSerializer.SerializeToElement(projection,options);
        var fields=payload.EnumerateObject().ToDictionary(p=>p.Name,p=>(object?)p.Value);
        fields["capture_sha256"]=digest;
        fields["expected_vin_independently_supplied"]=true;
        return JsonSerializer.SerializeToUtf8Bytes(fields,options);
    }

    internal static J1979LiveScalarProjection.Projection VerifyScalar(string source,string vin,string projectionPath)
    {
        if(!J1979LiveNative.ValidVin(vin)||
            Path.GetFullPath(source).Equals(Path.GetFullPath(projectionPath),StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Independent VIN and distinct capture and projection files required");
        foreach(string path in new[]{source,projectionPath})
            if((File.GetAttributes(path)&(FileAttributes.ReparsePoint|FileAttributes.Directory))!=0)
                throw new InvalidDataException("Regular capture and projection files required");

        using var capture=new FileStream(source,FileMode.Open,FileAccess.Read,FileShare.Read);
        using var candidate=new FileStream(projectionPath,FileMode.Open,FileAccess.Read,FileShare.Read);
        if(candidate.Length is <=0 or >MaximumProjectionBytes)
            throw new InvalidDataException("Invalid projection byte length");

        var (replay,digest)=ReplayPersisted(capture);
        var projection=J1979LiveScalarProjection.Project(replay,vin);
        byte[] expected=EncodeScalarJson(projection,digest);
        byte[] supplied=new byte[(int)candidate.Length];
        candidate.ReadExactly(supplied);
        if(candidate.ReadByte()!=-1)throw new InvalidDataException("Projection changed during verification");
        J1979PortableScalarVerification.Verify(expected,supplied);
        candidate.Position=0;
        if(!CryptographicOperations.FixedTimeEquals(SHA256.HashData(supplied),SHA256.HashData(candidate)))
            throw new InvalidDataException("Projection changed during verification");
        capture.Position=0;
        if(digest!=Convert.ToHexString(SHA256.HashData(capture)).ToLowerInvariant())
            throw new InvalidDataException("CL/1 changed during verification");
        return projection;
    }

    internal static Dictionary<string,object?> InspectPrefix(string source,string vin,string destination,Func<bool>? current=null)
    {
        if(!J1979LiveNative.ValidVin(vin)||Path.GetFullPath(source).Equals(Path.GetFullPath(destination),StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Independent VIN and distinct new output required");
        if(current is not null&&!current())throw new OperationCanceledException("Owner revoked");
        if((File.GetAttributes(source)&(FileAttributes.ReparsePoint|FileAttributes.Directory))!=0)
            throw new InvalidDataException("Regular capture file required");
        using var input=new FileStream(source,FileMode.Open,FileAccess.Read,FileShare.Read);
        if(input.Length is <=0 or >J1979LiveNative.MaximumCapture)
            throw new InvalidDataException("Invalid CL/1 input size");
        byte[] raw=new byte[(int)input.Length];input.ReadExactly(raw);
        if(input.ReadByte()!=-1)throw new InvalidDataException("CL/1 grew during read");
        string digest=Convert.ToHexString(SHA256.HashData(raw)).ToLowerInvariant();
        var report=J1979LiveNative.InspectPrefix(raw,vin);report["capture_sha256"]=digest;
        input.Position=0;
        if(digest!=Convert.ToHexString(SHA256.HashData(input)).ToLowerInvariant())
            throw new InvalidDataException("CL/1 changed during prefix inspection");
        byte[] data=JsonSerializer.SerializeToUtf8Bytes(report,new JsonSerializerOptions{
            WriteIndented=true,PropertyNamingPolicy=JsonNamingPolicy.SnakeCaseLower});
        if(data.Length>128*1024*1024)throw new InvalidDataException("Prefix report exceeds byte limit");
        if(current is not null&&!current())throw new OperationCanceledException("Owner revoked");
        using var output=new FileStream(destination,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        output.Write(data);output.Flush(true);return report;
    }

    internal static int Run(string[] args)
    {
        try
        {
            if(args.Length==0)return 2;
            if(args[0]==VerifyScalarCommand)
            {
                if(args.Length!=4)throw new ArgumentException(
                    "Expected CL/1 source, independently supplied VIN and canonical Windows JSON projection");
                var projection=VerifyScalar(args[1],args[2],args[3]);
                Console.WriteLine(JsonSerializer.Serialize(new{
                    projection_verified=true,projection.IdentityRechecked,projection.NumericProjectionComplete}));
                return projection.NumericProjectionComplete?0:7;
            }
            if(args[0] is ScalarCommand or ScalarCsvCommand)
            {
                if(args.Length!=4)throw new ArgumentException("Expected CL/1 source, independently supplied VIN and new output");
                var projection=ExportScalar(args[1],args[2],args[3],args[0]==ScalarCsvCommand);
                Console.WriteLine(JsonSerializer.Serialize(new{projection.IdentityRechecked,projection.NumericProjectionComplete}));
                return projection.NumericProjectionComplete?0:7;
            }
            if(args[0]==PrefixCommand)
            {
                if(args.Length!=4)throw new ArgumentException("Expected CL/1 source, independent VIN and new JSON output");
                var prefix=InspectPrefix(args[1],args[2],args[3]);
                Console.WriteLine(JsonSerializer.Serialize(new{
                    prefix_integrity_verified=true,execution_completed=false,
                    complete_records=prefix["complete_records"],discarded_tail_bytes=prefix["discarded_tail_bytes"]}));
                return 7;
            }
            J1979LiveNative.Report report;
            if(args[0]==CsvCommand)
            {
                if(args.Length!=4)throw new ArgumentException("Expected CL/1 source, independently supplied VIN and new CSV output");
                report=ExportCsv(args[1],args[2],args[3]);
            }
            else if(args[0]==InspectCommand)
            {
                if(args.Length!=4)throw new ArgumentException("Expected CL/1 source, independently supplied VIN and new JSON output");
                report=Inspect(args[1],args[2],args[3]);
            }
            else
            {
                if(args[0]!=CaptureCommand||!OperatingSystem.IsWindows())return 2;
                var options=Parse(args[1..]);
                if(File.Exists(options.CapturePath)||File.Exists(options.OutputPath))
                    throw new IOException("New evidence paths required");
                Console.Write("Qualified locally approved connector, stationary vehicle, exact VIN and scope? [yes/no] ");
                if(Console.ReadLine()!="yes")return 2;
                using var cancel=new CancellationTokenSource();
                ConsoleCancelEventHandler handler=(_,e)=>{e.Cancel=true;cancel.Cancel();};
                Console.CancelKeyPress+=handler;
                try{report=Capture(options,()=>!cancel.IsCancellationRequested);}
                finally{Console.CancelKeyPress-=handler;}
            }
            Console.WriteLine(J1979LiveNative.EncodeReport(report));
            return !report.CaptureIntegrityVerified||report.Cancelled?2:report.Summary?.Completed==true?0:7;
        }
        catch(Exception e) when(e is InvalidDataException or ArgumentException or IOException or UnauthorizedAccessException or
            TimeoutException or InvalidOperationException or OperationCanceledException or OverflowException)
        {Console.Error.WriteLine(e.Message);return 2;}
    }
}
