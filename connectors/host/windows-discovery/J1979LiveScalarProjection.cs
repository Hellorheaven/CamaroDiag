using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace CamaroDiagConnectors.Windows;

/// <summary>
/// A separate, context-free engineering projection of a verified CL/1 raw
/// capture. Never assumes an ECU-specific scale or upper-bank attribution.
/// </summary>
internal static class J1979LiveScalarProjection
{
    internal sealed record Sample(int Cycle,int Pid,long ElapsedMs,string SourceStatus,
        string? ResponseHex,string Interpretation,string? Name,string? Unit,double? Number);
    internal sealed record Channel(int Pid,string? Name,string? Unit,int Requested,int Attempted,
        int Count,int Missing,double? Minimum,double? Maximum,double? Mean,double? LastVerifiedValue,
        long? LastVerifiedElapsedMs,IReadOnlyDictionary<string,int> Outcomes);
    internal sealed record Projection(string Schema,string Vin,uint Tx,uint Rx,bool IdentityRechecked,
        bool SourceCompleted,bool NumericProjectionComplete,IReadOnlyList<Sample> Samples,
        IReadOnlyList<Channel> Channels)
    {
        public bool PhysicalVehicleVerified=>false;
        public bool Authenticated=>false;
        public bool PermitsProgramming=>false;
    }
    private static bool Contextual(int pid)=>pid is 0x0B or 0x10 or 0x44 or 0x4F or 0x50 or
        >=6 and <=9 or >=0x24 and <=0x2B or >=0x34 and <=0x3B or >=0x55 and <=0x58;
    private sealed class Accumulator(int pid,int requested)
    {
        private int attempted,count;
        private string? name,unit;
        private double? min,max,mean,last;
        private long? elapsed;
        private readonly Dictionary<string,int> outcomes=new(StringComparer.Ordinal);
        internal void Add(Sample s)
        {
            attempted++;
            outcomes[s.Interpretation]=outcomes.GetValueOrDefault(s.Interpretation)+1;
            if(s.Number is not double number)return;
            J1979LiveNative.Require(double.IsFinite(number)&&s.Name is not null&&s.Unit is not null&&
                (name is null||name==s.Name&&unit==s.Unit),"Inconsistent engineering channel");
            name=s.Name;unit=s.Unit;count++;
            min=min is double lo?Math.Min(lo,number):number;
            max=max is double hi?Math.Max(hi,number):number;
            mean=mean is double average?average+(number-average)/count:number;
            last=number;elapsed=s.ElapsedMs;
        }
        internal Channel Result()
        {
            J1979LiveNative.Require(count<=attempted&&attempted<=requested,"Invalid sample accounting");
            return new(pid,name,unit,requested,attempted,count,requested-count,min,max,mean,last,elapsed,
                new Dictionary<string,int>(outcomes));
        }
    }
    internal static Projection Project(J1979LiveNative.Report replay,string expectedVin)
    {
        if(!replay.CaptureIntegrityVerified||replay.Cancelled||replay.Profile is not {} profile||
            replay.Summary is not {} summary||replay.Result is not {} result||
            !J1979LiveNative.ValidVin(expectedVin)||profile.ExpectedVin!=expectedVin||
            result.DeclaredVin!=expectedVin||!result.RawOnly||!result.Verified)
            throw new InvalidDataException("Verified CL/1 scope and independent VIN required");
        bool identity=summary.InitialIdentityVerified&&summary.FinalIdentityVerified;
        J1979LiveNative.Require(result.FinalIdentityVerified==summary.FinalIdentityVerified&&
            result.Samples.Length<=J1979LiveNative.MaximumSamples&&profile.SelectedPids.Length is >=1 and <=32&&
            profile.RequestedCycles is >=1 and <=1000,"Invalid CL/1 replay dimensions");
        int[] selected=profile.SelectedPids;
        var stats=selected.ToDictionary(pid=>pid,pid=>new Accumulator(pid,profile.RequestedCycles));
        var samples=new List<Sample>(result.Samples.Length);
        long previous=-1;
        for(int i=0;i<result.Samples.Length;i++)
        {
            var row=result.Samples[i];
            int pid=row.Pid;
            J1979LiveNative.Require(row.Cycle==i/selected.Length+1&&row.Cycle<=profile.RequestedCycles&&
                pid==selected[i%selected.Length]&&row.ElapsedMs>=previous&&row.ElapsedMs<=604000,
                "CL/1 sample order or elapsed time differs");
            previous=row.ElapsedMs;
            string status=row.Status;
            string? response=row.ResponseHex;
            J1979LiveNative.Require(status is "RAW" or "NEGATIVE" or "TIMEOUT","Invalid CL/1 sample status");
            J1979LiveNative.Require((response is null)==(status=="TIMEOUT")&&
                (response is null||response.Length is >=6 and <=8190&&response.Length%2==0&&
                    Regex.IsMatch(response,@"\A[0-9A-F]+\z")),"Invalid CL/1 sample bytes");
            if(status=="RAW")J1979LiveNative.Require(response?.StartsWith($"41{pid:X2}",StringComparison.Ordinal)==true,
                "CL/1 sample response differs");
            if(status=="NEGATIVE")J1979LiveNative.Require(response is {Length:6}&&
                response.StartsWith("7F01",StringComparison.Ordinal)&&response[4..] is not ("00" or "78"),
                "Invalid CL/1 negative response");
            var decoded=identity&&status=="RAW"&&!Contextual(pid)?
                J1979CampaignSemantics.DecodeObservation([1,(byte)pid],Convert.FromHexString(response!))?.Measurement:null;
            string interpretation=status!="RAW"?status:!identity?"IDENTITY_UNVERIFIED":
                Contextual(pid)?"CONTEXT_REQUIRED":decoded is null?"RAW_UNINTERPRETED":"DOCUMENTED_SCALAR";
            var sample=new Sample(row.Cycle,pid,row.ElapsedMs,status,response,interpretation,
                decoded?.Name,decoded?.Unit,decoded?.Number);
            stats[pid].Add(sample);samples.Add(sample);
        }
        var channels=selected.Select(pid=>stats[pid].Result()).ToArray();
        bool complete=summary.Completed;
        return new("camarodiag-j1979-cl1-scalar-projection-v1",expectedVin,
            Convert.ToUInt32(profile.RequestCanId,16),Convert.ToUInt32(profile.ResponseCanId,16),identity,complete,
            complete&&identity&&channels.All(c=>c.Count==c.Requested),samples.AsReadOnly(),channels);
    }
    internal static byte[] Csv(Projection p)
    {
        var output=new StringBuilder("cycle,pid_hex,elapsed_ms,source_status,interpretation,name,number,unit,response_hex\r\n");
        static string Quote(string? s)=>s is null?"":"\""+s.Replace("\"","\"\"")+"\"";
        foreach(var s in p.Samples)
        {
            output.Append(s.Cycle.ToString(CultureInfo.InvariantCulture)).Append(',')
                .Append(s.Pid.ToString("X2",CultureInfo.InvariantCulture)).Append(',')
                .Append(s.ElapsedMs.ToString(CultureInfo.InvariantCulture)).Append(',').Append(s.SourceStatus).Append(',')
                .Append(s.Interpretation).Append(',').Append(Quote(s.Name)).Append(',')
                .Append(s.Number?.ToString("R",CultureInfo.InvariantCulture)).Append(',')
                .Append(Quote(s.Unit)).Append(',').Append(s.ResponseHex).Append("\r\n");
            J1979LiveNative.Require(output.Length<=40*1024*1024,"Projection CSV exceeds byte limit");
        }
        return Encoding.UTF8.GetBytes(output.ToString());
    }
}
