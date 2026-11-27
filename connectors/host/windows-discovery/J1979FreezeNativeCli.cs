using System.Globalization;
using System.IO.Ports;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using CamaroDiag.J2534Bridge;

namespace CamaroDiagConnectors.Windows;

internal static class J1979FreezeNativeCli
{
    internal const string CaptureCommand="--j1979-freeze-native";
    internal const string InspectCommand="--inspect-j1979-freeze-native";
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
            try
            {
                using var raw=new FileStream(options.CapturePath,FileMode.CreateNew,FileAccess.Write,FileShare.None);
                if(!current())throw new OperationCanceledException();
                using var serial=new SerialPort(options.Port,115200,Parity.None,8,StopBits.One)
                {
                    Handshake=Handshake.None,DtrEnable=true,RtsEnable=false,ReadTimeout=100,WriteTimeout=1000
                };
                serial.Open();
                report=new J1979FreezeUsbCapture(new J1979SerialPortLink(serial,current)).Run(profile,key,raw,current);
                raw.Flush(true);
            }
            catch(Exception e) when(e is IOException or TimeoutException or UnauthorizedAccessException or
                InvalidOperationException or OperationCanceledException)
            {
                report=report with {CaptureIntegrityVerified=false,Error=e.Message,
                    Cancelled=e is OperationCanceledException||!current()};
            }
            if(!current())report=report with {CaptureIntegrityVerified=false,Cancelled=true};
            Write(output,report);
            return report;
        }
        finally{CryptographicOperations.ZeroMemory(key);}
    }

    private static void Write(FileStream output,J1979FreezeNative.Report report)
    {
        using var writer=new StreamWriter(output,leaveOpen:true);
        writer.WriteLine(J1979FreezeNative.EncodeReport(report));writer.Flush();output.Flush(true);
    }

    internal static J1979FreezeNative.Report Inspect(string source,string destination)
    {
        if(Path.GetFullPath(source).Equals(Path.GetFullPath(destination),StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Capture and report paths must differ");
        using var input=new FileStream(source,FileMode.Open,FileAccess.Read,FileShare.Read);
        if(input.Length>J1979FreezeNative.MaximumCapture)throw new InvalidDataException("CF/1 capture exceeds bound");
        var report=J1979FreezeNative.Decode(input);
        using var output=new FileStream(destination,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        Write(output,report);return report;
    }

    internal static int Run(string[] args)
    {
        try
        {
            if(args.Length==0)return 2;
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
        catch(Exception e) when(e is ArgumentException or IOException or UnauthorizedAccessException or
            TimeoutException or InvalidOperationException or OperationCanceledException)
        {Console.Error.WriteLine(e.Message);return 2;}
    }
}
