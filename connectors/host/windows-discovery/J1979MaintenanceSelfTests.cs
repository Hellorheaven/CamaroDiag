using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace CamaroDiagConnectors.Windows;

internal static class J1979MaintenanceSelfTests
{
    private const string Vin="1G1FA1RXXR0123456";
    private static readonly byte[] Key=Enumerable.Range(0,32).Select(i=>(byte)i).ToArray();
    private static readonly byte[] Nonce=Enumerable.Repeat((byte)'H',32).ToArray();
    private static readonly byte[] Device=Enumerable.Repeat((byte)'D',32).ToArray();
    private static void Check(bool valid,string context)
    {if(!valid)throw new InvalidOperationException("Native maintenance regression: "+context);}
    private static void P16(byte[] b,int at,int n)=>BinaryPrimitives.WriteUInt16LittleEndian(b.AsSpan(at),checked((ushort)n));
    private static void P32(byte[] b,int at,int n)=>BinaryPrimitives.WriteUInt32LittleEndian(b.AsSpan(at),checked((uint)n));
    private static byte[] Row(int stage,int status,string cmd,byte[] reply,int index)
    {
        var request=Convert.FromHexString(cmd);
        var bytes=new byte[18+reply.Length];
        bytes[0]=(byte)stage;bytes[1]=(byte)status;bytes[2]=(byte)request.Length;
        request.CopyTo(bytes,3);P16(bytes,6,reply.Length);
        P32(bytes,8,index*10);P32(bytes,12,index*10+5);P16(bytes,16,1800);
        reply.CopyTo(bytes,18);return bytes;
    }
    private static byte[] Summary(int outcome,int reason,int count,int nrc,int a,int b)
    {
        var raw=new byte[20];raw[0]=(byte)outcome;raw[1]=(byte)reason;raw[2]=(byte)count;
        raw[3]=(byte)nrc;raw[4]=(byte)a;raw[5]=(byte)b;P32(raw,8,count*10-5);return raw;
    }
    private static byte[] Record(J1979MaintenanceUsb.Profile profile,int number,int kind,byte[] summary,
        List<byte[]> rows,int count)
    {
        byte[] payload=kind==1?profile.Encode():[..profile.Encode(),..summary,..rows.Take(count).SelectMany(r=>r)];
        var raw=new byte[12+payload.Length+4];raw[0]=(byte)'C';raw[1]=(byte)'M';raw[2]=1;
        raw[3]=(byte)kind;raw[4]=(byte)number;raw[5]=(byte)count;
        P32(raw,8,payload.Length);payload.CopyTo(raw,12);
        BinaryPrimitives.WriteUInt32LittleEndian(raw.AsSpan(raw.Length-4),BinaryDiscovery.Crc32(raw.AsSpan(0,raw.Length-4)));
        return raw;
    }
    private static byte[][] Fixture(J1979MaintenanceUsb.Profile profile,int failure)
    {
        bool clear=profile.Operation==1;
        string[] plan=clear?["0902","03","07","0A","0101","010D","010C","0902","010D","010C","04",
            "03","07","0A","0101","0902"]:["0902","0800","010D","010C","0902","0800","010D","010C","0801"];
        int[] stages=clear?[1,2,2,2,2,2,2,4,4,4,6,7,7,7,7,7]:[1,2,2,2,4,4,4,4,6];
        int baseline=clear?7:4,intent=clear?10:8,count=failure==0?plan.Length:intent+1;
        var rows=new List<byte[]>();
        for(int i=0;i<count;i++)
        {
            string cmd=plan[i];int status=1;
            byte[] reply=cmd switch{
                "0902"=>[0x49,2,1,..Encoding.ASCII.GetBytes(Vin)],
                "03"=>i<intent?[0x43,1,1,0x23]:[0x43,0],"07"=>[0x47,0],"0A"=>[0x4a,0],
                "0101"=>[0x41,1,0,0,0,0],"010D"=>[0x41,0x0d,0],"010C"=>[0x41,0x0c,0,0],
                "0800"=>[0x48,0,0x80,0,0,0],"04"=>[0x44],"0801"=>[0x48,1],
                _=>throw new InvalidOperationException(cmd)};
            if(i==intent&&failure!=0){
                if(failure==1){status=2;reply=[0x7f,(byte)(clear?4:8),0x22];}
                else{status=3;reply=[];}
            }
            rows.Add(Row(stages[i],status,cmd,reply,i));
        }
        int a=failure==0?(clear?255:63):(clear?183:55);
        int b=failure==0?(clear?31:16):failure==1?16:48;
        return [
            Record(profile,0,1,[],rows,0),
            Record(profile,1,2,Summary(0,0,baseline,0,clear?144:16,0),rows,baseline),
            Record(profile,2,3,Summary(0,0,intent,0,clear?177:49,0),rows,intent),
            Record(profile,3,4,Summary(failure==0?(clear?5:6):failure==1?2:3,
                failure==0?0:failure==1?11:10,count,failure==1?0x22:0,a,b),rows,count)
        ];
    }
    private static void Reject(byte[] bytes,bool prefix,string vin=Vin)
    {
        bool rejected=false;
        try{_ =prefix?J1979MaintenanceJournal.InspectPrefix(bytes,vin):J1979MaintenanceJournal.Inspect(bytes,vin);}
        catch(InvalidDataException){rejected=true;}
        Check(rejected,"invalid journal rejected");
    }
    private sealed class Sim(byte[] challenge,byte[] response,byte[] hello,byte[] proof):J1979UsbCapture.ILink
    {
        private byte[] available=[];
        private int offset;
        internal int Writes;
        internal bool Closed,FailProof;
        public int Read(byte[] target,int pos,int count,int timeout)
        {
            int n=Math.Min(7,Math.Min(count,available.Length-offset));
            Array.Copy(available,offset,target,pos,n);offset+=n;return n;
        }
        public void Write(byte[] data,int timeout)
        {
            Writes++;
            if(Writes==1){Check(data.SequenceEqual(hello),"exact hello");available=challenge;offset=0;}
            else if(Writes==2){
                Check(data.SequenceEqual(proof),"exact proof");
                if(FailProof)throw new IOException("Uncertain proof write");
                available=response;offset=0;
            }else throw new InvalidOperationException("Unexpected CU/4 retry");
        }
        public void CheckNoBufferedInput()
        {if(offset!=available.Length)throw new IOException("Trailing CU/4 data");}
        public void Dispose(){Closed=true;}
    }
    private static byte[] Envelope(byte[] raw,bool recovery)
    {
        var header=new byte[12];header[0]=(byte)'C';header[1]=(byte)'U';header[2]=4;header[3]=4;
        header[4]=(byte)(recovery?3:2);P32(header,8,raw.Length);return [..header,..raw];
    }
    private static void Capture(J1979MaintenanceUsb.Profile profile,byte[] raw,bool recovery)
    {
        byte[] hello=J1979MaintenanceUsb.Hello(profile,Nonce);
        byte[] transcript=[(byte)'M',..hello[4..],..Device];
        byte[] challenge=[(byte)'C',(byte)'U',4,2,..Device,..HMACSHA256.HashData(Key,transcript)];
        transcript[0]=(byte)'m';
        byte[] proof=[(byte)'C',(byte)'U',4,3,..HMACSHA256.HashData(Key,transcript)];
        Check(J1979MaintenanceUsb.Proof(hello,challenge,Key).SequenceEqual(proof),"independent CU/4 proof");
        for(int which=0;which<5;which++)
        {
            byte[] ch=challenge.ToArray(),response=Envelope(raw,recovery);
            if(which==1)ch[36]^=1;
            if(which==2)response=response[..^1];
            if(which==4)response[4]=(byte)(recovery?2:3);
            var sim=new Sim(ch,response,hello,proof){FailProof=which==3};
            using var output=new MemoryStream();
            var capture=new J1979MaintenanceUsb(sim,()=>0);
            var report=capture.Run(profile,Key,output,()=>true,Nonce);
            Check(sim.Closed&&Equals(report["permits_automatic_retry"],false),"one shot closure");
            if(which==0){
                Check(Equals(report["journal_transfer_verified"],true)&&Equals(report["recovery_required"],recovery)&&
                    output.ToArray().SequenceEqual(raw),"original journal verified");
            }else{
                Check(Equals(report["journal_transfer_verified"],false)&&Equals(report["recovery_required"],which!=1)&&
                    sim.Writes==(which==1?1:2),"failed proof or uncertain result");
            }
            bool blocked=false;
            try{capture.Run(profile,Key,output,()=>true,Nonce);}
            catch(InvalidOperationException){blocked=true;}
            Check(blocked,"session cannot be reused");
        }
    }
    private static byte[] ArchiveEnvelope(byte[] raw,int records,int flags)
    {
        var header=new byte[12];header[0]=(byte)'C';header[1]=(byte)'U';header[2]=5;header[3]=4;
        header[4]=(byte)flags;header[5]=(byte)records;P32(header,8,raw.Length);
        var tail=new byte[12];tail[0]=(byte)'C';tail[1]=(byte)'U';tail[2]=5;tail[3]=5;
        P32(tail,4,raw.Length);
        BinaryPrimitives.WriteUInt32LittleEndian(tail.AsSpan(8),BinaryDiscovery.Crc32(raw));
        return [..header,..raw,..tail];
    }
    private static void Archive(J1979MaintenanceUsb.Profile profile,byte[][] records,bool recovery)
    {
        byte[] hello=[(byte)'C',(byte)'U',5,1,..profile.Encode(),..Nonce];
        byte[] transcript=[(byte)'R',..hello[4..],..Device];
        byte[] challenge=[(byte)'C',(byte)'U',5,2,..Device,..HMACSHA256.HashData(Key,transcript)];
        transcript[0]=(byte)'r';
        byte[] proof=[(byte)'C',(byte)'U',5,3,..HMACSHA256.HashData(Key,transcript)];
        for(int count=1;count<=records.Length;count++)
        {
            byte[] raw=records.Take(count).SelectMany(x=>x).ToArray();
            bool terminal=count==4,required=!terminal||recovery;
            int flags=(terminal?1:0)|(required?4:0);
            for(int scenario=0;scenario<12;scenario++)
            {
                byte[] ch=challenge.ToArray(),response=ArchiveEnvelope(raw,count,flags);
                switch(scenario){
                    case 1:ch[36]^=1;break;
                    case 2:response=response[..^12];break;
                    case 3:response[^1]^=1;break;
                    case 4:response=[..response,0];break;
                    case 5:response[5]=(byte)(count==4?3:4);break;
                    case 6:response[2]=4;break;
                    case 7:response[4]=(byte)(flags^1);break;
                    case 8:response[22]^=1;break;
                    case 9:response=response[..(12+raw.Length/2)];break;
                }
                var sim=new Sim(ch,response,hello,proof){FailProof=scenario==10};
                using var output=new MemoryStream();
                var capture=new J1979MaintenanceUsb(sim,()=>0);
                bool Current()=>scenario!=11||sim.Writes<2;
                var report=capture.RunArchive(profile,Key,output,Current,Nonce);
                Check(sim.Closed&&Equals(report["maintenance_command_sent"],false)&&
                    Equals(report["physical_vehicle_verified"],false)&&Equals(report["permits_automatic_retry"],false),
                    "archive is read-only and closed");
                Check(Equals(report["archive_transfer_verified"],scenario==0)&&
                    Equals(report["archive_close_receipt_verified"],scenario==0)&&
                    Equals(report["recovery_required"],scenario!=0||required),"archive closure and recovery");
                if(scenario==0){
                    Check(output.ToArray().SequenceEqual(raw),"preserve original CM/1 bytes");
                    Check(System.Text.Json.JsonSerializer.Serialize(report["inspection"])==
                        System.Text.Json.JsonSerializer.Serialize(J1979MaintenanceJournal.Inspect(raw,Vin)),"independent replay");
                }else Check(report["inspection"] is null,"no false successful inspection");
                Check(sim.Writes==(scenario==1?1:2),"no retries or other command");
                bool reused=false;
                try{capture.RunArchive(profile,Key,output,()=>true,Nonce);}
                catch(InvalidOperationException){reused=true;}
                Check(reused,"CU/5 cannot be reused");
            }
        }
    }
    internal static void Run()
    {
        foreach(int operation in new[]{1,2})
        {
            var profile=new J1979MaintenanceUsb.Profile(operation,0x7e0,0x7e8,false,Vin,60000);
            foreach(int failure in new[]{0,1,2})
            {
                byte[][] records=Fixture(profile,failure);
                byte[] full=records.SelectMany(x=>x).ToArray();
                var report=J1979MaintenanceJournal.Inspect(full,Vin);
                var terminal=(Dictionary<string,object?>)report["terminal"]!;
                string outcome=failure==0?(operation==1?"ACKNOWLEDGED_READBACK":"ACKNOWLEDGED_EVAP"):
                    failure==1?"REFUSED":"UNCERTAIN";
                Check(Equals(terminal["outcome"],outcome)&&Equals(report["recovery_required"],failure==2),"terminal outcome");
                byte[] prefix=records.Take(3).SelectMany(x=>x).ToArray();
                foreach(int length in new[]{0,1,5,12,records[3].Length-1})
                {
                    var partial=J1979MaintenanceJournal.InspectPrefix([..prefix,..records[3][..length]],Vin);
                    Check(Equals(partial["prefix_integrity_verified"],true)&&Equals(partial["record_consistency_verified"],false)&&
                        Equals(partial["terminal_present"],false)&&Equals(partial["recovery_required"],true)&&
                        Equals(partial["verified_prefix_bytes"],prefix.Length)&&Equals(partial["unverified_trailing_bytes"],length),
                        "torn prefix remains unresolved");
                }
                Reject(full,true);Reject(prefix,true,"1FA6P8TH0L5100000");
                byte[] corrupt=prefix.ToArray();corrupt[^1]^=1;Reject(corrupt,true);Reject([..prefix,(byte)'X'],true);
                Capture(profile,full,failure==2);
                Archive(profile,records,failure==2);
            }
        }
    }
}
