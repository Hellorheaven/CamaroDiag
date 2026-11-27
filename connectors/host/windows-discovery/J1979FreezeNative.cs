using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using CamaroDiag.J2534Bridge;

namespace CamaroDiagConnectors.Windows;

/// <summary>CU/2 authentication and independent CF/1 evidence replay.
/// No fallback to discovery, CU/1, or a different physical responder.</summary>
internal static class J1979FreezeNative
{
    internal const int PlanSize=104, HelloSize=140, ChallengeSize=68, ProofSize=36;
    internal const int MaximumBody=4108;
    internal const long MaximumCapture=(ObdJ1979FreezeCampaign.MaximumSteps+2L)*(MaximumBody+12);
    internal sealed record Profile(uint Tx,uint Rx,bool Extended,ObdJ1979FreezeCampaign.Plan Plan)
    {
        internal Profile Validate()
        {
            if(!J1979UsbAuth.PhysicalPair(Tx,Rx,Extended))throw new ArgumentException("Invalid physical channel");
            return this with {Plan=Plan.Validate()};
        }
        internal byte[] Encode()
        {
            var p=Validate();
            var wire=new byte[PlanSize];
            BinaryPrimitives.WriteUInt32LittleEndian(wire,p.Tx);
            BinaryPrimitives.WriteUInt32LittleEndian(wire.AsSpan(4),p.Rx);
            wire[8]=p.Extended?(byte)1:(byte)0;
            BinaryPrimitives.WriteInt32LittleEndian(wire.AsSpan(9),p.Plan.BudgetMs);
            Encoding.ASCII.GetBytes(p.Plan.ExpectedVin).CopyTo(wire,13);
            wire[30]=(byte)p.Plan.Frames.Length;
            for(int i=0;i<p.Plan.Frames.Length;i++)wire[31+i]=(byte)p.Plan.Frames[i];
            wire[39]=(byte)p.Plan.SelectedPids.Length;
            for(int i=0;i<p.Plan.SelectedPids.Length;i++)wire[40+i]=(byte)p.Plan.SelectedPids[i];
            return wire;
        }
        internal static Profile Decode(ReadOnlySpan<byte> wire)
        {
            if(wire.Length!=PlanSize||wire[8]>1||wire[30] is <1 or >8||wire[39]>64)
                throw new InvalidDataException("Invalid CF/1 profile");
            var profile=new Profile(BinaryPrimitives.ReadUInt32LittleEndian(wire),
                BinaryPrimitives.ReadUInt32LittleEndian(wire[4..]),wire[8]==1,
                new(Encoding.ASCII.GetString(wire.Slice(13,17)),
                    wire.Slice(31,wire[30]).ToArray().Select(x=>(int)x).ToArray(),
                    wire.Slice(40,wire[39]).ToArray().Select(x=>(int)x).ToArray(),
                    BinaryPrimitives.ReadInt32LittleEndian(wire[9..])));
            if(!wire.SequenceEqual(profile.Encode()))throw new InvalidDataException("Noncanonical CF/1 profile");
            return profile;
        }
    }
    internal sealed record Terminal(string? StopReason,int VisitedFrames,bool Completed,
        bool IdentityRechecked,bool AllFramesStable,int Steps,long DurationMs,string[] FrameStates);
    internal sealed record Observation(byte[] Request,ObdJ1979FreezeCampaign.Read Read);
    internal sealed record Report(string Schema,string Protocol,string Source,
        bool DeviceCredentialAuthenticated,bool ClientProofSent,bool CaptureIntegrityVerified,
        bool Cancelled,int ObservationsReceived,string? Error,Profile? Profile,
        Terminal? Summary,ObdJ1979FreezeCampaign.Result? Result)
    {
        public bool HardwareProvenanceVerified=>false;
        public bool EvidenceAuthenticated=>false;
        public bool PhysicalVehicleVerified=>false;
        public bool PermitsProgramming=>false;
    }

