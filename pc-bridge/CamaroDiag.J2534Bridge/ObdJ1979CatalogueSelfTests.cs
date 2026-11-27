using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979CatalogueSelfTests
{
    private const string Vin="1G1FA1RXXR0123456";
    private static string Catalogue(string digest)=>"""
        {
          "schema":"camarodiag-j1979-pid-catalogue-v1",
          "edition":"synthetic regression only",
          "reference_name":"synthetic reference",
          "reference_sha256":"PIN",
          "definitions":[{
            "pid":241,"payload_bytes":3,"protocols":["CAN","LEGACY"],"reference":"fixture",
            "signals":[{
              "name":"signed sample","unit":"fixture unit","bits":{"offset":8,"width":16,"order":"LE_BYTES"},
              "signed":true,"scale":[1,3],"offset":[1,2],"reference":"fixture section 1",
              "presence":{"bits":{"offset":0,"width":8,"order":"MSB0"},"mask":128,"equals":128},
              "unavailable":[65535],"range":[[-100,1],[100,1]]
            },{
              "name":"state","unit":"state","bits":{"offset":6,"width":2,"order":"MSB0"},
              "signed":false,"scale":[1,1],"offset":[0,1],"reference":"fixture section 2",
              "enumeration":[{"raw":1,"label":"fixture state"}]
            }]
          },{
            "pid":243,"payload_bytes":8,"protocols":["CAN"],"reference":"fixture unsigned 64-bit",
            "signals":[{
              "name":"counter","unit":"fixture unit","bits":{"offset":0,"width":64,"order":"MSB0"},
              "signed":false,"scale":[1,1],"offset":[0,1],"reference":"fixture counter"
            }]
          }]
        }
        """.Replace("PIN",digest,StringComparison.Ordinal);
    private static readonly JsonSerializerOptions Camel=new(){PropertyNamingPolicy=JsonNamingPolicy.CamelCase};
    private static byte[] Original(bool zeroDuration=false)
    {
        long now=0;
        int reads=0;
        var report=ObdJ1979LiveRecorder.Run((request,_)=>{
            if(!zeroDuration)now+=10;
            if(request.SequenceEqual(new byte[]{9,2}))return [0x49,2,1,..Encoding.ASCII.GetBytes(Vin)];
            if(request[0]==1&&request[1]%32==0)
                return request[1]==0xE0?[0x41,0xE0,0,0,0xE0,0]:[0x41,request[1],0,0,0,1];
            return request[1] switch {
                0xF1=>Convert.FromHexString(new[]{"41F181FEFF","41F1010000","41F180FFFF","41F18000"}[reads++]),
                0xF2=>[0x41,0xF2,0],
                0xF3=>Convert.FromHexString("41F3FFFFFFFFFFFFFFFF"),
                _=>throw new InvalidOperationException("Unexpected regression request")
            };
        },new([0xF1,0xF2,0xF3],Vin,4,100,30000,true),()=>{},clock:()=>now,delay:ms=>now+=ms);
        if(!report.Completed)throw new InvalidOperationException("Raw fixture did not complete");
        return JsonSerializer.SerializeToUtf8Bytes(new {
            schema="camarodiag-j1979-live-v1",txCanId="7E0",rxCanId="7E8",profile=33,
            completed=true,physicalVehicleVerified=false,wholeVehicleCoverageVerified=false,result=report
        },Camel);
    }
    internal static int Run()
    {
        int count=0;
        void Check(bool good,string message)
        {
            if(!good)throw new InvalidOperationException("J1979 documented catalogue: "+message);
            count++;
        }
        void Reject(Action action,string message)
        {
            bool rejected=false;
            try{action();}
            catch(Exception e) when(e is InvalidDataException or ArgumentException or FormatException or JsonException or
                KeyNotFoundException or InvalidOperationException or IOException){rejected=true;}
            Check(rejected,message);
        }
        byte[] reference=Encoding.UTF8.GetBytes("synthetic catalogue reference — not SAE data");
        string pin=ObdJ1979DocumentedCatalogue.Sha(reference);
        byte[] cat=Encoding.UTF8.GetBytes(Catalogue(pin));
        byte[] original=Original();
        var source=ObdJ1979CatalogueProjection.ReplayApplication(original,Vin);
        Check(source.Completed&&source.FinalIdentityVerified&&source.Samples.Count==12,"selected raw acquisition replay");
        var projection=ObdJ1979CatalogueProjection.Build(cat,pin,original,Vin);
        var serialized=JsonSerializer.SerializeToUtf8Bytes(projection);
        using var doc=JsonDocument.Parse(serialized);
        var root=doc.RootElement;
        var rows=root.GetProperty("rows");
        JsonElement Signal(int row,int signal)=>rows[row].GetProperty("signals")[signal];
        Check(Signal(0,0).GetProperty("value").GetProperty("numerator").GetString()=="-1"&&
            Signal(0,0).GetProperty("value").GetProperty("denominator").GetString()=="6","exact rational signed value");
        Check(Signal(0,1).GetProperty("enumeration").GetString()=="fixture state","categorical value");
        Check(rows[1].GetProperty("projection_status").GetString()=="NO_DOCUMENTED_DEFINITION","unknown PID retained");
        Check(Signal(2,0).GetProperty("value").GetProperty("numerator").GetString()=="18446744073709551615",
            "unsigned 64-bit value not rounded");
        Check(Signal(3,0).GetProperty("status").GetString()=="NOT_PRESENT","conditional absence");
        Check(Signal(6,0).GetProperty("status").GetString()=="UNAVAILABLE","sentinel distinct from signed value");
        Check(rows[9].GetProperty("projection_status").GetString()=="DOCUMENTED_WIDTH_MISMATCH","incorrect width");
        Check(!root.GetProperty("source_replay_verified").GetBoolean()&&
            root.GetProperty("source_application_replay_verified").GetBoolean()&&
            !root.GetProperty("permits_programming").GetBoolean()&&!root.GetProperty("physical_vehicle_verified").GetBoolean(),
            "projection cannot claim transport or vehicle validation");
        Check(root.GetProperty("source_report_sha256").GetString()==ObdJ1979DocumentedCatalogue.Sha(original),
            "source digest bound to original bytes");
        Check(ObdJ1979CatalogueProjection.ReplayApplication(Original(true),Vin).Completed,
            "coarse clock ties order initial, sample, final");
        byte[] Alter(Action<JsonObject> modify)
        {
            var copy=JsonNode.Parse(original)!.AsObject();modify(copy);
            return Encoding.UTF8.GetBytes(copy.ToJsonString());
        }
        Reject(()=>ObdJ1979CatalogueProjection.Build(cat,pin,original,"1FA6P8TH0L5100000"),"wrong independent VIN");
        Reject(()=>ObdJ1979CatalogueProjection.ReplayApplication(Alter(x=>x["rxCanId"]="7E9"),Vin),"wrong responder pair");
        Reject(()=>ObdJ1979CatalogueProjection.ReplayApplication(Alter(x=>x["result"]!["rawOnly"]=false),Vin),"numeric report not raw");
        Reject(()=>ObdJ1979CatalogueProjection.ReplayApplication(Alter(x=>x["result"]!["raw_only"]=true),Vin),"ambiguous report alias");
        Reject(()=>ObdJ1979CatalogueProjection.ReplayApplication(Alter(x=>x["result"]!["observations"]![0]!["responseHex"]="41F281FEFF"),Vin),"wrong echoed PID");
        Reject(()=>ObdJ1979CatalogueProjection.ReplayApplication(Alter(x=>x["result"]!["observations"]![0]!["measurement"]=new JsonObject()),Vin),"raw row with measurement");
        Reject(()=>ObdJ1979CatalogueProjection.ReplayApplication(Alter(x=>x["result"]!["admission"]!.AsArray().RemoveAt(1)),Vin),"missing bitmap page");
        Reject(()=>ObdJ1979CatalogueProjection.ReplayApplication(Alter(x=>x["result"]!["admission"]!.AsArray().RemoveAt(9)),Vin),"missing final VIN in complete report");
        var cancelled=ObdJ1979CatalogueProjection.ReplayApplication(Alter(x=>x["completed"]=false),Vin);
        Check(!cancelled.Completed&&cancelled.FinalIdentityVerified,"outer cancellation preserved");
        Reject(()=>ObdJ1979DocumentedCatalogue.Parse(cat,new string('b',64)),"wrong independent reference digest");
        Reject(()=>ObdJ1979DocumentedCatalogue.Parse(Encoding.UTF8.GetBytes(Catalogue(pin).Replace(
            "\"pid\":241","\"pid\":241,\"pid\":242",StringComparison.Ordinal)),pin),"duplicate JSON key");
        Reject(()=>ObdJ1979DocumentedCatalogue.Parse(Encoding.UTF8.GetBytes(Catalogue(pin).Replace(
            "\"width\":16","\"width\":65",StringComparison.Ordinal)),pin),"invalid bit width");
        Reject(()=>ObdJ1979DocumentedCatalogue.Parse(Encoding.UTF8.GetBytes(Catalogue(pin).Replace(
            "\"scale\":[1,3]","\"scale\":[1,0]",StringComparison.Ordinal)),pin),"zero denominator");
        string dir=Path.Combine(Path.GetTempPath(),"camarodiag-catalogue-"+Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            string catPath=Path.Combine(dir,"catalogue.json"),refPath=Path.Combine(dir,"reference.txt"),
                reportPath=Path.Combine(dir,"raw.json"),outputPath=Path.Combine(dir,"projection.json");
            File.WriteAllBytes(catPath,cat);File.WriteAllBytes(refPath,reference);File.WriteAllBytes(reportPath,original);
            var args=new[]{ObdJ1979CatalogueCli.Command,"--catalogue",catPath,"--reference",refPath,
                "--reference-sha256",pin,"--report",reportPath,"--vin",Vin,"--output",outputPath};
            Check(ObdJ1979CatalogueCli.Parse(args).Pin==pin,"CLI options");
            Reject(()=>ObdJ1979CatalogueCli.Parse([..args,"--adapter-index","0"]),"mixed hardware invocation rejected");
            var saved=ObdJ1979CatalogueProjection.ProjectFiles(catPath,refPath,pin,reportPath,Vin);
            ObdJ1979CatalogueProjection.WriteNew(outputPath,saved);
            Check(File.Exists(outputPath),"exclusive projection file");
            Reject(()=>ObdJ1979CatalogueProjection.WriteNew(outputPath,saved),"no overwrite");
            File.AppendAllText(refPath,"changed");
            Reject(()=>ObdJ1979CatalogueProjection.ProjectFiles(catPath,refPath,pin,reportPath,Vin),"reference changed");
        }
        finally{Directory.Delete(dir,true);}
        return count;
    }
}
