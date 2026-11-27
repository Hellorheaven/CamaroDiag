using System.Diagnostics;
using System.Globalization;
using CamaroDiagConnectors.Windows;

namespace CamaroDiag.J2534Bridge;

/// <summary>Read-only, physical ECU Mode 01 numeric recording. One
/// timestamp per exchange, no inferred simultaneous or cached samples.</summary>
internal static class ObdJ1979LiveRecorder
{
    internal sealed record Plan(byte[] Pids,string ExpectedVin,int Cycles=30,int IntervalMs=1000,int BudgetMs=180000)
    {
        internal Plan Validate(){
            if(Pids is null||Pids.Length is <1 or >32||Pids.Distinct().Count()!=Pids.Length||
                Pids.Any(x=>ObdJ1979PidValues.DataLength(x) is null)||
                !System.Text.RegularExpressions.Regex.IsMatch(ExpectedVin??"",@"\A[A-HJ-NPR-Z0-9]{17}\z")||
                Cycles is <1 or >1000||Cycles*Pids.Length>10000||IntervalMs is <100 or >60000||
                BudgetMs is <1 or >600000)throw new ArgumentException("Invalid selected PID/VIN/time plan.");
            return this with {Pids=Pids.ToArray()};
        }
    }
    internal sealed record Observation(string Phase,int? Cycle,byte? Pid,string RequestHex,string? ResponseHex,
        long StartedElapsedMs,long ElapsedMs,long DurationMs,string Status,string? Detail,
        ObdJ1979PidValues.Value? Measurement,J1979CampaignSemantics.Observation? ApplicationEvidence);
    internal sealed record Summary(byte Pid,string? Name,string? Unit,int Attempted,
        IReadOnlyDictionary<string,int> StatusCounts,double? Minimum,double? Maximum,double? Mean,
        double? LastVerifiedValue,long? LastVerifiedElapsedMs);
    internal sealed record Result(string DeclaredVin,string? ObservedVin,byte[] SelectedPids,
        IReadOnlyList<byte> AdvertisedPids,IReadOnlyList<ObdJ1979PidSupport.Page> InventoryPages,
        bool InventoryComplete,int RequestedCycles,int CompletedCycles,int IntervalMs,int BudgetMs,
        IReadOnlyList<Observation> Admission,IReadOnlyList<Observation> Observations,
        IReadOnlyList<Summary> Summary,bool Completed,string? StopReason,long DurationMs)
    {
        public string VinMatch=>ObservedVin is null?"UNVERIFIED":ObservedVin==DeclaredVin?"MATCH":"MISMATCH";
        public IReadOnlyList<byte> UnverifiedSelectedPids=>SelectedPids.Except(AdvertisedPids).ToArray();
        public bool Cancelled=>StopReason=="CANCELLED";
        public bool WholeVehicleCoverageVerified=>false;
        public bool PhysicalVehicleVerified=>false;
        public string[] NotExecuted=>["MODE_04_CLEAR","MODE_08_CONTROL"];
    }

    private sealed class Accumulator(byte pid)
    {
        private readonly Dictionary<string,int> counts=new(StringComparer.Ordinal);
        private string? name,unit;
        private double? minimum,maximum,mean,last;
        private long? lastElapsed;
        internal void Add(Observation observation){
            counts[observation.Status]=counts.GetValueOrDefault(observation.Status)+1;
            if(observation.Status!="PASSED"||observation.Measurement is not {} value)return;
            if(!double.IsFinite(value.Number))throw new InvalidDataException("Nonfinite measurement.");
            name=value.Name;unit=value.Unit;
            minimum=minimum.HasValue?Math.Min(minimum.Value,value.Number):value.Number;
            maximum=maximum.HasValue?Math.Max(maximum.Value,value.Number):value.Number;
            mean=mean.HasValue?mean.Value+(value.Number-mean.Value)/counts["PASSED"]:value.Number;
            last=value.Number;lastElapsed=observation.ElapsedMs;
        }
        internal Summary Report()=>new(pid,name,unit,counts.Values.Sum(),
            new Dictionary<string,int>(counts),minimum,maximum,mean,last,lastElapsed);
    }

