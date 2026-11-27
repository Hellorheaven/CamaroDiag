using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace CamaroDiagConnectors.Windows;

/// <summary>Explicit CU/3 authentication and independent CL/1 application
/// replay. Saved bytes cannot prove a device credential or vehicle provenance.</summary>
internal static class J1979LiveNative
{
    internal const int ScopeSize=58,PlanSize=67,AuthSize=71,HelloSize=107,ChallengeSize=68,ProofSize=36;
    internal const int MaximumSamples=10000,MaximumSteps=11010,MaximumRaw=16*1024*1024;
    internal const int ObservationSize=26,FinalSize=128,MaximumBody=ObservationSize+4095;
    internal const int MaximumCapture=MaximumRaw+(MaximumSteps+2)*38+1024;
    internal static void Require(bool good,string message){if(!good)throw new InvalidDataException(message);}
    internal static bool ValidVin(string? vin)=>vin is not null&&Regex.IsMatch(vin,@"\A[A-HJ-NPR-Z0-9]{17}\z");
    private static ushort U16(ReadOnlySpan<byte> data,int offset)=>BinaryPrimitives.ReadUInt16LittleEndian(data[offset..]);
    private static uint U32(ReadOnlySpan<byte> data,int offset)=>BinaryPrimitives.ReadUInt32LittleEndian(data[offset..]);
    private static void P16(Span<byte> data,int offset,int value)=>BinaryPrimitives.WriteUInt16LittleEndian(data[offset..],checked((ushort)value));
    private static void P32(Span<byte> data,int offset,long value)=>BinaryPrimitives.WriteUInt32LittleEndian(data[offset..],checked((uint)value));
    internal sealed record Profile(uint Tx,uint Rx,bool Extended,string Vin,int[] Pids,
        int Cycles=30,int IntervalMs=1000,int BudgetMs=180000)
    {
        internal Profile Validate()
        {
            Require(J1979UsbAuth.PhysicalPair(Tx,Rx,Extended)&&ValidVin(Vin)&&
                Pids is {Length:>=1 and <=32}&&Pids.Distinct().Count()==Pids.Length&&
                Pids.All(x=>x is >=1 and <=255&&x%32!=0)&&Cycles is >=1 and <=1000&&
                Cycles*Pids.Length<=MaximumSamples&&IntervalMs is >=100 and <=60000&&
                BudgetMs is >=1000 and <=600000,"Invalid CU/3 physical live plan");
            return this with{Pids=Pids.ToArray()};
        }
        internal byte[] Encode()
        {
            var p=Validate();var data=new byte[PlanSize];
            P32(data,0,p.Tx);P32(data,4,p.Rx);data[8]=p.Extended?(byte)1:(byte)0;
            Encoding.ASCII.GetBytes(p.Vin).CopyTo(data,9);data[26]=(byte)p.Pids.Length;
            for(int i=0;i<p.Pids.Length;i++)data[27+i]=(byte)p.Pids[i];
            P16(data,59,p.Cycles);P16(data,61,p.IntervalMs);P32(data,63,p.BudgetMs);return data;
        }
        internal byte[] EncodeAuth()
        {
            var plan=Encode();var data=new byte[AuthSize];
            plan.AsSpan(0,9).CopyTo(data);P32(data,9,BudgetMs);plan.AsSpan(9).CopyTo(data.AsSpan(13));
            return data;
        }
        internal static Profile Decode(ReadOnlySpan<byte> data)
        {
            Require(data.Length==PlanSize&&data[8]<=1&&data[26] is >=1 and <=32,"Invalid CL/1 plan");
            var p=new Profile(U32(data,0),U32(data,4),data[8]==1,Encoding.ASCII.GetString(data.Slice(9,17)),
                data.Slice(27,data[26]).ToArray().Select(x=>(int)x).ToArray(),U16(data,59),U16(data,61),
                checked((int)U32(data,63))).Validate();
            Require(data.SequenceEqual(p.Encode()),"Noncanonical CL/1 scope");return p;
        }
        internal static Profile DecodeAuth(ReadOnlySpan<byte> data)
        {
            Require(data.Length==AuthSize,"Invalid CU/3 profile size");
            Span<byte> plan=stackalloc byte[PlanSize];
            data[..9].CopyTo(plan);data[13..].CopyTo(plan[9..]);
            var p=Decode(plan);
            Require(data.SequenceEqual(p.EncodeAuth()),"Contradictory CU/3 budget");return p;
        }
        internal ProfileView View()=>new(Tx.ToString("X"),Rx.ToString("X"),Extended,Vin,Pids.ToArray(),
            Cycles,IntervalMs,BudgetMs,true);
    }
    internal sealed record ProfileView(string RequestCanId,string ResponseCanId,bool Extended,string ExpectedVin,
        int[] SelectedPids,int RequestedCycles,int IntervalMs,int BudgetMs,bool RawOnly);
    internal sealed record PidCount(int Pid,int Count);
    internal sealed record Terminal(string? StopReason,bool Completed,bool InitialIdentityVerified,
        bool FinalIdentityVerified,bool InventoryComplete,int CompletedCycles,int Steps,int Samples,int Raw,
        int Negative,int Timeout,long DurationMs,int RawBytes,int[] AdvertisedPids,PidCount[] RawPerPid);
    internal sealed record Sample(int Cycle,int Pid,long ElapsedMs,string Status,string? ResponseHex);
    internal sealed record Observation(string Phase,string Status,bool Accepted,int? Cycle,int? Pid,
        long StartedElapsedMs,long ElapsedMs,long DurationMs,long ExchangeDurationMs,int TimeoutMs,
        string RequestHex,string? ResponseHex);
    internal sealed record Result(string Schema,bool Verified,bool RawOnly,string DeclaredVin,
        Dictionary<string,object> Channel,bool Completed,bool FinalIdentityVerified,
        Sample[] Samples,Observation[] Observations,bool PhysicalVehicleVerified=false,bool Authenticated=false);
    internal sealed record Report(string Schema,string Protocol,string Source,ProfileView? Profile,
        bool CaptureIntegrityVerified,bool DeviceCredentialAuthenticated,bool HardwareProvenanceVerified,
        bool EvidenceAuthenticated,bool PhysicalVehicleVerified,bool PermitsProgramming,
        int ObservationsReceived,Terminal? Summary,Result? Result,string? Error)
    {
        internal bool ClientProofSent {get;init;}
        internal bool Cancelled {get;init;}
        internal long? HostElapsedMs {get;init;}
    }
    private static bool Nonzero(ReadOnlySpan<byte> data)
    {
        foreach(byte value in data)if(value!=0)return true;
        return false;
    }
    internal static byte[] Hello(Profile profile,byte[]? nonce=null)
    {
        nonce??=RandomNumberGenerator.GetBytes(32);
        Require(nonce.Length==32&&Nonzero(nonce),"Invalid CU/3 client nonce");
        var data=new byte[HelloSize];
        "CU\x03\x01"u8.CopyTo(data);profile.EncodeAuth().CopyTo(data,4);nonce.CopyTo(data,4+AuthSize);
        return data;
    }
    internal static byte[] Proof(ReadOnlySpan<byte> request,ReadOnlySpan<byte> challenge,ReadOnlySpan<byte> key)
    {
        Require(request.Length==HelloSize&&challenge.Length==ChallengeSize&&key.Length==32&&
            request[..4].SequenceEqual("CU\x03\x01"u8)&&challenge[..4].SequenceEqual("CU\x03\x02"u8)&&
            Nonzero(request[(4+AuthSize)..])&&Nonzero(challenge[4..36]),"Invalid CU/3 transcript");
        _=Profile.DecodeAuth(request.Slice(4,AuthSize));
        Span<byte> transcript=stackalloc byte[1+AuthSize+64];
        request[4..].CopyTo(transcript[1..]);challenge[4..36].CopyTo(transcript[(1+AuthSize+32)..]);
        try
        {
            transcript[0]=(byte)'L';var server=HMACSHA256.HashData(key,transcript);
            try{Require(CryptographicOperations.FixedTimeEquals(server,challenge[36..]),"CU/3 server proof rejected");}
            finally{CryptographicOperations.ZeroMemory(server);}
            transcript[0]=(byte)'l';var tag=HMACSHA256.HashData(key,transcript);
            try
            {
                var result=new byte[ProofSize];"CU\x03\x03"u8.CopyTo(result);tag.CopyTo(result,4);return result;
            }
            finally{CryptographicOperations.ZeroMemory(tag);}
        }
        finally{CryptographicOperations.ZeroMemory(transcript);}
    }
    internal static (int Kind,int Length) Header(byte[] head,int seq)
    {
        Require(head.Length==8&&head.AsSpan(0,3).SequenceEqual("CL\x01"u8)&&
            head[3] is >=1 and <=3&&U16(head,4)==seq,"Invalid CL/1 header");
        int len=U16(head,6);Require(len is >=1 and <=MaximumBody,"Invalid CL/1 record size");
        return (head[3],len);
    }
    internal static byte[] Body(byte[] head,byte[] tail,int length)
    {
        Require(tail.Length==length+4,"Truncated CL/1 record");
        var data=new byte[8+length];head.CopyTo(data,0);tail.AsSpan(0,length).CopyTo(data.AsSpan(8));
        Require(BinaryDiscovery.Crc32(data)==U32(tail,length),"CL/1 CRC mismatch");return tail[..length];
    }
    private static readonly string[] Phases=["","INITIAL_VIN","INVENTORY","PERIODIC_VIN","SAMPLE","FINAL_VIN"];
    private static readonly string[] States=["","PASSED","RAW","NEGATIVE","TIMEOUT","FAILED"];
    private static readonly string?[] Stops=[null,"INVALID_SCOPE","BUDGET_EXHAUSTED","CONTEXT_CHANGED",
        "TRANSPORT_ERROR","VIN_UNVERIFIED","VIN_MISMATCH","SELECTED_PID_NOT_VERIFIED",
        "SELECTED_RAW_PID_NO_RESPONSE","SINK_STOPPED","INVALID_SEQUENCE","EVIDENCE_LIMIT"];
    private sealed record Event(int Phase,int State,bool Accepted,int Cycle,int Pid,long Start,long End,
        int Timeout,long ExchangeMs,byte[] Request,byte[] Response)
    {
        internal static Event Decode(byte[] data)
        {
            Require(data.Length>=ObservationSize,"Truncated CL/1 observation");
            int phase=data[0],state=data[1],length=U16(data,18),timeout=U16(data,16);
            long start=U32(data,8),end=U32(data,12);
            Require(phase is >=1 and <=5&&state is >=1 and <=5&&data[2]<=1&&data[3]==0&&data[7]==0&&
                start<=end&&end<=604000&&timeout is >=1 and <=4000&&length<=4095&&
                data.Length==ObservationSize+length&&(state<=3)==(length>0)&&(data[2]==1||state==5),
                "Malformed CL/1 observation");
            return new(phase,state,data[2]==1,U16(data,4),data[6],start,end,timeout,U32(data,20),
                data[24..26],data[26..]);
        }
        internal Observation View()=>new(Phases[Phase],States[State],Accepted,Phase==4?Cycle:null,Phase==4?Pid:null,
            Start,End,End-Start,ExchangeMs,Timeout,Convert.ToHexString(Request),
            Response.Length==0?null:Convert.ToHexString(Response));
        internal int Shape()
        {
            if(Response.Length==0)return State is 4 or 5?State:0;
            if(Response[0]==0x7f)return Response.Length==3&&Response[1]==Request[0]&&Response[2] is not (0 or 0x78)?3:0;
            if(Request.AsSpan().SequenceEqual(new byte[]{9,2}))
                return Response.Length==20&&Response.AsSpan(0,3).SequenceEqual(new byte[]{0x49,2,1})&&
                    ValidVin(Encoding.ASCII.GetString(Response.AsSpan(3)))?1:0;
            if(Request[0]!=1||Response.Length<3||Response[0]!=0x41||Response[1]!=Request[1])return 0;
            return Request[1]%32==0?
                Response.Length==6&&(Request[1]!=224||(Response[^1]&1)==0)?1:0:2;
        }
    }