    private static bool Nonzero(ReadOnlySpan<byte> bytes)
    {
        foreach(byte b in bytes)if(b!=0)return true;
        return false;
    }
    internal static byte[] Hello(Profile profile,byte[]? nonce=null)
    {
        nonce??=RandomNumberGenerator.GetBytes(32);
        if(nonce.Length!=32||!Nonzero(nonce))throw new ArgumentException("Invalid CU/2 nonce");
        byte[] wire=new byte[HelloSize];
        "CU\x02\x01"u8.CopyTo(wire);profile.Encode().CopyTo(wire,4);nonce.CopyTo(wire,108);
        return wire;
    }
    internal static byte[] Proof(ReadOnlySpan<byte> request,ReadOnlySpan<byte> challenge,ReadOnlySpan<byte> key)
    {
        if(request.Length!=HelloSize||challenge.Length!=ChallengeSize||key.Length!=32||
           !request[..4].SequenceEqual("CU\x02\x01"u8)||!challenge[..4].SequenceEqual("CU\x02\x02"u8)||
           !Nonzero(request[108..])||!Nonzero(challenge[4..36]))
            throw new InvalidDataException("Invalid CU/2 transcript");
        _=Profile.Decode(request.Slice(4,PlanSize));
        Span<byte> transcript=stackalloc byte[169];
        request[4..].CopyTo(transcript[1..]);challenge[4..36].CopyTo(transcript[137..]);
        try
        {
            transcript[0]=(byte)'F';
            byte[] server=HMACSHA256.HashData(key,transcript);
            try
            {
                if(!CryptographicOperations.FixedTimeEquals(server,challenge[36..]))
                    throw new InvalidDataException("CU/2 server proof rejected");
            }
            finally{CryptographicOperations.ZeroMemory(server);}
            transcript[0]=(byte)'f';
            var result=new byte[ProofSize];"CU\x02\x03"u8.CopyTo(result);
            byte[] tag=HMACSHA256.HashData(key,transcript);
            try{tag.CopyTo(result,4);return result;}
            finally{CryptographicOperations.ZeroMemory(tag);}
        }
        finally{CryptographicOperations.ZeroMemory(transcript);}
    }

    private static readonly string[] Outcomes=["","RECEIVED","TIMEOUT","TRANSPORT_ERROR",
        "BUDGET","CONTEXT_CHANGED","LATE_RESPONSE"];
    private static readonly string?[] Stops=[null,"BUDGET_EXHAUSTED","TRANSPORT_ERROR",
        "CONTEXT_CHANGED","VIN_UNVERIFIED","VIN_MISMATCH","FINAL_VIN_UNVERIFIED","FINAL_VIN_MISMATCH"];
    private static readonly string[] States=["UNAVAILABLE","ABSENT","COMPLETE","PARTIAL","CHANGED"];

    internal static (int Kind,int Length) Header(byte[] head,int sequence)
    {
        if(head.Length!=8||!head.AsSpan(0,3).SequenceEqual("CF\x01"u8)||
           head[3] is <1 or >3||BinaryPrimitives.ReadUInt16LittleEndian(head.AsSpan(4))!=sequence)
            throw new InvalidDataException("Invalid CF/1 header");
        int length=BinaryPrimitives.ReadUInt16LittleEndian(head.AsSpan(6));
        if(length is <1 or >MaximumBody)throw new InvalidDataException("Invalid CF/1 body length");
        return (head[3],length);
    }
    internal static byte[] Body(byte[] head,byte[] tail,int length)
    {
        if(tail.Length!=length+4)throw new InvalidDataException("Truncated CF/1 body");
        byte[] combined=new byte[8+length];
        head.CopyTo(combined,0);tail.AsSpan(0,length).CopyTo(combined.AsSpan(8));
        if(BinaryDiscovery.Crc32(combined)!=BinaryPrimitives.ReadUInt32LittleEndian(tail.AsSpan(length)))
            throw new InvalidDataException("CF/1 CRC mismatch");
        return tail[..length];
    }
    private static Observation DecodeObservation(byte[] body)
    {
        if(body.Length<10||body[0] is <1 or >6||body[1]>1||body[2] is <2 or >3||body[3]!=0)
            throw new InvalidDataException("Invalid CF/1 observation");
        int count=body[2],size=BinaryPrimitives.ReadUInt16LittleEndian(body.AsSpan(8));
        uint duration=BinaryPrimitives.ReadUInt32LittleEndian(body.AsSpan(4));
        if(size>4095||body.Length!=10+count+size||duration>3600000||
           ((body[0] is 1 or 6)!=(size>0))||
           ((body[0] is 1 or 2 or 3 or 6)&&body[1]!=1)||
           ((body[0] is 4 or 5)&&body[1]!=0))
            throw new InvalidDataException("Inconsistent CF/1 observation");
        return new(body[10..(10+count)],new(Outcomes[body[0]],
            size==0?null:body[(10+count)..],duration,body[1]==1));
    }
    private static Terminal DecodeTerminal(byte[] body,Profile p,IReadOnlyList<Observation> observations)
    {
        if(body.Length!=20||body[0]>=Stops.Length||body[1]>p.Plan.Frames.Length||
           (body[2]&~7)!=0||body[3]!=0||
           BinaryPrimitives.ReadUInt32LittleEndian(body.AsSpan(4))!=observations.Count||observations.Count==0)
            throw new InvalidDataException("Malformed CF/1 terminal");
        uint duration=BinaryPrimitives.ReadUInt32LittleEndian(body.AsSpan(8));
        if(duration>3600000||observations.Sum(x=>x.Read.DurationMs)>duration)
            throw new InvalidDataException("Inconsistent CF/1 timing");
        var states=new string[body[1]];
        for(int i=0;i<8;i++)
        {
            if(body[12+i]>=States.Length||(i>=states.Length&&body[12+i]!=0))
                throw new InvalidDataException("Invalid CF/1 frame status");
            if(i<states.Length)states[i]=States[body[12+i]];
        }
        return new(Stops[body[0]],states.Length,(body[2]&1)!=0,(body[2]&2)!=0,
            (body[2]&4)!=0,observations.Count,duration,states);
    }

