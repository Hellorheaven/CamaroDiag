using System.IO.Ports;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Globalization;
using System.Text.RegularExpressions;

namespace CamaroDiagConnectors.Windows;

internal static class J1979UsbCli
{
    internal static int Run(string[] args)
    {
        var required=new HashSet<string>(StringComparer.Ordinal)
            {"--port","--key-file","--tx","--rx","--budget-ms","--vehicle","--ecu","--connector","--capture","--output"};
        var values=new Dictionary<string,string>(StringComparer.Ordinal);
        bool ext=false;
        for(int i=0;i<args.Length;i++){
            if(args[i]=="--extended"){if(ext)return 2;ext=true;continue;}
            if((!required.Contains(args[i])&&args[i]!="--vin")||i+1==args.Length||!values.TryAdd(args[i],args[i+1]))return 2;
            i++;
        }
        if(!required.All(values.ContainsKey)||!OperatingSystem.IsWindows()||
           !Regex.IsMatch(values["--port"],@"\ACOM[1-9][0-9]{0,3}\z",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)||
           !int.TryParse(values["--budget-ms"],NumberStyles.None,CultureInfo.InvariantCulture,out int budget)||
           !Hex(values["--tx"],out uint tx)||!Hex(values["--rx"],out uint rx)||
           !J1979UsbAuth.PhysicalPair(tx,rx,ext)||budget is <1 or >600000)return 2;
        foreach(string name in new[]{"--vehicle","--ecu","--connector"}){
            string value=values[name];
            if(string.IsNullOrWhiteSpace(value)||value.Length>256||value.Any(char.IsControl))return 2;
        }
        string? expectedVin=values.GetValueOrDefault("--vin")?.Trim().ToUpperInvariant();
        if(expectedVin is not null&&!Regex.IsMatch(expectedVin,@"\A[A-HJ-NPR-Z0-9]{17}\z"))return 2;
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
            J1979UsbCapture.Result result=new(false,false,0,"USB campaign not started");
            var timer=Stopwatch.StartNew();
            int cancelled=0;
            ConsoleCancelEventHandler cancelHandler=(_,e)=>{e.Cancel=true;Interlocked.Exchange(ref cancelled,1);};
            Console.CancelKeyPress+=cancelHandler;
            try{
                using var raw=new FileStream(capturePath,FileMode.CreateNew,FileAccess.Write,FileShare.None);
                using var port=new SerialPort(values["--port"],115200,Parity.None,8,StopBits.One){
                    Handshake=Handshake.None,DtrEnable=true,RtsEnable=false,ReadTimeout=100,WriteTimeout=1000
                };
                if(Volatile.Read(ref cancelled)!=0)throw new OperationCanceledException("USB campaign cancelled");
                port.Open();
                result=new J1979UsbCapture(new J1979SerialPortLink(port,()=>Volatile.Read(ref cancelled)==0)).Run(tx,rx,ext,budget,key,raw);
                raw.Flush(flushToDisk:true);
            }catch(Exception error) when(error is IOException or UnauthorizedAccessException or TimeoutException or InvalidOperationException or OperationCanceledException){
                result=result with {FrameIntegrityVerified=false,Error=error.Message,Summary=null};
            }finally{Console.CancelKeyPress-=cancelHandler;}
            bool wasCancelled=Volatile.Read(ref cancelled)!=0;
            J1979NativeReport.Write(output,result,typeof(J1979UsbCli).Assembly.GetName().Version?.ToString()??"",
                values["--vehicle"],values["--ecu"],values["--connector"],tx,rx,ext,budget,timer.ElapsedMilliseconds,
                Path.GetFileName(capturePath),cancelled:wasCancelled,expectedVin:expectedVin);
            output.Flush(flushToDisk:true);
            Console.WriteLine("Raw CJ/1 and diagnostic evidence saved. Physical provenance not established.");
            return !wasCancelled&&result.CaptureIntegrityVerified&&result.Summary?.End=="COMPLETE"&&
                (expectedVin is null||J1979NativeReport.VinMatch(result,expectedVin)=="MATCH")?0:2;
        }catch(Exception error) when(error is IOException or UnauthorizedAccessException or ArgumentException or TimeoutException or InvalidOperationException){
            Console.Error.WriteLine("CU/1 failed: "+error.Message);return 2;
        }finally{CryptographicOperations.ZeroMemory(key);}
    }
    private static bool Hex(string input,out uint value)=>
        uint.TryParse(input.StartsWith("0x",StringComparison.OrdinalIgnoreCase)?input[2..]:input,
            NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out value);
}
