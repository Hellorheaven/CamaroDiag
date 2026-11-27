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
        // Same synthetic native CL/1 fixture as the Android regression.
        byte[] native=Convert.FromHexString(
            "434c010100004300e0070000e807000000314731464131525858523031323334353603f1f2f3000000000000000000000000" +
            "000000000000000000000000000000000001006400e80300006f021499434c010201002e0001010100000000000000000005" +
            "0000006400140005000000090249020131473146413152585852303132333435367d8eac1e434c0102020020000201010000" +
            "000000050000000a00000064000600050000000100410000000001284d533a434c01020300200002010100000000000a0000" +
            "000f00000064000600050000000120412000000001f354c069434c01020400200002010100000000000f0000001400000064" +
            "0006000500000001404140000000011787631d434c0102050020000201010000000000140000001900000064000600050000" +
            "0001604160000000011ca946c5434c0102060020000201010000000000190000001e00000064000600050000000180418000" +
            "0000017fd2b851434c01020700200002010100000000001e00000023000000640006000500000001a041a00000000186dac7" +
            "b7434c01020800200002010100000000002300000028000000640006000500000001c041c00000000190aeb200434c010209" +
            "0020000201010000000000280000002d000000640006000500000001e041e00000e0006eea5e13434c01020a001f00040201" +
            "000100f1002d00000032000000640005000500000001f141f181feff01c3c00d434c01020b001d00040201000100f2003200" +
            "000037000000640003000500000001f241f20001dd414e434c01020c002400040201000100f300370000003c00000064000a" +
            "000500000001f341f3fffffffffffffffff5ce1095434c01020d002e0005010100000000003c000000410000006400140005" +
            "000000090249020131473146413152585852303132333435360667a450434c01030e008000000f01000d0000000300000003" +
            "0000000000000000000000410000006a0000000000000000000000000000000000000000000000000000000000000000000e" +
            "0001000100010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000009e60ab59");
        var nativeProjection=ObdJ1979CatalogueProjection.Build(cat,pin,native,Vin);
        Check((string?)nativeProjection["schema"]==ObdJ1979CatalogueProjection.NativeSchema&&
            Equals(nativeProjection["source_replay_verified"],true)&&Equals(nativeProjection["transport_frames_replayed"],false),
            "native source replay boundary");
        Check((string?)nativeProjection["source_capture_sha256"]==ObdJ1979DocumentedCatalogue.Sha(native),"native digest");
        using(var nativeDoc=JsonDocument.Parse(JsonSerializer.SerializeToUtf8Bytes(nativeProjection)))
        {
            var nativeRows=nativeDoc.RootElement.GetProperty("rows");
            Check(nativeRows.GetArrayLength()==3,"native sample count");
            Check(nativeRows[0].GetProperty("signals")[0].GetProperty("value").GetProperty("numerator").GetString()=="-1",
                "native rational projection");
            Check(nativeRows[1].GetProperty("projection_status").GetString()=="NO_DOCUMENTED_DEFINITION","unknown native PID");
        }
        var nativeReport=CamaroDiagConnectors.Windows.J1979LiveNative.Decode(new MemoryStream(native));
        var nativeCsv=Encoding.ASCII.GetString(CamaroDiagConnectors.Windows.J1979LiveNativeCli.Csv(nativeReport));
        Check(nativeCsv.Contains("1,F1,50,RAW,41F181FEFF,7E0,7E8,"+Vin+",true",StringComparison.Ordinal),"native raw CSV");
        Reject(()=>ObdJ1979CatalogueProjection.Build(cat,pin,native[..^1],Vin),"truncated native source");
        Reject(()=>ObdJ1979CatalogueProjection.Build(cat,pin,native,"1FA6P8TH0L5100000"),"native VIN mismatch");
        // Same CF/1 wire fixture and synthetic frame definitions as Android.
        byte[] freeze=Convert.FromHexString(
            "4346010100006800e0070000e80700000060ea00003147314641314533304431323334353637020001000000000000020c0d" +
            "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000001be52aa643460102010020000101020005000000140009024902013147314641314533304431" +
            "3233343536370fee793343460102020014000101030005000000070002000042000040180000a518bfa24346010203001200" +
            "0101030005000000050002020042020003014bdf4814434601020400120001010300050000000500020c00420c001f403ec1" +
            "dd26434601020500110001010300050000000400020d00420d0050b116910c43460102060012000101030005000000050002" +
            "020042020003018b28c15f43460102070014000101030005000000070002000142000140180000f3ca56bb43460102080012" +
            "0001010300050000000500020201420201030199eded5e434601020900120001010300050000000500020c01420c011f406c" +
            "a9891e434601020a00110001010300050000000400020d01420d01506e81ff64434601020b00120001010300050000000500" +
            "0202014202010301d9409567434601020c002000010102000500000014000902490201314731464131453330443132333435" +
            "363723d3f9ee434601030d001400000207000c0000003c0000000202000000000000937d821a");
        byte[] freezeCat=Encoding.UTF8.GetBytes("""{"schema":"camarodiag-j1979-freeze-catalogue-v1","edition":"synthetic","reference_name":"synthetic","reference_sha256":"PIN","frames":[{"frame":0,"definitions":[{"pid":12,"payload_bytes":2,"protocols":["CAN"],"reference":"synthetic","signals":[{"name":"sample","unit":"fixture","bits":{"offset":0,"width":16,"order":"MSB0"},"signed":false,"scale":[1,2],"offset":[0,1],"reference":"test only"}]},{"pid":13,"payload_bytes":1,"protocols":["CAN"],"reference":"synthetic","signals":[{"name":"sample","unit":"fixture","bits":{"offset":0,"width":8,"order":"MSB0"},"signed":false,"scale":[1,2],"offset":[0,1],"reference":"test only"}]}]},{"frame":1,"definitions":[{"pid":12,"payload_bytes":2,"protocols":["CAN"],"reference":"synthetic","signals":[{"name":"sample","unit":"fixture","bits":{"offset":0,"width":16,"order":"MSB0"},"signed":false,"scale":[1,2],"offset":[100,1],"reference":"test only"}]}]}]}""".Replace("PIN",pin,StringComparison.Ordinal));
        const string freezeVin="1G1FA1E30D1234567";
        var freezeProjection=ObdJ1979CatalogueProjection.Build(freezeCat,pin,freeze,freezeVin);
        Check(Equals(freezeProjection["schema"],ObdJ1979FreezeCatalogue.Projection)&&
            Equals(freezeProjection["final_identity_verified"],true)&&
            Equals(freezeProjection["permits_programming"],false)&&
            Equals(freezeProjection["physical_vehicle_verified"],false),"CF/1 projection boundaries");
        using(var freezeDoc=JsonDocument.Parse(JsonSerializer.SerializeToUtf8Bytes(freezeProjection)))
        {
            var entries=freezeDoc.RootElement.GetProperty("rows");
            Check(entries.GetArrayLength()==4,"CF/1 measurement count");
            Check(entries[0].GetProperty("signals")[0].GetProperty("value").GetProperty("numerator").GetString()=="4000",
                "frame zero definition");
            Check(entries[2].GetProperty("signals")[0].GetProperty("value").GetProperty("numerator").GetString()=="4100",
                "frame one definition differs");
            Check(entries[3].GetProperty("projection_status").GetString()=="NO_DOCUMENTED_DEFINITION","unknown frame PID");
            var coverage=freezeDoc.RootElement.GetProperty("catalogue_coverage");
            Check(coverage.GetProperty("scope").GetString()=="EXPLICIT_FREEZE_FRAMES_ONLY"&&
                !coverage.GetProperty("normative_compliance_verified").GetBoolean()&&
                !coverage.GetProperty("hardware_provenance_verified").GetBoolean(),
                "CF/1 audit never implies normative or physical qualification");
            var audited=coverage.GetProperty("frames");
            Check(audited.GetArrayLength()==2&&audited[0].GetProperty("frame").GetInt32()==0&&
                audited[1].GetProperty("frame").GetInt32()==1,"CF/1 audit scopes both requested frames");
            Check(audited[0].GetProperty("advertised_without_documentation").GetArrayLength()==0&&
                audited[0].GetProperty("documented_pids").GetArrayLength()==2,
                "frame zero has separately documented PIDs");
            Check(audited[1].GetProperty("advertised_without_documentation")[0].GetInt32()==13&&
                audited[1].GetProperty("responded_measurement_pids").GetArrayLength()==2&&
                audited[1].GetProperty("projected_measurement_pids").GetArrayLength()==1,
                "frame one unrecognised value is retained without cross-frame interpretation");
            Check(audited[1].GetProperty("documented_without_advertisement").GetArrayLength()==0,
                "complete inventory permits determining unadvertised definitions");
            Check(freezeDoc.RootElement.GetProperty("source_capture_sha256").GetString()==
                ObdJ1979DocumentedCatalogue.Sha(freeze),"CF/1 original digest");
        }
        Reject(()=>ObdJ1979CatalogueProjection.Build(freezeCat,pin,freeze[..^1],freezeVin),"truncated CF/1");
        Reject(()=>ObdJ1979CatalogueProjection.Build(freezeCat,pin,freeze,Vin),"wrong independent CF/1 VIN");
        Reject(()=>ObdJ1979CatalogueProjection.Build(cat,pin,freeze,freezeVin),"live catalogue cannot decode freeze");
        Reject(()=>ObdJ1979FreezeCatalogue.Parse(freezeCat,new string('b',64)),"wrong CF/1 reference pin");
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
