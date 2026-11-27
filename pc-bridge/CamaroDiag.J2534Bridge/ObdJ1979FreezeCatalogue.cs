using System.Text.Json;
using System.Text.RegularExpressions;
using static CamaroDiag.J2534Bridge.ObdJ1979DocumentedCatalogue;
using CamaroDiagConnectors.Windows;

namespace CamaroDiag.J2534Bridge;

/// <summary>Mode 02 interpretation requires its own documented frame/PID scope.
/// No Mode 01 definitions, live traffic, or repair verdicts are admitted.</summary>
internal static class ObdJ1979FreezeCatalogue
{
    internal const string Schema="camarodiag-j1979-freeze-catalogue-v1";
    internal const string Projection="camarodiag-j1979-native-freeze-catalogue-projection-v1";
    internal const int SignalLimit=25000;
    internal sealed record Catalogue(string Edition,string ReferenceName,string ReferenceSha256,
        string Sha256,IReadOnlyDictionary<(int Frame,int Pid),Definition> Definitions);
    internal static Catalogue Parse(byte[] raw,string pin)
    {
        Check(raw.Length is >0 and <=MaxCatalogueBytes,"Catalogue exceeds byte limit");
        using var document=JsonDocument.Parse(raw,new JsonDocumentOptions{MaxDepth=32});
        var root=document.RootElement;
        Unique(root);
        Fields(root,["schema","edition","reference_name","reference_sha256","frames"]);
        Check(Label(root.GetProperty("schema"))==Schema,"Explicit Mode 02 catalogue required");
        var reference=Digest(Label(root.GetProperty("reference_sha256")));
        Check(reference==Digest(pin),"Reference digest differs from pin");
        var seen=new HashSet<int>();
        var definitions=new Dictionary<(int,int),Definition>();
        foreach(var entry in Array(root.GetProperty("frames"),1,8))
        {
            Fields(entry,["frame","definitions"]);
            int frame=Int(entry.GetProperty("frame"),0,255);
            Check(seen.Add(frame),"Duplicate frame definition");
            foreach(var row in Array(entry.GetProperty("definitions"),1,247))
            {
                var definition=ParseDefinition(row);
                Check(definition.Pid!=2&&definition.Width<=4092&&
                    definition.Protocols.SequenceEqual(new[]{"CAN"}),"Invalid native freeze definition");
                Check(definitions.TryAdd((frame,definition.Pid),definition),"Duplicate frame/PID definition");
            }
        }
        return new(Label(root.GetProperty("edition")),Label(root.GetProperty("reference_name")),
            reference,Sha(raw),definitions);
    }

    internal static Dictionary<string,object?> Build(byte[] catalogueBytes,string referenceSha,
        byte[] capture,string expectedVin)
    {
        Check(Regex.IsMatch(expectedVin??"", @"\A[A-HJ-NPR-Z0-9]{17}\z"),"Independent VIN required");
        var catalogue=Parse(catalogueBytes,referenceSha);
        using var stream=new MemoryStream(capture,writable:false);
        var source=J1979FreezeNative.Decode(stream);
        Check(source.CaptureIntegrityVerified&&source.Profile is not null&&source.Result is not null,
            "Original independently replayed CF/1 required");
        var profile=source.Profile!;
        var result=source.Result!;
        Check(profile.Plan.ExpectedVin==expectedVin&&result.Plan.ExpectedVin==expectedVin,
            "CF/1 differs from independently supplied VIN");
        bool identity=result.IdentityRechecked&&result.InitialVin==expectedVin&&result.FinalVin==expectedVin;
        var rows=new List<Dictionary<string,object?>>();
        var frames=new List<Dictionary<string,object?>>();
        var counts=new SortedDictionary<string,int>(StringComparer.Ordinal);
        var consumed=new HashSet<int>();
        int signals=0;
        foreach(var frame in result.Frames)
        {
            bool stable=frame.TriggerStable&&frame.TriggerDtc is not null&&frame.TriggerDtc==frame.FinalTriggerDtc;
            frames.Add(new(){
                ["frame"]=frame.Frame,["source_status"]=frame.Status,["trigger_dtc"]=frame.TriggerDtc,
                ["final_trigger_dtc"]=frame.FinalTriggerDtc,["trigger_stable"]=stable,
                ["inventory_complete"]=frame.InventoryComplete});
            foreach(int stepIndex in frame.MeasurementSteps)
            {
                Check(stepIndex>=0&&stepIndex<result.Steps.Length&&consumed.Add(stepIndex),"Invalid replay measurement index");
                var step=result.Steps[stepIndex];
                byte[] request=Convert.FromHexString(step.RequestHex);
                Check(request.Length==3&&request[0]==2&&request[2]==frame.Frame&&request[1]!=2&&
                    request[1]%32!=0&&frame.AdvertisedPids.Contains((int)request[1]),"Uncorrelated freeze measurement");
                int pid=request[1];
                var row=new Dictionary<string,object?>{
                    ["frame"]=frame.Frame,["pid"]=pid,["step_index"]=stepIndex,["source_status"]=step.Status,
                    ["response_hex"]=step.ResponseHex,["projection_status"]=null,["signals"]=System.Array.Empty<object>()};
                string status;
                if(!identity)status="IDENTITY_NOT_RECHECKED";
                else if(!stable)status="TRIGGER_NOT_STABLE";
                else if(step.Status is not ("RAW" or "PASSED"))status="NO_CORRELATED_RAW_PAYLOAD";
                else
                {
                    byte[] reply=Convert.FromHexString(step.ResponseHex??"");
                    Check(reply.Length>3&&reply[0]==0x42&&reply[1]==pid&&reply[2]==frame.Frame,
                        "CF/1 replay response differs from frame/PID");
                    if(!catalogue.Definitions.TryGetValue((frame.Frame,pid),out var definition))
                        status="NO_DOCUMENTED_DEFINITION";
                    else if(reply.Length!=definition.Width+3)status="DOCUMENTED_WIDTH_MISMATCH";
                    else
                    {
                        signals+=definition.Signals.Length;
                        Check(signals<=SignalLimit,"Projection signal limit");
                        var values=new List<Dictionary<string,object?>>();
                        foreach(var signal in definition.Signals)values.Add(signal.Project(reply.AsSpan(3)));
                        status="PROJECTED";row["reference"]=definition.Reference;row["signals"]=values;
                    }
                }
                row["projection_status"]=status;
                counts[status]=counts.GetValueOrDefault(status)+1;
                rows.Add(row);
                Check(rows.Count<=SignalLimit,"Projection row limit");
            }
        }
        return new(){
            ["schema"]=Projection,["reference_edition"]=catalogue.Edition,["reference_sha256"]=catalogue.ReferenceSha256,
            ["catalogue_sha256"]=catalogue.Sha256,["source_capture_sha256"]=Sha(capture),
            ["expected_vin_independently_supplied"]=true,["declared_vin"]=expectedVin,
            ["channel"]=new Dictionary<string,object>{{"kind","can"},{"tx",profile.Tx},{"rx",profile.Rx},{"extended",profile.Extended}},
            ["source_completed"]=result.Completed,["final_identity_verified"]=identity,["source_replay_verified"]=true,
            ["native_capture_replayed"]=true,["transport_frames_replayed"]=false,["reference_authenticity_verified"]=false,
            ["normative_compliance_verified"]=false,["physical_vehicle_verified"]=false,["authenticated"]=false,
            ["permits_programming"]=false,["diagnostic_verdict"]=null,["frames"]=frames,
            ["unvisited_frames"]=result.UnvisitedFrames,["counts"]=counts,["rows"]=rows};
    }
}