    private sealed class Replay(Profile profile)
    {
        private int stage=1,stop,steps,samples,raw,negative,timeouts,rawBytes,cycles,basePid,index;
        private long duration,lastIdentity;
        private long? cycleStart;
        private bool initial,final,inventoryComplete,completed,earlyIdentity;
        private readonly SortedSet<int> advertised=[];
        private readonly int[] rawPerPid=new int[32];
        private readonly List<Sample> rows=[];
        private readonly List<Observation> observations=[];
        private void Abort(int why,long now){stop=why;stage=7;duration=now;}
        private int Phase(long now)
        {
            if(stage==7||now>=profile.BudgetMs)return 7;
            if(stage==4&&index==0)
            {
                if(cycleStart is long start&&now-start<profile.IntervalMs)return 6;
                if(now-lastIdentity>=30000)return 3;
            }
            return stage;
        }
        private byte[] Request(int phase)=>phase switch {
            1 or 3 or 5=>[9,2],
            2=>[1,(byte)basePid],
            4=>[1,(byte)profile.Pids[index]],
            _=>throw new InvalidDataException("CL/1 request outside sequence")
        };
        internal void Observe(Event e)
        {
            Require(e.Start>=duration&&stage!=7&&steps<MaximumSteps,"Overlapping or post-terminal CL/1 observation");
            int phase=Phase(e.Start);
            if(e.Phase==3&&phase==4&&index==0&&!earlyIdentity)phase=3;
            Require(phase==e.Phase&&phase is >=1 and <=5,"Unexpected CL/1 phase");
            var req=Request(phase);
            Require(e.Request.AsSpan().SequenceEqual(req)&&e.Timeout<=profile.BudgetMs-e.Start&&
                e.Cycle==(phase==4?cycles+1:0)&&e.Pid==(phase==4?req[1]:0),"CL/1 request outside approved scope");
            if(phase==4&&index==0)cycleStart=e.Start;
            steps++;duration=e.End;observations.Add(e.View());
            if(e.End>=profile.BudgetMs){Require(!e.Accepted,"Late response accepted");Abort(2,e.End);return;}
            if(e.End-e.Start>e.Timeout||e.ExchangeMs>e.Timeout)
            {Require(!e.Accepted,"Exchange exceeded deadline");Abort(4,e.End);return;}
            if(!e.Accepted){Require(e.State==5&&e.Response.Length==0,"Rejected event carries data");Abort(4,e.End);return;}
            Require(e.State is >=1 and <=4&&e.Shape()==e.State,"CL/1 uncorrelated response");
            rawBytes+=e.Response.Length;Require(rawBytes<=MaximumRaw,"CL/1 raw evidence limit");
            if(phase is 1 or 3 or 5)
            {
                if(e.State!=1){Abort(5,e.End);return;}
                if(!e.Response.AsSpan(3).SequenceEqual(Encoding.ASCII.GetBytes(profile.Vin))){Abort(6,e.End);return;}
                lastIdentity=e.End;
                if(phase==1){initial=true;stage=2;}
                else if(phase==3){earlyIdentity=true;stage=4;}
                else{
                    final=true;stage=7;
                    if(rawPerPid.Take(profile.Pids.Length).Any(x=>x==0))stop=8;
                    else completed=true;
                }
                return;
            }
            if(phase==2)
            {
                if(e.State!=1){Abort(7,e.End);return;}
                for(int bit=0;bit<31;bit++)if((e.Response[2+bit/8]&(0x80>>(bit%8)))!=0)
                    advertised.Add(basePid+bit+1);
                bool more=(e.Response[5]&1)!=0;inventoryComplete=!more;
                if(profile.Pids.All(advertised.Contains))stage=4;
                else if(!more||basePid==224)Abort(7,e.End);
                else basePid+=32;
                return;
            }
            Require(phase==4&&e.State is 2 or 3 or 4,"Invalid CL/1 sample state");
            samples++;
            if(e.State==2){raw++;rawPerPid[index]++;}
            else if(e.State==3)negative++;else timeouts++;
            rows.Add(new(e.Cycle,e.Pid,e.End,States[e.State],e.Response.Length==0?null:Convert.ToHexString(e.Response)));
            if(++index==profile.Pids.Length)
            {
                index=0;earlyIdentity=false;cycles++;
                if(cycles==profile.Cycles)stage=5;
            }
        }
        private byte[] EncodeSummary()
        {
            var data=new byte[FinalSize];
            data[0]=(byte)stop;data[1]=(byte)((initial?1:0)|(final?2:0)|(inventoryComplete?4:0)|(completed?8:0));
            P16(data,2,cycles);P32(data,4,steps);P32(data,8,samples);P32(data,12,raw);
            P32(data,16,negative);P32(data,20,timeouts);P32(data,24,duration);P32(data,28,rawBytes);
            foreach(int pid in advertised)data[32+pid/8]|=(byte)(1<<(pid%8));
            for(int i=0;i<32;i++)P16(data,64+2*i,rawPerPid[i]);
            return data;
        }
        internal Terminal Finish(byte[] data)
        {
            Require(data.Length==FinalSize&&data[0]<Stops.Length&&data[0] is not (1 or 9 or 10 or 11)&&
                (data[1]&~15)==0,"Malformed CL/1 terminal");
            long end=U32(data,24);Require(end>=duration&&end<=604000,"Invalid terminal duration");
            if(stage!=7)
            {
                Require(data[0] is 2 or 3&&(data[0]!=2||profile.BudgetMs-end<=1),"Unsupported terminal state");
                Abort(data[0],end);
            }
            Require(data.AsSpan().SequenceEqual(EncodeSummary()),"CL/1 terminal counters differ from replay");
            return new(Stops[stop],completed,initial,final,inventoryComplete,cycles,steps,samples,raw,
                negative,timeouts,duration,rawBytes,advertised.ToArray(),
                profile.Pids.Select((pid,i)=>new PidCount(pid,rawPerPid[i])).ToArray());
        }
        internal Result Build()=>new("camarodiag-j1979-live-inspection-v1",true,true,profile.Vin,
            new(){["kind"]="can",["tx"]=profile.Tx,["rx"]=profile.Rx},completed,final,
            rows.ToArray(),observations.ToArray());
    }

