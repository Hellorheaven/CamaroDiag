using System.Globalization;
using System.IO.Ports;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace CamaroDiagConnectors.Windows;

internal static class J1979MaintenanceUsbCli
{
    internal const string Command="--j1979-maintenance-native";
    internal sealed record Options(string Port,string KeyPath,string JournalPath,string ReportPath,J1979MaintenanceUsb.Profile Profile);
    internal static Options Parse(string[] args)
    {
        var values=new Dictionary<string,string>(StringComparer.Ordinal);bool extended=false,fr=false;
        for(int i=0;i<args.Length;i++)
        {
            string name=args[i];
            if(name=="--extended"){if(extended)throw new ArgumentException("Duplicate extended flag");extended=true;continue;}
            if(name=="--fr"){if(fr)throw new ArgumentException("Duplicate language flag");fr=true;continue;}
            if(name is not ("--operation" or "--port" or "--key-file" or "--journal" or "--output" or
                "--tx" or "--rx" or "--vin" or "--budget-ms")||i+1>=args.Length||
                args[i+1].StartsWith("--",StringComparison.Ordinal)||!values.TryAdd(name,args[++i]))
                throw new ArgumentException("Unknown, duplicate or incomplete CU/4 option");
        }
        string Required(string key)=>values.TryGetValue(key,out var value)&&!string.IsNullOrWhiteSpace(value)?value:
            throw new ArgumentException("Missing "+key);
        uint Id(string key)
        {
            string value=Required(key);
            if(value.StartsWith("0x",StringComparison.OrdinalIgnoreCase))value=value[2..];
            if(value.Length is <1 or >8||!uint.TryParse(value,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out uint id))
                throw new ArgumentException("Invalid CAN identifier");
            return id;
        }
        int operation=Required("--operation") switch{"clear"=>1,"evap"=>2,_=>throw new ArgumentException("Unknown operation")};
        string port=Required("--port");
        if(!Regex.IsMatch(port,@"\ACOM[1-9][0-9]{0,3}\z",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant))
            throw new ArgumentException("Explicit COM port required");
        if(!int.TryParse(values.GetValueOrDefault("--budget-ms","60000"),NumberStyles.None,CultureInfo.InvariantCulture,out int budget))
            throw new ArgumentException("Invalid maintenance budget");
        var profile=new J1979MaintenanceUsb.Profile(operation,Id("--tx"),Id("--rx"),extended,Required("--vin"),budget);
        _=profile.Encode();
        var options=new Options(port,Path.GetFullPath(Required("--key-file")),Path.GetFullPath(Required("--journal")),
            Path.GetFullPath(Required("--output")),profile);
        if(new[]{options.KeyPath,options.JournalPath,options.ReportPath}.Distinct(StringComparer.OrdinalIgnoreCase).Count()!=3)
            throw new ArgumentException("Distinct credential and evidence paths required");
        return options;
    }
    private static Dictionary<string,object?> Failure(Options options,Exception e,bool enteredUsb)
    {
        // If the capture implementation failed before returning its stage,
        // do not claim a proof was never sent.
        return new(){
            ["schema"]="camarodiag-functional-j1979-native-maintenance-v1",["protocol"]="SAE-J1979",
            ["source"]="LIVE_USB_CU4",["scope"]=options.Profile.View(),
            ["device_credential_authenticated"]=false,["proof_transmission_attempted"]=enteredUsb?null:false,
            ["client_proof_sent"]=false,["cancelled"]=e is OperationCanceledException,
            ["journal_transfer_verified"]=false,["journal_bytes_received"]=null,["terminal_present"]=false,
            ["recovery_required"]=enteredUsb,["command_outcome_unknown"]=enteredUsb,
            ["declared_recovery_in_envelope"]=null,["inspection"]=null,["physical_vehicle_verified"]=false,
            ["evidence_authenticated"]=false,["operator_consent_independently_verified"]=false,
            ["permits_automatic_retry"]=false,["permits_programming"]=false,["host_elapsed_ms"]=null,["error"]=e.Message};
    }
    private static void FailReport(Dictionary<string,object?> report,Exception error,bool enteredUsb)
    {
        bool possible=enteredUsb&&!Equals(report.GetValueOrDefault("proof_transmission_attempted"),false);
        report["journal_transfer_verified"]=false;report["terminal_present"]=false;report["inspection"]=null;
        report["recovery_required"]=possible;report["command_outcome_unknown"]=possible;report["error"]=error.Message;
        if(error is OperationCanceledException)report["cancelled"]=true;
    }
    private static byte[] ReadCredential(string path)
    {
        if((File.GetAttributes(path)&(FileAttributes.Directory|FileAttributes.ReparsePoint))!=0)
            throw new InvalidDataException("Regular credential file required");
        using var source=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read);
        byte[] key=new byte[32];
        try{
            source.ReadExactly(key);
            if(source.ReadByte()!=-1)throw new InvalidDataException("Provisioned credential must contain 32 bytes");
            return key;
        }catch{CryptographicOperations.ZeroMemory(key);throw;}
    }
    private static void Persist(FileStream file,Dictionary<string,object?> value)
    {
        byte[] json=JsonSerializer.SerializeToUtf8Bytes(value,new JsonSerializerOptions{WriteIndented=true});
        if(json.Length>1024*1024)throw new InvalidDataException("Maintenance report size limit");
        file.Write(json);file.Flush(true);
    }
    internal static Dictionary<string,object?> Capture(Options options,Func<bool> current)
    {
        _=options.Profile.Encode();
        if(!current())throw new OperationCanceledException("Owner revoked");
        if(new[]{options.KeyPath,options.JournalPath,options.ReportPath}.Select(Path.GetFullPath)
            .Distinct(StringComparer.OrdinalIgnoreCase).Count()!=3)throw new ArgumentException("Distinct evidence paths required");
        byte[] key=ReadCredential(options.KeyPath);
        DateTime started=DateTime.UtcNow;
        try
        {
            if(!current())throw new OperationCanceledException("Owner revoked");
            string markerPath=options.ReportPath+".pending";
            using(var marker=new FileStream(markerPath,FileMode.CreateNew,FileAccess.Write,FileShare.None))
                Persist(marker,new(){
                    ["schema"]="camarodiag-j1979-native-maintenance-pending-v1",["scope"]=options.Profile.View(),
                    ["started_at_utc"]=started.ToString("O",CultureInfo.InvariantCulture),
                    ["raw_journal_name"]=Path.GetFileName(options.JournalPath),["final_report_name"]=Path.GetFileName(options.ReportPath),
                    ["command_outcome_unknown"]=true,["permits_automatic_retry"]=false});
            using var reportFile=new FileStream(options.ReportPath,FileMode.CreateNew,FileAccess.Write,FileShare.None);
            Dictionary<string,object?>? report=null;
            bool enteredUsb=false;
            string? digest=null;
            try
            {
                using(var raw=new FileStream(options.JournalPath,FileMode.CreateNew,FileAccess.Write,FileShare.None))
                {
                    if(!current())throw new OperationCanceledException("Owner revoked");
                    using var serial=new SerialPort(options.Port,115200,Parity.None,8,StopBits.One){
                        Handshake=Handshake.None,DtrEnable=true,RtsEnable=false,ReadTimeout=100,WriteTimeout=1000};
                    serial.Open();enteredUsb=true;
                    report=new J1979MaintenanceUsb(new J1979SerialPortLink(serial,current)).Run(options.Profile,key,raw,current);
                    raw.Flush(true);
                }
                using var saved=new FileStream(options.JournalPath,FileMode.Open,FileAccess.Read,FileShare.Read);
                if(saved.Length>J1979MaintenanceJournal.MaxJournal)throw new InvalidDataException("CM/1 exceeds byte limit");
                byte[] bytes=new byte[(int)saved.Length];saved.ReadExactly(bytes);
                digest=Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
                if(Equals(report["journal_transfer_verified"],true))
                {
                    var replay=J1979MaintenanceJournal.Inspect(bytes,options.Profile.Vin);
                    if(JsonSerializer.Serialize(replay)!=JsonSerializer.Serialize(report["inspection"]))
                        throw new InvalidDataException("Persisted CM/1 differs from transfer");
                }
                saved.Position=0;
                if(digest!=Convert.ToHexString(SHA256.HashData(saved)).ToLowerInvariant())
                    throw new InvalidDataException("CM/1 changed after transfer");
            }
            catch(Exception e) when(e is IOException or InvalidDataException or UnauthorizedAccessException or
                ArgumentException or InvalidOperationException or TimeoutException or OperationCanceledException or OverflowException)
            {
                if(report is null)report=Failure(options,e,enteredUsb);
                else FailReport(report,e,enteredUsb);
            }
            if(!current())FailReport(report!,new OperationCanceledException("Owner revoked"),enteredUsb);
            report!["journal_sha256"]=digest;
            report["raw_journal_name"]=Path.GetFileName(options.JournalPath);
            report["version"]=typeof(J1979MaintenanceUsbCli).Assembly.GetName().Version?.ToString()??"source";
            report["declared_connector"]=options.Port;
            report["started_at_utc"]=started.ToString("O",CultureInfo.InvariantCulture);
            var finished=DateTime.UtcNow;
            report["finished_at_utc"]=(finished<started?started:finished).ToString("O",CultureInfo.InvariantCulture);
            Persist(reportFile,report);
            return report;
        }
        finally{CryptographicOperations.ZeroMemory(key);}
    }
    internal static int Run(string[] args)
    {
        try
        {
            if(args.Length==0||args[0]!=Command||!OperatingSystem.IsWindows()||Console.IsInputRedirected)return 2;
            var options=Parse(args[1..]);
            if(File.Exists(options.JournalPath)||File.Exists(options.ReportPath)||File.Exists(options.ReportPath+".pending"))
                throw new IOException("New evidence paths required");
            bool fr=args.Contains("--fr",StringComparer.Ordinal);
            string warning=options.Profile.Operation==1?
                (fr?"L’effacement réinitialise les informations de diagnostic et les états de préparation.":"Clearing resets diagnostic information and readiness states."):
                (fr?"Préparation EVAP uniquement, pas un test de fuite.":"EVAP preparation only, not a leak test.");
            Console.Write(warning+(fr?" Véhicule immobilisé, moteur arrêté, local ventilé, carte qualifiée. OK : ":
                " Stationary vehicle, engine stopped, ventilated area, qualified board. Type OK: "));
            if(Console.ReadLine()!="OK")return 2;
            Console.Write($"{options.Profile.Vin} {options.Profile.Tx:X}/{options.Profile.Rx:X} "+
                (fr?"Confirmer une seule opération avec autorisation locale indépendante ? OUI/NON : ":
                    "Confirm one operation with independent local approval? YES/NO: "));
            if(Console.ReadLine()!=(fr?"OUI":"YES"))return 2;
            using var cancel=new CancellationTokenSource();
            ConsoleCancelEventHandler handler=(_,e)=>{e.Cancel=true;cancel.Cancel();};
            Console.CancelKeyPress+=handler;
            Dictionary<string,object?> report;
            try{report=Capture(options,()=>!cancel.IsCancellationRequested);}
            finally{Console.CancelKeyPress-=handler;}
            Console.WriteLine(JsonSerializer.Serialize(report));
            return !Equals(report["journal_transfer_verified"],true)?2:Equals(report["recovery_required"],true)?7:0;
        }
        catch(Exception e) when(e is IOException or InvalidDataException or ArgumentException or
            UnauthorizedAccessException or OperationCanceledException or InvalidOperationException)
        {Console.Error.WriteLine(e.Message);return 2;}
    }
}
