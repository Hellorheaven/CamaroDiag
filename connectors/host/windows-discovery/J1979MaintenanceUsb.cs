using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace CamaroDiagConnectors.Windows;

// Dedicated CU/4 credential and journal session. Never exposes arbitrary
// ECU requests. The proof may start one maintenance attempt, even if its
// USB transmission appears to fail. No automatic retry.
internal sealed class J1979MaintenanceUsb(J1979UsbCapture.ILink link,Func<long>? clock=null)
{
    internal const int HelloSize=67,ChallengeSize=68,ProofSize=36,ResultSize=12;
    private readonly Func<long> now=clock??(()=>Environment.TickCount64);
    private bool used;
    internal sealed record Profile(int Operation,uint Tx,uint Rx,bool Extended,string Vin,int BudgetMs)
    {
        internal byte[] Encode()
        {
            if(Operation is not (1 or 2)||!(Extended?Tx==0x18da10f1&&Rx==0x18daf110:Tx==0x7e0&&Rx==0x7e8)||
                BudgetMs is <1000 or >60000||!J1979LiveNative.ValidVin(Vin))
                throw new InvalidDataException("Invalid primary ECM maintenance scope");
            var b=new byte[31];b[0]=(byte)Operation;b[1]=(byte)(Extended?1:0);
            BinaryPrimitives.WriteUInt32LittleEndian(b.AsSpan(2),Tx);
            BinaryPrimitives.WriteUInt32LittleEndian(b.AsSpan(6),Rx);
            BinaryPrimitives.WriteUInt32LittleEndian(b.AsSpan(10),(uint)BudgetMs);
            Encoding.ASCII.GetBytes(Vin).CopyTo(b,14);return b;
        }
        internal Dictionary<string,object?> View()=>new(){
            ["operation"]=Operation==1?"CLEAR":"EVAP",["extended"]=Extended,
            ["request_can_id"]=Tx.ToString("X"),["response_can_id"]=Rx.ToString("X"),
            ["budget_ms"]=BudgetMs,["expected_vin"]=Vin};
    }
    private static void Require(bool value,string message){if(!value)throw new InvalidDataException(message);}
    internal static byte[] Hello(Profile profile,byte[]? nonce=null,bool archive=false)
    {
        nonce??=RandomNumberGenerator.GetBytes(32);
        Require(nonce.Length==32&&nonce.Any(x=>x!=0),"Invalid CU/4 client nonce");
        return [(byte)'C',(byte)'U',(byte)(archive?5:4),1,..profile.Encode(),..nonce];
    }
    internal static byte[] Proof(byte[] request,byte[] challenge,byte[] key,bool archive=false)
    {
        byte version=(byte)(archive?5:4);
        Require(request.Length==HelloSize&&request.AsSpan(0,4).SequenceEqual(new byte[]{(byte)'C',(byte)'U',version,1})&&
            request.AsSpan(35,32).ToArray().Any(x=>x!=0)&&challenge.Length==ChallengeSize&&
            challenge.AsSpan(0,4).SequenceEqual(new byte[]{(byte)'C',(byte)'U',version,2})&&
            challenge.AsSpan(4,32).ToArray().Any(x=>x!=0)&&key.Length==32,"Invalid CU/4 transcript");
        byte[] transcript=[(byte)(archive?'R':'M'),..request[4..],..challenge[4..36]];
        byte[]? expected=null,answer=null;
        try
        {
            expected=HMACSHA256.HashData(key,transcript);
            Require(CryptographicOperations.FixedTimeEquals(expected,challenge.AsSpan(36,32)),"CU/4 device proof rejected");
            transcript[0]=(byte)(archive?'r':'m');answer=HMACSHA256.HashData(key,transcript);
            return [(byte)'C',(byte)'U',version,3,..answer];
        }
        finally
        {
            CryptographicOperations.ZeroMemory(transcript);
            if(expected is not null)CryptographicOperations.ZeroMemory(expected);
            if(answer is not null)CryptographicOperations.ZeroMemory(answer);
        }
    }
    internal static (int Length,bool Recovery) Header(byte[] b)
    {
        Require(b.Length==ResultSize&&b.AsSpan(0,4).SequenceEqual(new byte[]{(byte)'C',(byte)'U',4,4})&&
            b[4] is 2 or 3&&b[5]==0&&b[6]==0&&b[7]==0,"Invalid CU/4 result envelope");
        uint n=BinaryPrimitives.ReadUInt32LittleEndian(b.AsSpan(8));
        Require(n is >0 and <=J1979MaintenanceJournal.MaxJournal,"Invalid CU/4 journal length");
        return ((int)n,(b[4]&1)!=0);
    }
    private int Remaining(long start,int budget,Func<bool> current)
    {
        if(!current())throw new OperationCanceledException("CU/4 owner revoked");
        long elapsed=now()-start;
        if(elapsed<0||elapsed>=budget)throw new TimeoutException("CU/4 deadline exceeded");
        return budget-(int)elapsed;
    }
    private byte[] Read(int length,long start,int budget,Func<bool> current,Stream? raw,MemoryStream? copy)
    {
        var b=new byte[length];int pos=0;
        while(pos<length)
        {
            int count=link.Read(b,pos,length-pos,Remaining(start,budget,current));
            Require(count>0&&count<=length-pos,"Truncated CU/4 stream");
            if(copy is not null)
            {
                Require(copy.Length+count<=J1979MaintenanceJournal.MaxJournal,"CM/1 size limit");
                copy.Write(b,pos,count);
            }
            raw?.Write(b,pos,count);pos+=count;
            _=Remaining(start,budget,current);
        }
        return b;
    }
    internal Dictionary<string,object?> Run(Profile profile,byte[] key,Stream raw,Func<bool> current,byte[]? nonce=null)
    {
        if(used)throw new InvalidOperationException("Fresh CU/4 session required");
        byte[] hello=Hello(profile,nonce);
        Require(key.Length==32&&raw.CanWrite,"Invalid CU/4 capture");
        used=true;long began=now();
        bool authenticated=false,attempted=false,sent=false,cancelled=false;
        bool? envelopeRecovery=null;
        Dictionary<string,object?>? inspection=null;
        string? error=null;
        using var copy=new MemoryStream();
        try
        {
            long start=now();
            link.Write(hello,Remaining(start,5000,current));
            byte[] challenge=Read(ChallengeSize,start,5000,current,null,null);
            byte[] proof=Proof(hello,challenge,key);authenticated=true;
            try
            {
                link.CheckNoBufferedInput();
                int timeout=Remaining(start,5000,current);
                attempted=true;link.Write(proof,timeout);sent=true;
            }
            finally{CryptographicOperations.ZeroMemory(proof);}
            start=now();int budget=profile.BudgetMs+40000;
            var (length,recovery)=Header(Read(ResultSize,start,budget,current,null,null));
            envelopeRecovery=recovery;
            _=Read(length,start,budget,current,raw,copy);
            _=Remaining(start,budget,current);link.CheckNoBufferedInput();
            _=Remaining(start,budget,current);
            inspection=J1979MaintenanceJournal.Inspect(copy.ToArray(),profile.Vin);
            Require(JsonSerializer.Serialize(inspection["scope"])==JsonSerializer.Serialize(profile.View()),
                "CM/1 scope differs from authenticated CU/4 scope");
            Require(Equals(inspection["recovery_required"],recovery),"CU/4 recovery envelope differs from journal");
        }
        catch(OperationCanceledException e){cancelled=true;error=e.Message;inspection=null;}
        catch(Exception e) when(e is IOException or InvalidDataException or TimeoutException or
            ArgumentException or InvalidOperationException or UnauthorizedAccessException or OverflowException)
        {error=e.Message;inspection=null;}
        finally
        {
            try{link.Dispose();}catch(Exception e) when(e is IOException or InvalidOperationException or TimeoutException){error??=e.Message;}
            try{raw.Flush();}catch(IOException e){error??=e.Message;}
        }
        if(!current()){cancelled=true;error??="CU/4 context revoked";}
        if(error is not null||cancelled)inspection=null;
        var terminal=inspection?.GetValueOrDefault("terminal") as Dictionary<string,object?>;
        return new(){
            ["schema"]="camarodiag-functional-j1979-native-maintenance-v1",["protocol"]="SAE-J1979",
            ["source"]="LIVE_USB_CU4",["scope"]=profile.View(),
            ["device_credential_authenticated"]=authenticated,["proof_transmission_attempted"]=attempted,
            ["client_proof_sent"]=sent,["cancelled"]=cancelled,["journal_transfer_verified"]=inspection is not null,
            ["journal_bytes_received"]=copy.Length,["terminal_present"]=terminal is not null,
            ["recovery_required"]=attempted&&(inspection is null||Equals(inspection["recovery_required"],true)),
            ["command_outcome_unknown"]=attempted&&(terminal is null||Equals(terminal["outcome"],"UNCERTAIN")),
            ["declared_recovery_in_envelope"]=envelopeRecovery,["inspection"]=inspection,
            ["physical_vehicle_verified"]=false,["evidence_authenticated"]=false,
            ["operator_consent_independently_verified"]=false,["permits_automatic_retry"]=false,
            ["permits_programming"]=false,["host_elapsed_ms"]=Math.Max(0,now()-began),["error"]=error};
    }
    internal Dictionary<string,object?> RunArchive(Profile profile,byte[] key,Stream raw,Func<bool> current,byte[]? nonce=null)
    {
        if(used)throw new InvalidOperationException("Fresh CU/5 session required");
        byte[] hello=Hello(profile,nonce,archive:true);
        Require(key.Length==32&&raw.CanWrite,"Invalid CU/5 capture");
        used=true;long began=now();bool authenticated=false;
        Dictionary<string,object?>? declared=null,inspection=null;
        string? error=null;
        using var copy=new MemoryStream();
        try
        {
            long start=now();link.Write(hello,Remaining(start,5000,current));
            byte[] challenge=Read(ChallengeSize,start,5000,current,null,null);
            byte[] proof=Proof(hello,challenge,key,archive:true);authenticated=true;
            try{link.CheckNoBufferedInput();link.Write(proof,Remaining(start,5000,current));}
            finally{CryptographicOperations.ZeroMemory(proof);}
            start=now();const int budget=40000;
            byte[] head=Read(ResultSize,start,budget,current,null,null);
            Require(head.AsSpan(0,4).SequenceEqual(new byte[]{(byte)'C',(byte)'U',5,4})&&
                (head[4]&~7)==0&&head[5] is >=1 and <=4&&head[6]==0&&head[7]==0,"Invalid CU/5 archive header");
            uint length=BinaryPrimitives.ReadUInt32LittleEndian(head.AsSpan(8));
            bool terminal=(head[4]&1)!=0,damaged=(head[4]&2)!=0,recovery=(head[4]&4)!=0;
            Require(length is >=47 and <=J1979MaintenanceJournal.MaxJournal&&(!terminal||head[5]>=2)&&
                ((terminal&&!damaged)||recovery),"Invalid CU/5 archive declaration");
            declared=new(){["bytes"]=(int)length,["records"]=(int)head[5],["terminal_present"]=terminal,
                ["damaged_tail"]=damaged,["recovery_required"]=recovery};
            _=Read((int)length,start,budget,current,raw,copy);
            byte[] trailer=Read(12,start,budget,current,null,null);
            Require(trailer.AsSpan(0,4).SequenceEqual(new byte[]{(byte)'C',(byte)'U',5,5})&&
                BinaryPrimitives.ReadUInt32LittleEndian(trailer.AsSpan(4))==length&&
                BinaryPrimitives.ReadUInt32LittleEndian(trailer.AsSpan(8))==BinaryDiscovery.Crc32(copy.ToArray()),
                "CU/5 closing receipt missing or inconsistent");
            link.CheckNoBufferedInput();_=Remaining(start,budget,current);
            var result=J1979MaintenanceJournal.Inspect(copy.ToArray(),profile.Vin);
            Require(JsonSerializer.Serialize(result["scope"])==JsonSerializer.Serialize(profile.View())&&
                Equals(result["terminal_present"],terminal)&&result["records"] is string[] records&&
                records.Length==head[5]&&(!Equals(result["recovery_required"],true)||recovery),
                "CM/1 archive differs from declaration");
            inspection=result;
        }
        catch(Exception e) when(e is IOException or InvalidDataException or TimeoutException or ArgumentException or
            InvalidOperationException or UnauthorizedAccessException or OperationCanceledException or OverflowException)
        {error=e.Message;}
        finally
        {
            try{link.Dispose();}catch(Exception e) when(e is IOException or InvalidOperationException or TimeoutException){error??=e.Message;}
            try{raw.Flush();}catch(IOException e){error??=e.Message;}
        }
        if(!current())error??="CU/5 context revoked";
        if(error is not null)inspection=null;
        return new(){
            ["schema"]="camarodiag-j1979-native-maintenance-archive-v1",["protocol"]="SAE-J1979",
            ["source"]="READ_ONLY_USB_CU5",["scope"]=profile.View(),["device_credential_authenticated"]=authenticated,
            ["archive_transfer_verified"]=inspection is not null,["archive_close_receipt_verified"]=inspection is not null,
            ["journal_bytes_received"]=copy.Length,
            ["declared_archive"]=declared,["inspection"]=inspection,
            ["recovery_required"]=inspection is null||Equals(declared!["recovery_required"],true),
            ["maintenance_command_sent"]=false,["physical_vehicle_verified"]=false,
            ["physical_stop_independently_verified"]=false,["evidence_authenticated"]=false,
            ["permits_automatic_retry"]=false,["permits_programming"]=false,
            ["host_elapsed_ms"]=Math.Max(0,now()-began),["error"]=error};
    }

}