    internal static Report Decode(Stream input,Profile? expected=null)
    {
        Profile? profile=null;Terminal? terminal=null;
        ObdJ1979FreezeCampaign.Result? result=null;
        var observations=new List<Observation>();
        string? error=null;
        try
        {
            for(int seq=0;seq<ObdJ1979FreezeCampaign.MaximumSteps+2;seq++)
            {
                byte[] head=new byte[8];input.ReadExactly(head);
                var (kind,length)=Header(head,seq);
                byte[] tail=new byte[length+4];input.ReadExactly(tail);
                var body=Body(head,tail,length);
                if(seq==0)
                {
                    if(kind!=1)throw new InvalidDataException("Missing CF/1 plan");
                    profile=Profile.Decode(body);
                    if(expected is not null&&!body.AsSpan().SequenceEqual(expected.Encode()))
                        throw new InvalidDataException("CF/1 profile differs from approved plan");
                    continue;
                }
                if(profile is null)throw new InvalidDataException("Missing CF/1 profile");
                if(kind==2)
                {
                    if(observations.Count>=ObdJ1979FreezeCampaign.MaximumSteps)
                        throw new InvalidDataException("Excessive CF/1 observations");
                    observations.Add(DecodeObservation(body));continue;
                }
                if(kind!=3)throw new InvalidDataException("Duplicate CF/1 plan");
                terminal=DecodeTerminal(body,profile,observations);
                if(input.ReadByte()!=-1)throw new InvalidDataException("Trailing bytes after CF/1 terminal");
                var final=terminal;
                int position=0;
                var replay=ObdJ1979FreezeCampaign.Execute(profile.Plan,request=>
                {
                    if(position>=observations.Count)throw new InvalidDataException("Missing recipe observation");
                    var saved=observations[position++];
                    if(!request.AsSpan().SequenceEqual(saved.Request))
                        throw new InvalidDataException("Unexpected CF/1 request order");
                    return saved.Read;
                },()=>final.DurationMs);
                if(position!=observations.Count||replay.StopReason!=terminal.StopReason||
                   replay.Frames.Length!=terminal.VisitedFrames||replay.Completed!=terminal.Completed||
                   replay.IdentityRechecked!=terminal.IdentityRechecked||replay.AllFramesStable!=terminal.AllFramesStable||
                   !replay.Frames.Select(f=>f.Status).SequenceEqual(terminal.FrameStates))
                    throw new InvalidDataException("CF/1 terminal differs from independent replay");
                result=replay;break;
            }
            if(result is null)throw new InvalidDataException("Incomplete CF/1 capture");
        }
        catch(Exception e) when(e is InvalidDataException or IOException or ArgumentException or InvalidOperationException)
        {error=e.Message;result=null;}
        return new("camarodiag-functional-j1979-native-freeze-v1","SAE-J1979","OFFLINE_CF1",
            false,false,error is null,false,observations.Count,error,profile,terminal,result);
    }

    internal static string EncodeReport(Report report, string? captureSha256=null, string? rawCaptureName=null)
    {
        // Explicit profile representation agrees with the Python CF/1 inspector.
        var p=report.Profile;
        var row=new {
            report.Schema,report.Protocol,report.Source,report.DeviceCredentialAuthenticated,
            report.ClientProofSent,report.CaptureIntegrityVerified,report.Cancelled,
            report.ObservationsReceived,report.Error,
            Profile=p is null?null:new {RequestCanId=p.Tx.ToString("X"),ResponseCanId=p.Rx.ToString("X"),
                p.Extended,p.Plan},
            report.Summary,report.Result,report.HardwareProvenanceVerified,
            report.EvidenceAuthenticated,report.PhysicalVehicleVerified,report.PermitsProgramming,
            CaptureSha256=captureSha256,RawCaptureName=rawCaptureName
        };
        return JsonSerializer.Serialize(row,new JsonSerializerOptions{
            PropertyNamingPolicy=JsonNamingPolicy.SnakeCaseLower,WriteIndented=true});
    }
}
