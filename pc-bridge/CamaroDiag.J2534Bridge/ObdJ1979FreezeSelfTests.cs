using System.Text;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979FreezeSelfTests
{
    private const string Vin = "1G1FA1E30D1234567";
    private static byte[] VinReply(string vin = Vin) =>
        new byte[]{0x49,2,1}.Concat(Encoding.ASCII.GetBytes(vin)).ToArray();

    private static byte[] Reply(byte[] request)
    {
        if (request.SequenceEqual(new byte[]{9,2})) return VinReply();
        var payload = request[1] switch
        {
            0 => new byte[]{0x40,0x18,0,0},
            2 => new byte[]{3,1},
            12 => new byte[]{0x1f,0x40},
            13 => new byte[]{80},
            _ => throw new InvalidOperationException("Unexpected fixture request.")
        };
        return new byte[]{0x42,request[1],request[2]}.Concat(payload).ToArray();
    }

    public static int Run()
    {
        int checks = 0;
        void Assert(bool value,string label)
        {
            checks++;
            if (!value) throw new InvalidOperationException("J1979 freeze self-test: " + label);
        }
        void Reject(Action action,string label)
        {
            try { action(); }
            catch (ArgumentException) { checks++; return; }
            throw new InvalidOperationException("J1979 freeze self-test accepted: " + label);
        }
        var plan = new ObdJ1979FreezeCampaign.Plan(Vin,[0,1],[12,13,42],60000);
        List<string> requests = [];
        var result = ObdJ1979FreezeCampaign.Execute(plan, req =>
        {
            requests.Add(Convert.ToHexString(req));
            return new("RECEIVED",Reply(req),5,true);
        },() => requests.Count*5);
        Assert(result.Completed && result.IdentityRechecked && result.AllFramesStable,"complete explicit frames");
        Assert(result.Frames.Length==2 && result.Frames.All(frame=>frame.Status=="COMPLETE"),"per-frame completion");
        Assert(result.Frames.All(frame=>frame.UnadvertisedSelectedPids.SequenceEqual(new[]{42})),"per-frame missing PID");
        Assert(requests.SequenceEqual(new[]{"0902","020000","020200","020C00","020D00","020200",
            "020001","020201","020C01","020D01","020201","0902"}),"exact recipe order");
        Assert(result.Steps.Single(s=>s.RequestHex=="020C00").Status=="PASSED","standard scalar");
        Assert(result.Steps.Single(s=>s.RequestHex=="020C01").Status=="RAW","manufacturer raw");
        Assert(!result.Authenticated && !result.PhysicalVehicleVerified && !result.PermitsProgramming,"no provenance claim");

        var mismatch = ObdJ1979FreezeCampaign.Execute(plan,_ =>
            new("RECEIVED",VinReply("1G1FA1E30D7654321"),5,true));
        Assert(mismatch.StopReason=="VIN_MISMATCH" && mismatch.Steps.Length==1,"VIN mismatch blocks frame acquisition");
        var absent = ObdJ1979FreezeCampaign.Execute(plan,req=>new("RECEIVED",
            req.SequenceEqual(new byte[]{2,2,0}) ? new byte[]{0x42,2,0,0,0} : Reply(req),5,true));
        Assert(absent.Frames[0].Status=="ABSENT" && absent.Frames[0].ReadPids.Length==0,"zero trigger blocks reads");
        int triggerCount=0;
        var changed=ObdJ1979FreezeCampaign.Execute(plan,req=>new("RECEIVED",
            req.SequenceEqual(new byte[]{2,2,0}) && ++triggerCount==2
            ? new byte[]{0x42,2,0,3,2} : Reply(req),5,true));
        Assert(changed.Frames[0].Status=="CHANGED" && !changed.AllFramesStable,"trigger change prevents stable claim");
        var wrong = ObdJ1979FreezeCampaign.Classify([2,13,1],new("RECEIVED",[0x42,13,0,80],5,true));
        Assert(wrong.Status=="FAILED" && wrong.Evidence is null,"wrong frame echo rejected");
        var budget=ObdJ1979FreezeCampaign.Execute(plan,_=>new("BUDGET",null,0,false));
        Assert(budget.StopReason=="BUDGET_EXHAUSTED" && !budget.Steps[0].TransmissionAttempted,"pre-TX budget exhaustion");
        var cancelled=ObdJ1979FreezeCampaign.Run(plan,(_,_)=>throw new OperationCanceledException(),()=>{});
        Assert(cancelled.StopReason=="CANCELLED","cancel without retry");
        foreach (var request in new byte[][]{[4],[8,0],[0x22,0xf1,0x90],[2,13,2]})
            Assert(!ObdJ1979FreezeCampaign.Allows(request,plan.Frames),"deny outside explicit read scope");
        Reject(()=> (plan with { Frames=[0,0] }).Validate(),"duplicate frame");
        Reject(()=> (plan with { SelectedPids=[2] }).Validate(),"trigger as data PID");
        Reject(()=> (plan with { SelectedPids=[32] }).Validate(),"bitmap as data PID");
        string[] args=[ObdJ1979FreezeCli.Command,"--adapter-index","0","--protocol","33","--tx","7E0",
            "--rx","7E8","--vin",Vin,"--frame","00","--frame","01","--pid","0C",
            "--confirm-physical-channel","--output","freeze-test.json"];
        var parsed=ObdJ1979FreezeCli.Parse(args);
        Assert(parsed.Plan.Frames.SequenceEqual(new[]{0,1}) && parsed.Plan.SelectedPids.SequenceEqual(new[]{12}),"CLI explicit scope");
        Reject(()=>ObdJ1979FreezeCli.Parse(args.Where(a=>a!="--confirm-physical-channel").ToArray()),"missing confirmation");
        Reject(()=>ObdJ1979FreezeCli.Parse(args.Concat(new[]{"--frame","00"}).ToArray()),"duplicate CLI frame");
        var report=new ObdJ1979FreezeCli.Report("camarodiag-functional-j1979-freeze-v1","SAE-J1979",
            "WINDOWS_J2534_ISOTP","test","fixture",33,"7E0","7E8",true,
            DateTimeOffset.UnixEpoch,DateTimeOffset.UnixEpoch,null,result);
        using var json=JsonDocument.Parse(ObdJ1979FreezeCli.Encode(report));
        Assert(json.RootElement.GetProperty("execution_completed").GetBoolean(),"portable completion");
        Assert(json.RootElement.GetProperty("result").GetProperty("plan").GetProperty("frames").GetArrayLength()==2,
            "portable integer array");
        return checks;
    }
}