    internal static Result Run(Func<byte[],int,byte[]> exchange,Plan requested,Action requireActive,
        Action<Observation>? onObservation=null,Func<long>? clock=null,Action<int>? delay=null)
    {
        var plan=requested.Validate();
        var watch=Stopwatch.StartNew();
        clock??=()=>watch.ElapsedMilliseconds;
        delay??=Thread.Sleep;
        long started=clock();
        long Elapsed()=>Math.Max(0,clock()-started);
        long Remaining()=>plan.BudgetMs-Elapsed();
        var admission=new List<Observation>();
        var observations=new List<Observation>();
        var stats=plan.Pids.ToDictionary(x=>x,x=>new Accumulator(x));
        var pages=new List<ObdJ1979PidSupport.Page>();
        var advertised=new SortedSet<byte>();
        string? vin=null,stop=null;
        long? lastIdentity=null;
        int completedCycles=0;
        bool inventoryComplete=false;
        bool Active(){
            if(stop is not null)return false;
            requireActive();
            if(Remaining()<=0){stop="BUDGET_EXHAUSTED";return false;}
            return true;
        }
        Observation? Query(byte[] request,string phase,int? cycle=null,byte? pid=null){
            if(!Active())return null;
            if(!(request.SequenceEqual(new byte[]{9,2})||request.Length==2&&request[0]==1))
                throw new InvalidOperationException("Read-only live recorder.");
            var begun=Elapsed();
            byte[]? raw=null;
            string status="NOT_TESTED";
            string? detail=null;
            ObdJ1979PidValues.Value? value=null;
            J1979CampaignSemantics.Observation? evidence=null;
            try{
                raw=exchange(request,(int)Math.Min(2500,Math.Max(1,Remaining())));
                requireActive();
                if(Remaining()<=0){stop="BUDGET_EXHAUSTED";detail="LATE_RESPONSE";}
                else{
                    evidence=J1979CampaignSemantics.DecodeObservation(request,raw);
                    if(evidence is null)throw new InvalidDataException("Uncorrelated response.");
                    status=evidence.Shape;
                    if(status=="NEGATIVE"){
                        var nrc=ObdJ1979Transaction.Decode(request,raw).NegativeResponseCode;
                        detail=$"NRC_{nrc:X2}";
                    }else if(phase=="SAMPLE"&&status=="PASSED"){
                        value=ObdJ1979PidValues.Decode(request,raw);
                        if(value is null||value.Pid!=pid||value.Mode!=1)throw new InvalidDataException("Uncorrelated measurement.");
                    }
                }
            }catch(OperationCanceledException){stop=detail="CANCELLED";status="NOT_TESTED";evidence=null;value=null;}
            catch(TimeoutException){status="TIMEOUT";detail="TIMEOUT";if(Remaining()<=0)stop="BUDGET_EXHAUSTED";}
            catch(InvalidDataException){status="FAILED";stop=detail="INVALID_APPLICATION_RESPONSE";evidence=null;value=null;}
            catch(Exception e) when(e is IOException or ArgumentException or InvalidOperationException){
                status="FAILED";stop="TRANSPORT_OR_CONTEXT_ERROR";detail=e.GetType().Name;evidence=null;value=null;
            }
            var elapsed=Elapsed();
            var row=new Observation(phase,cycle,pid,Convert.ToHexString(request),
                raw is null?null:Convert.ToHexString(raw),begun,elapsed,elapsed-begun,status,detail,value,evidence);
            if(phase=="SAMPLE"){
                observations.Add(row);
                stats[pid!.Value].Add(row);
                onObservation?.Invoke(row);
            }else admission.Add(row);
            return row;
        }
        bool Identity(string phase){
            var row=Query([9,2],phase);
            if(row is null||stop is not null)return false;
            string? observed=null;
            if(row.Status=="PASSED"&&row.ResponseHex is not null)
                observed=ObdJ1979Vin.DecodeIsoTp(Convert.FromHexString(row.ResponseHex));
            if(phase=="INITIAL_VIN")vin=observed;
            if(observed!=plan.ExpectedVin){
                stop=observed is null?"VIN_UNVERIFIED":"VIN_MISMATCH";return false;
            }
            lastIdentity=Elapsed();
            return true;
        }
        void Inventory(){
            for(int basePid=0;basePid<=0xE0;basePid+=32){
                var req=new byte[]{1,(byte)basePid};
                var row=Query(req,"INVENTORY");
                if(row is null||stop is not null)return;
                if(row.Status!="PASSED"||row.ResponseHex is null)break;
                var page=ObdJ1979PidSupport.Decode(req,Convert.FromHexString(row.ResponseHex));
                pages.Add(page);
                advertised.UnionWith(page.SupportedPids.Where(x=>x%32!=0));
                if(!page.HasNextPage){inventoryComplete=true;break;}
                if(plan.Pids.All(advertised.Contains))break;
            }
            if(!plan.Pids.All(advertised.Contains))stop="SELECTED_PID_NOT_VERIFIED";
        }
        bool Pause(long target){
            while(Active()){
                long left=target-Elapsed();
                if(left<=0)return true;
                delay((int)Math.Min(200,Math.Min(left,Remaining())));
            }
            return false;
        }
        try{
            if(Identity("INITIAL_VIN"))Inventory();
            if(stop is null){
                long next=Elapsed();
                for(int cycle=1;cycle<=plan.Cycles;cycle++){
                    if(!Pause(next))break;
                    if(lastIdentity.HasValue&&Elapsed()-lastIdentity.Value>=30000&&!Identity("PERIODIC_VIN"))break;
                    long actual=Elapsed();
                    bool done=true;
                    foreach(var pid in plan.Pids){
                        var row=Query([1,pid],"SAMPLE",cycle,pid);
                        if(row is null||stop is not null){done=false;break;}
                    }
                    if(!done)break;
                    completedCycles++;
                    next=Math.Max(actual+plan.IntervalMs,Elapsed());
                }
            }
            if(stop is null&&completedCycles==plan.Cycles)Identity("FINAL_VIN");
            if(stop is null&&!observations.Any(x=>x.Status=="PASSED"))stop="NO_VERIFIED_MEASUREMENT";
        }catch(OperationCanceledException){stop="CANCELLED";}
        catch(Exception e) when(e is IOException or InvalidDataException or ArgumentException or InvalidOperationException){
            stop="CONTEXT_OR_SINK_ERROR";
        }
        return new(plan.ExpectedVin,vin,plan.Pids,advertised.ToArray(),pages.ToArray(),inventoryComplete,
            plan.Cycles,completedCycles,plan.IntervalMs,plan.BudgetMs,admission.ToArray(),observations.ToArray(),
            plan.Pids.Select(pid=>stats[pid].Report()).ToArray(),stop is null&&completedCycles==plan.Cycles,stop,Elapsed());
    }

    internal static IEnumerable<string> Csv(Result report)
    {
        static string Escape(string? field)=>field is null?"":"\""+field.Replace("\"","\"\"")+"\"";
        static string Number(double? value)=>value?.ToString("R",CultureInfo.InvariantCulture)??"";
        yield return "cycle,pid_hex,started_elapsed_ms,elapsed_ms,duration_ms,status,value,unit,request_hex,response_hex,detail";
        foreach(var row in report.Observations)
            yield return string.Join(",",row.Cycle?.ToString(CultureInfo.InvariantCulture),row.Pid?.ToString("X2"),
                row.StartedElapsedMs.ToString(CultureInfo.InvariantCulture),row.ElapsedMs.ToString(CultureInfo.InvariantCulture),
                row.DurationMs.ToString(CultureInfo.InvariantCulture),Escape(row.Status),Number(row.Measurement?.Number),
                Escape(row.Measurement?.Unit),Escape(row.RequestHex),Escape(row.ResponseHex),Escape(row.Detail));
    }
}
