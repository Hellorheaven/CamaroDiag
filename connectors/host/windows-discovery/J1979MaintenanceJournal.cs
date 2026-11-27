using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace CamaroDiagConnectors.Windows;

// Independent CM/1 replay, no hardware or credential access. A journal
// declaration of a durable intent or verified stop is not physical proof.
internal static class J1979MaintenanceJournal
{
    internal const int Header=12,ScopeSize=31,SummarySize=20,RowSize=18,MaxRows=16,MaxReply=512;
    internal const int MaxRecord=Header+ScopeSize+SummarySize+MaxRows*(RowSize+MaxReply)+4,MaxJournal=4*MaxRecord;
    private static readonly string[] Kinds=["","OPEN","BASELINE","INTENT","TERMINAL"];
    private static readonly string[] Outcomes=["RUNNING","NOT_TESTED","REFUSED","UNCERTAIN","ACKNOWLEDGED_PARTIAL","ACKNOWLEDGED_READBACK","ACKNOWLEDGED_EVAP"];
    private static readonly string[] Reasons=["NONE","INVALID_SCOPE","CONTEXT","DEADLINE","SEQUENCE","IDENTITY","STATIONARY",
        "UNSUPPORTED","BASELINE","JOURNAL","TRANSPORT","NEGATIVE","READBACK","DTC_REMAINING"];
    private static readonly string[] FlagsA=["baseline_persisted","intent_persisted","attempted","acknowledged","initial_vin","rechecked_vin","final_vin","before_confirmed"];
    private static readonly string[] FlagsB=["after_confirmed","after_pending","after_confirmed_empty","after_pending_empty","physical_stop_declared","recovery_required"];
    private static readonly (int Stage,string Command)[] Clear=[
        (1,"0902"),(2,"03"),(2,"07"),(2,"0A"),(2,"0101"),(2,"010D"),(2,"010C"),
        (4,"0902"),(4,"010D"),(4,"010C"),(6,"04"),(7,"03"),(7,"07"),(7,"0A"),(7,"0101"),(7,"0902")];
    private static readonly (int Stage,string Command)[] Evap=[
        (1,"0902"),(2,"0800"),(2,"010D"),(2,"010C"),(4,"0902"),(4,"0800"),(4,"010D"),(4,"010C"),(6,"0801")];
    private static void Require(bool value,string error){if(!value)throw new InvalidDataException(error);}
    private static int U16(ReadOnlySpan<byte> b,int at)=>BinaryPrimitives.ReadUInt16LittleEndian(b[at..]);
    private static long U32(ReadOnlySpan<byte> b,int at)=>BinaryPrimitives.ReadUInt32LittleEndian(b[at..]);
    private static string StageName(int stage)=>stage switch{1=>"INITIAL",2=>"BASELINE",4=>"RECHECK",6=>"COMMAND",7=>"READBACK",_=>throw new InvalidDataException("Invalid stage")};
    private static string StatusName(int status)=>status switch{1=>"POSITIVE",2=>"NEGATIVE",3=>"TIMEOUT",4=>"FAULT",_=>throw new InvalidDataException("Invalid status")};
    private sealed record Scope(int Operation,bool Extended,long Tx,long Rx,long Budget,string Vin)
    {
        internal (int Stage,string Command)[] Plan=>Operation==1?Clear:Evap;
        internal int Baseline=>Operation==1?7:4;
        internal int Intent=>Operation==1?10:8;
        internal Dictionary<string,object?> View()=>new(){
            ["operation"]=Operation==1?"CLEAR":"EVAP",["extended"]=Extended,["request_can_id"]=Tx.ToString("X"),
            ["response_can_id"]=Rx.ToString("X"),["budget_ms"]=Budget,["expected_vin"]=Vin};
        internal static Scope Decode(ReadOnlySpan<byte> b)
        {
            Require(b.Length==ScopeSize&&b[0] is 1 or 2&&b[1]<=1,"Invalid CM/1 scope");
            long tx=U32(b,2),rx=U32(b,6),budget=U32(b,10);
            string vin=Encoding.ASCII.GetString(b[14..]);
            Require((b[1]==0?tx==0x7e0&&rx==0x7e8:tx==0x18da10f1&&rx==0x18daf110)&&
                budget is >=1000 and <=60000&&J1979LiveNative.ValidVin(vin),"Invalid maintenance target");
            return new(b[0],b[1]==1,tx,rx,budget,vin);
        }
    }
    private sealed record Event(int Stage,int Status,string Command,string? Response,long Start,long End,int Timeout)
    {
        internal Dictionary<string,object?> View()=>new(){
            ["stage"]=StageName(Stage),["status"]=StatusName(Status),["request_hex"]=Command,["response_hex"]=Response,
            ["started_ms"]=Start,["ended_ms"]=End,["timeout_ms"]=Timeout};
    }
    private sealed record Summary(int Outcome,int Reason,int Count,int Nrc,int A,int B,long Duration)
    {
        internal bool HasA(int bit)=>(A&(1<<bit))!=0;
        internal bool HasB(int bit)=>(B&(1<<bit))!=0;
        internal Dictionary<string,object?> View()
        {
            var d=new Dictionary<string,object?>{["outcome"]=Outcomes[Outcome],["reason"]=Reasons[Reason],
                ["observations"]=Count,["nrc"]=Nrc,["duration_ms"]=Duration};
            for(int i=0;i<FlagsA.Length;i++)d[FlagsA[i]]=HasA(i);
            for(int i=0;i<FlagsB.Length;i++)d[FlagsB[i]]=HasB(i);
            return d;
        }
        internal static Summary Decode(ReadOnlySpan<byte> b,int count)
        {
            Require(b.Length==SummarySize&&b[0]<Outcomes.Length&&b[1]<Reasons.Length&&b[2]==count&&
                (b[5]&0xc0)==0&&b[6]==0&&b[7]==0&&!b[12..].ToArray().Any(x=>x!=0),"Invalid CM/1 summary");
            return new(b[0],b[1],b[2],b[3],b[4],b[5],U32(b,8));
        }
    }
    private sealed record Entry(int Kind,Scope Scope,Summary? Summary,Event[] Rows);
    private static List<Entry> Parse(byte[] data)
    {
        Require(data.Length is >0 and <=MaxJournal,"Invalid CM/1 journal size");
        int pos=0,prior=0;
        var result=new List<Entry>();
        while(pos<data.Length)
        {
            Require(result.Count<4&&data.Length-pos>=Header,"Truncated or excessive CM/1 records");
            ReadOnlySpan<byte> h=data.AsSpan(pos,Header);
            int kind=h[3],count=h[5];
            long len=U32(h,8);
            Require(h[0]=='C'&&h[1]=='M'&&h[2]==1&&h[4]==result.Count&&U16(h,6)==0&&
                kind is >=1 and <=4&&count<=MaxRows&&prior!=4&&
                (prior==0?kind==1:kind==prior+1||kind==4),"Unexpected CM/1 header");
            Require(len>=ScopeSize&&len<=MaxRecord-Header-4&&len+Header+4<=data.Length-pos,"CM/1 body size");
            int end=pos+Header+(int)len;
            Require(BinaryDiscovery.Crc32(data.AsSpan(pos,end-pos))==U32(data,end),"CM/1 CRC mismatch");
            ReadOnlySpan<byte> b=data.AsSpan(pos+Header,(int)len);
            var scope=Scope.Decode(b[..ScopeSize]);int cursor=ScopeSize;
            Summary? summary=null;
            var rows=new List<Event>();
            if(kind==1)Require(count==0&&cursor==b.Length,"Unexpected open payload");
            else
            {
                Require(cursor+SummarySize<=b.Length,"Truncated summary");
                summary=Summary.Decode(b.Slice(cursor,SummarySize),count);cursor+=SummarySize;
                for(int i=0;i<count;i++)
                {
                    Require(cursor+RowSize<=b.Length,"Truncated CM/1 observation");
                    var row=b.Slice(cursor,RowSize);cursor+=RowSize;
                    int stage=row[0],status=row[1],n=row[2],size=U16(row,6),timeout=U16(row,16);
                    long start=U32(row,8),finish=U32(row,12);
                    Require(stage is 1 or 2 or 4 or 6 or 7&&status is >=1 and <=4&&n is 1 or 2&&
                        row[5]==0&&(n==2||row[4]==0)&&size<=MaxReply&&cursor+size<=b.Length&&
                        start<=finish&&timeout is >=1 and <=1800,"Malformed CM/1 observation");
                    rows.Add(new(stage,status,Convert.ToHexString(row.Slice(3,n)),
                        size==0?null:Convert.ToHexString(b.Slice(cursor,size)),start,finish,timeout));
                    cursor+=size;
                }
                Require(cursor==b.Length,"Trailing CM/1 record bytes");
            }
            result.Add(new(kind,scope,summary,rows.ToArray()));prior=kind;pos=end+4;
        }
        return result;
    }
    private static int Shape(Event e,byte[] req,byte[] reply)
    {
        if(reply.Length==0)return e.Status is 3 or 4?e.Status:0;
        if(reply[0]==0x7f)return reply.Length==3&&reply[1]==req[0]&&reply[2] is not (0 or 0x78)?2:0;
        if(e.Command=="04")return e.Response=="44"?1:0;
        if(e.Command=="0801")return e.Response=="4801"?1:0;
        if(req[0] is 3 or 7 or 10)
        {
            if(reply.Length<2||reply[0]!=req[0]+64||reply.Length!=2+2*reply[1])return 0;
            for(int i=2;i<reply.Length;i+=2)if(reply[i]==0&&reply[i+1]==0)return 0;
            return 1;
        }
        if(e.Command=="0902")return reply.Length==20&&reply[0]==0x49&&reply[1]==2&&reply[2]==1&&
            J1979LiveNative.ValidVin(Encoding.ASCII.GetString(reply,3,17))?1:0;
        int size=e.Command switch{"0101" or "0800"=>6,"010D"=>3,"010C"=>4,_=>0};
        return size>0&&reply.Length==size&&reply[0]==req[0]+64&&reply[1]==req[1]?1:0;
    }
    private static Summary Replay(Scope scope,Event[] rows,bool baseline,bool intent)
    {
        int outcome=0,reason=0,a=0,b=0,nrc=0,count=0;
        long now=0,identity=-1,speed=-1,rpm=-1;
        void Abort(int why){reason=why;outcome=(a&8)!=0?4:(a&6)!=0?3:1;}
        Require(rows.Length<=scope.Plan.Length,"Too many observations");
        for(int i=0;i<rows.Length;i++)
        {
            var e=rows[i];var expected=scope.Plan[i];
            Require(outcome==0&&e.Stage==expected.Stage&&e.Command==expected.Command,"Unexpected maintenance request");
            Require(e.Start>=now&&e.Start<scope.Budget&&e.Timeout<=scope.Budget-e.Start,"Invalid deadline");
            if(i==scope.Baseline){Require(baseline,"Missing baseline");a|=1;}
            if(i==scope.Intent)
            {
                Require(intent&&baseline&&identity>=0&&speed>=0&&rpm>=0&&
                    e.Start-identity<=5000&&e.Start-speed<=5000&&e.Start-rpm<=5000,"Command without fresh checks and intent");
                a|=6;
            }
            now=e.End;count++;
            if(now>=scope.Budget||now-e.Start>e.Timeout){Abort(3);continue;}
            byte[] req=Convert.FromHexString(e.Command),reply=e.Response is null?[]:Convert.FromHexString(e.Response);
            if(e.Status==4){Require(reply.Length==0,"Fault carries data");Abort(10);continue;}
            if(Shape(e,req,reply)!=e.Status){Abort(10);continue;}
            bool positive=e.Status==1;
            if(e.Stage==6)
            {
                if(e.Status==2){outcome=2;reason=11;nrc=reply[2];}
                else if(!positive)Abort(10);
                else {a|=8;if(scope.Operation==2)outcome=6;}
            }
            else if(e.Command=="0902")
            {
                if(!positive||Encoding.ASCII.GetString(reply,3,17)!=scope.Vin)Abort(5);
                else if(e.Stage==1)a|=16;
                else if(e.Stage==4){a|=32;identity=now;}
                else a|=64;
            }
            else if(e.Command is "010D" or "010C")
            {
                if(!positive||reply.Skip(2).Any(x=>x!=0))Abort(6);
                else if(e.Stage==4){if(e.Command=="010D")speed=now;else rpm=now;}
            }
            else if(e.Command=="0800"){if(!positive||(reply[2]&0x80)==0)Abort(7);}
            else if(e.Stage==2&&e.Command=="03"){if(!positive)Abort(8);else a|=128;}
            else if(e.Stage==7&&e.Command is "03" or "07")
            {
                if(positive){int bit=e.Command=="03"?1:2;b|=bit;if(reply[1]==0)b|=bit<<2;}
            }
            if(i==scope.Plan.Length-1&&scope.Operation==1&&outcome==0)
            {
                if((b&3)==3&&(a&64)!=0){outcome=5;reason=(b&12)==12?0:13;}
                else Abort(12);
            }
        }
        return new(outcome,reason,count,nrc,a,b,now);
    }
    internal static Dictionary<string,object?> Inspect(byte[] data,string expectedVin)
    {
        Require(J1979LiveNative.ValidVin(expectedVin),"Independent VIN required");
        var entries=Parse(data);var scope=entries[0].Scope;
        Require(scope.Vin==expectedVin,"CM/1 differs from independent VIN");
        Event[] previous=[];
        bool baseline=false,intent=false;
        Summary? terminal=null;
        foreach(var entry in entries.Skip(1))
        {
            Require(entry.Scope==scope&&entry.Rows.Length>=previous.Length&&
                entry.Rows.Take(previous.Length).SequenceEqual(previous),"CM/1 prior evidence changed");
            var state=Replay(scope,entry.Rows,baseline,intent);var claimed=entry.Summary!;
            if(entry.Kind==2)
            {
                Require(entry.Rows.Length==scope.Baseline&&state.Outcome==0&&state==claimed,"Invalid baseline");
                baseline=true;
            }
            else if(entry.Kind==3)
            {
                Require(baseline&&entry.Rows.Length==scope.Intent&&state.Outcome==0&&state==claimed,"Invalid intent");
                intent=true;
            }
            else
            {
                Require(claimed.HasB(4),"Terminal missing shutdown declaration");
                if(state.Outcome==0)
                {
                    Require(claimed.Reason is 2 or 3 or 4 or 6 or 9 or 10&&claimed.Duration>=state.Duration&&
                        (claimed.Reason!=3||claimed.Duration>=scope.Budget),"Invalid external terminal declaration");
                    int a=state.A;
                    if(entry.Rows.Length==scope.Baseline&&baseline)a=(a&~1)|(claimed.A&1);
                    if(entry.Rows.Length==scope.Intent&&intent)a=(a&~2)|(claimed.A&2);
                    state=state with{A=a,Outcome=(a&8)!=0?4:(a&6)!=0?3:1,Reason=claimed.Reason,Duration=claimed.Duration};
                }
                state=state with{B=state.B|16|(claimed.B&32)};
                Require((state.Outcome!=3||state.HasB(5))&&state==claimed,"Terminal summary differs from replay");
                terminal=state;
            }
            previous=entry.Rows;
        }
        return new(){
            ["schema"]="camarodiag-j1979-native-maintenance-inspection-v1",["protocol"]="SAE-J1979",
            ["scope"]=scope.View(),["record_consistency_verified"]=true,["records"]=entries.Select(x=>Kinds[x.Kind]).ToArray(),
            ["baseline_record_present"]=baseline,["intent_record_present"]=intent,["terminal_present"]=terminal is not null,
            ["terminal"]=terminal?.View(),["observations"]=previous.Select(x=>x.View()).ToArray(),
            ["recovery_required"]=terminal is null||terminal.HasB(5),["physical_vehicle_verified"]=false,
            ["physical_stop_independently_verified"]=false,["durable_before_tx_independently_verified"]=false,
            ["operator_consent_verified"]=false,["monitor_completion_verified"]=false,["evidence_authenticated"]=false,
            ["permits_automatic_retry"]=false,["permits_programming"]=false};
    }
    internal const string Command="--inspect-j1979-maintenance-journal";
    internal static Dictionary<string,object?> InspectFile(string source,string vin,string destination,Func<bool>? current=null)
    {
        Require(!Path.GetFullPath(source).Equals(Path.GetFullPath(destination),StringComparison.OrdinalIgnoreCase),"Distinct input and output required");
        if(current is not null&&!current())throw new OperationCanceledException();
        Require((File.GetAttributes(source)&(FileAttributes.ReparsePoint|FileAttributes.Directory))==0,"Regular CM/1 file required");
        using var input=new FileStream(source,FileMode.Open,FileAccess.Read,FileShare.Read);
        Require(input.Length is >0 and <=MaxJournal,"Invalid CM/1 input size");
        byte[] bytes=new byte[(int)input.Length];input.ReadExactly(bytes);
        Require(input.ReadByte()==-1,"CM/1 grew during read");
        string digest=Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        var report=Inspect(bytes,vin);report["journal_sha256"]=digest;
        input.Position=0;
        Require(digest==Convert.ToHexString(SHA256.HashData(input)).ToLowerInvariant(),"CM/1 changed during inspection");
        byte[] json=JsonSerializer.SerializeToUtf8Bytes(report,new JsonSerializerOptions{WriteIndented=true});
        if(current is not null&&!current())throw new OperationCanceledException();
        using var output=new FileStream(destination,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        output.Write(json);output.Flush(true);return report;
    }
    internal static int Run(string[] args)
    {
        try{
            Require(args.Length==4&&args[0]==Command,"Expected CM/1 source, independent VIN and new JSON path");
            var report=InspectFile(args[1],args[2],args[3]);
            Console.WriteLine(JsonSerializer.Serialize(report));
            return Equals(report["recovery_required"],true)?7:0;
        }catch(Exception e) when(e is IOException or ArgumentException or UnauthorizedAccessException or InvalidDataException or OperationCanceledException)
        {Console.Error.WriteLine(e.Message);return 2;}
    }
}