    internal static Report Decode(Stream stream,Profile? expected=null)
    {
        Profile? profile=null;Replay? replay=null;Terminal? summary=null;int received=0,total=0;
        string? error=null;
        try
        {
            for(int seq=0;seq<MaximumSteps+2;seq++)
            {
                var head=new byte[8];stream.ReadExactly(head);
                var (kind,length)=Header(head,seq);
                total+=8+length+4;Require(total<=MaximumCapture,"CL/1 exceeds capture size limit");
                var tail=new byte[length+4];stream.ReadExactly(tail);var body=Body(head,tail,length);
                if(profile is null)
                {
                    Require(kind==1,"Missing CL/1 plan");profile=Profile.Decode(body);
                    Require(expected is null||body.AsSpan().SequenceEqual(expected.Encode()),"CL/1 scope differs from CU/3");
                    replay=new Replay(profile);
                }
                else if(kind==2){received++;replay!.Observe(Event.Decode(body));}
                else if(kind==3)
                {
                    summary=replay!.Finish(body);Require(stream.ReadByte()==-1,"Trailing CL/1 evidence");break;
                }
                else throw new InvalidDataException("Duplicate CL/1 plan");
            }
            Require(profile is not null&&summary is not null,"Incomplete CL/1 capture");
        }
        catch(Exception e) when(e is InvalidDataException or IOException or ArgumentException or OverflowException)
        {error=e.Message;summary=null;}
        return new("camarodiag-functional-j1979-native-live-v1","SAE-J1979","OFFLINE_CL1",profile?.View(),
            error is null,false,false,false,false,false,received,summary,error is null?replay!.Build():null,error);
    }
    internal static string EncodeReport(Report report,string? digest=null,string? name=null,
        IReadOnlyDictionary<string,object?>? metadata=null)
    {
        var json=new Dictionary<string,object?>{
            ["schema"]=report.Schema,["protocol"]=report.Protocol,["source"]=report.Source,["profile"]=report.Profile,
            ["capture_integrity_verified"]=report.CaptureIntegrityVerified,
            ["device_credential_authenticated"]=report.DeviceCredentialAuthenticated,
            ["hardware_provenance_verified"]=false,["evidence_authenticated"]=false,["physical_vehicle_verified"]=false,
            ["permits_programming"]=false,["observations_received"]=report.ObservationsReceived,["summary"]=report.Summary,
            ["result"]=report.Result,["error"]=report.Error
        };
        if(report.Source=="LIVE_USB_CU3"){json["client_proof_sent"]=report.ClientProofSent;json["cancelled"]=report.Cancelled;}
        if(report.HostElapsedMs.HasValue)json["host_elapsed_ms"]=report.HostElapsedMs.Value;
        if(digest is not null)json["capture_sha256"]=digest;
        if(name is not null)json["raw_capture_name"]=name;
        if(metadata is not null)
        {
            foreach(var item in metadata)
            {
                Require(item.Key is "version" or "declared_connector" or "declared_ecu" or "connection_generation" or
                    "started_at_utc" or "finished_at_utc" or "observed_vin","Unsupported native report metadata");
                Require(json.TryAdd(item.Key,item.Value),"Duplicate native report metadata");
            }
        }
        return JsonSerializer.Serialize(json,new JsonSerializerOptions{WriteIndented=true,PropertyNamingPolicy=JsonNamingPolicy.SnakeCaseLower});
    }
}
