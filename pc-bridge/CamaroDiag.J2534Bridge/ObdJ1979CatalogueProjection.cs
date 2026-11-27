using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using static CamaroDiag.J2534Bridge.ObdJ1979DocumentedCatalogue;

namespace CamaroDiag.J2534Bridge;

/// <summary>Local application-layer replay of raw-only live reports. It
/// deliberately does not assert transport replay, reference authenticity,
/// normative compliance or physical provenance. Independent full replay is
/// available via camarodiagconnectors-j1979-catalogue on the original report.</summary>
internal static class ObdJ1979CatalogueProjection
{
    internal const int MaxReportBytes=64*1024*1024;
    internal const int MaxProjectionBytes=128*1024*1024;
    internal const string Schema="camarodiag-j1979-catalogue-local-projection-v1";
    internal sealed record Source(string Vin,bool Completed,string Protocol,Dictionary<string,object> Channel,
        IReadOnlyList<Sample> Samples,bool FinalIdentityVerified);
    internal sealed record Sample(int Pid,int Cycle,long Elapsed,string Status,string? Response);
    private static JsonElement Get(JsonElement obj,string snake)
    {
        Check(obj.ValueKind==JsonValueKind.Object,"Expected report object");
        string camel=Regex.Replace(snake,"_([a-z])",m=>m.Groups[1].Value.ToUpperInvariant());
        bool hasSnake=obj.TryGetProperty(snake,out var a);
        if(camel==snake)return a;
        bool hasCamel=obj.TryGetProperty(camel,out var b);
        Check(!(hasSnake&&hasCamel),"Ambiguous report property "+snake);
        return hasSnake?a:hasCamel?b:default;
    }
    private static bool Null(JsonElement e)=>e.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined;
    private static string? Text(JsonElement e)=>Null(e)?null:e.ValueKind==JsonValueKind.String?e.GetString():
        throw new InvalidDataException("Expected report text");
    private static string RequiredText(JsonElement e)=>Text(e)??throw new InvalidDataException("Missing report text");
    private static byte[] Bytes(string? value,int max=4095)
    {
        Check(value is not null&&value.Length<=max*2&&value.Length%2==0&&
            Regex.IsMatch(value,@"\A[0-9A-Fa-f]*\z"),"Invalid application payload");
        return Convert.FromHexString(value!);
    }
    private static int Id(JsonElement node,int max)
    {
        var text=RequiredText(node);
        Check(text.Length is >=1 and <=8&&Regex.IsMatch(text,@"\A[0-9A-Fa-f]+\z")&&
            uint.TryParse(text,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out var value)&&value<=max,
            "Invalid channel ID");
        return int.Parse(text,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture);
    }
    private static int[] Selected(JsonElement value)
    {
        int[] pids;
        if(value.ValueKind==JsonValueKind.String)
        {
            var encoded=value.GetString()!;
            Check(encoded.Length<=44&&Regex.IsMatch(encoded,@"\A[A-Za-z0-9+/]+={0,2}\z"),"Invalid base64 PID list");
            var decoded=Convert.FromBase64String(encoded);
            Check(Convert.ToBase64String(decoded)==encoded,"Noncanonical PID list");
            pids=decoded.Select(x=>(int)x).ToArray();
        }
        else pids=Array(value,1,32).Select(x=>Int(x,1,255)).ToArray();
        Check(pids.Length is >=1 and <=32&&pids.Distinct().Count()==pids.Length&&
            pids.All(x=>x is >=1 and <=255&&x%32!=0),"Invalid selected PIDs");
        return pids;
    }
    private static bool ValidVin(string? value)=>value is not null&&Regex.IsMatch(value,@"\A[A-HJ-NPR-Z0-9]{17}\z");
    private static bool PositiveVin(byte[]? data,string vin)=>data is {Length:20}&&data[0]==0x49&&data[1]==2&&
        data[2]==1&&data.AsSpan(3).SequenceEqual(Encoding.ASCII.GetBytes(vin));
    private static bool Negative(byte[]? data,int mode)=>data is {Length:3}&&data[0]==0x7F&&data[1]==mode&&
        data[2] is not (0 or 0x78);
    private static void NoMeasurement(JsonElement row)
    {
        Check(Null(Get(row,"measurement")),"Raw-only row carries numerical value");
        var multi=Get(row,"multi_signals");
        Check(Null(multi)||multi.ValueKind==JsonValueKind.Array&&multi.GetArrayLength()==0,
            "Raw-only row carries interpreted signals");
    }
    private static bool FlagFalse(JsonElement row,string key)
    {
        var value=Get(row,key);return Null(value)||value.ValueKind==JsonValueKind.False;
    }
    internal static Source ReplayApplication(byte[] raw,string expectedVin)
    {
        Check(ValidVin(expectedVin),"Expected independent VIN required");
        Check(raw.Length is >0 and <=MaxReportBytes,"Report exceeds size limit");
        using var doc=JsonDocument.Parse(raw,new JsonDocumentOptions{MaxDepth=64});
        var root=doc.RootElement;Unique(root);
        string schema=RequiredText(Get(root,"schema"));
        Check(schema is "camarodiag-j1979-live-v1" or "camarodiag-j1979-legacy-live-v1",
            "Original live report required");
        bool legacy=schema=="camarodiag-j1979-legacy-live-v1";
        var inner=Get(root,"result");var result=inner.ValueKind==JsonValueKind.Undefined?root:inner;
        Check(Bool(Get(result,"raw_only"))&&RequiredText(Get(result,"declared_vin"))==expectedVin,
            "Raw-only report and matching expected VIN required");
        Check(FlagFalse(root,"physical_vehicle_verified")&&FlagFalse(result,"physical_vehicle_verified")&&
            FlagFalse(root,"whole_vehicle_coverage_verified")&&FlagFalse(result,"whole_vehicle_coverage_verified"),
            "Unsubstantiated physical verification");
        var channel=new Dictionary<string,object>();
        if(legacy)
        {
            Check(FlagFalse(root,"physically_addressed"),"Legacy cannot claim physical addressing");
            var variant=Get(root,"expected_protocol");
            int protocol=Int(Null(variant)?Get(root,"profile"):variant,1,5);
            int address=Id(Get(root,"selected_response_source"),254);
            Check(address!=0&&address!=0xF1,"Invalid legacy response source");
            channel["kind"]="legacy";channel["protocol"]=protocol;channel["source"]=address;
        }
        else
        {
            int tx=Id(Get(root,"tx_can_id"),0x1FFFFFFF),rx=Id(Get(root,"rx_can_id"),0x1FFFFFFF);
            bool valid=tx is >=0x7E0 and <=0x7E7&&rx==tx+8 ||
                (tx&0x1FFF00FF)==0x18DA00F1 && (tx>>8&255)<0xF0 &&
                rx==(0x18DAF100|(tx>>8&255));
            Check(valid,"Invalid physical CAN address pair");
            channel["kind"]="can";channel["tx"]=tx;channel["rx"]=rx;
        }
        var selected=Selected(Get(result,"selected_pids"));
        int cycles=Int(Get(result,"requested_cycles"),1,1000);
        int interval=Int(Get(result,"interval_ms"),100,60000);
        int budget=Int(Get(result,"budget_ms"),1,600000);
        Check(cycles*selected.Length<=10000,"Too many requested observations");
        var admission=Array(Get(result,"admission"),1,256);
        var observations=Array(Get(result,"observations"),0,10000);
        foreach(var group in new[]{admission,observations})
        {
            int previous=0;
            foreach(var row in group)
            {
                int start=Int(Get(row,"started_elapsed_ms"),0,int.MaxValue);
                Check(start>=previous,"Reordered observations in source array");
                previous=Int(Get(row,"elapsed_ms"),start,int.MaxValue);
            }
        }
        // Admission comes before samples sharing the same start timestamp;
        // the final VIN must instead come after the last sample.
        var combined=admission.Select((row,i)=>(Row:row,Sample:false,Index:i))
            .Concat(observations.Select((row,i)=>(Row:row,Sample:true,Index:i)))
            .OrderBy(x=>Int(Get(x.Row,"started_elapsed_ms"),0,int.MaxValue))
            .ThenBy(x=>x.Sample?1:RequiredText(Get(x.Row,"phase"))=="FINAL_VIN"?2:0)
            .ThenBy(x=>Int(Get(x.Row,"elapsed_ms"),0,int.MaxValue))
            .ThenBy(x=>x.Index).ToArray();
        var advertised=new SortedSet<int>();
        var samples=new List<Sample>();
        var rawSeen=new HashSet<int>();
        var counts=new Dictionary<int,Dictionary<string,int>>();
        foreach(var pid in selected)counts.Add(pid,new(StringComparer.Ordinal));
        bool identity=false,initial=false,final=false,terminal=false,inventoryComplete=false;
        int nextPage=0,lastEnd=0,lastIdentity=-1,lastCycleStart=-1,rawBytes=0;
        foreach(var entry in combined)
        {
            var row=entry.Row;
            int start=Int(Get(row,"started_elapsed_ms"),0,int.MaxValue);
            int end=Int(Get(row,"elapsed_ms"),start,int.MaxValue);
            int elapsed=Int(Get(row,"duration_ms"),0,int.MaxValue);
            Check(start>=lastEnd&&Math.Abs((long)end-start-elapsed)<=2&&!terminal&&!final,
                "Overlapping, inconsistent or post-terminal observation");
            lastEnd=end;
            string phase=RequiredText(Get(row,"phase")),status=RequiredText(Get(row,"status"));
            Check(status is "PASSED" or "RAW" or "NEGATIVE" or "TIMEOUT" or "FAILED" or "NOT_TESTED","Unknown status");
            byte[] request=Bytes(RequiredText(Get(row,"request_hex")),2);
            var responseHex=Text(Get(row,"response_hex"));
            byte[]? response=responseHex is null?null:Bytes(responseHex);
            rawBytes+=response?.Length??0;Check(rawBytes<=16*1024*1024,"Raw evidence exceeds byte limit");
            NoMeasurement(row);
            if(status is "PASSED" or "RAW" or "NEGATIVE")Check(end<=budget+1,"Successful response after budget");
            if(status=="NEGATIVE")Check(request.Length>0&&Negative(response,request[0]),"Uncorrelated negative response");
            if(entry.Sample)
            {
                int cycle=samples.Count/selected.Length+1,pid=selected[samples.Count%selected.Length];
                Check(phase=="SAMPLE"&&identity&&selected.All(advertised.Contains)&&cycle<=cycles&&
                    entry.Index==samples.Count&&Int(Get(row,"cycle"),1,cycles)==cycle&&
                    Int(Get(row,"pid"),1,255)==pid&&request.SequenceEqual(new byte[]{1,(byte)pid})&&status!="PASSED",
                    "Unexpected or uninterpreted sample without admission");
                if(samples.Count%selected.Length==0)
                {
                    Check(lastCycleStart<0||start+2L>=lastCycleStart+(long)interval,"Cycle interval violated");
                    Check(lastIdentity>=0&&start-lastIdentity<=30002,"Missing periodic VIN check");
                    lastCycleStart=start;
                }
                if(status=="RAW")
                {
                    Check(response is {Length:>=3}&&response[0]==0x41&&response[1]==pid&&
                        (!legacy||response.Length<=7)&&Text(Get(row,"detail"))=="RAW_UNINTERPRETED",
                        "Uncorrelated raw sample");
                    rawSeen.Add(pid);
                }
                samples.Add(new(pid,cycle,end,status,responseHex?.ToUpperInvariant()));
                counts[pid][status]=counts[pid].GetValueOrDefault(status)+1;
            }
            else if(phase is "INITIAL_VIN" or "PERIODIC_VIN" or "FINAL_VIN")
            {
                Check(request.SequenceEqual(new byte[]{9,2})&&status!="RAW","Unexpected VIN request");
                if(phase=="INITIAL_VIN")
                {
                    Check(!initial&&nextPage==0&&samples.Count==0&&entry.Index==0,"Duplicate or late initial VIN");
                    initial=true;
                }
                else Check(identity&&selected.All(advertised.Contains)&&samples.Count%selected.Length==0,
                    "Misplaced identity check");
                identity=status=="PASSED"&&PositiveVin(response,expectedVin);
                if(status=="PASSED")Check(identity,"VIN response differs from independent target");
                if(identity)lastIdentity=end;else terminal=true;
                if(phase=="FINAL_VIN")
                {
                    Check(samples.Count==cycles*selected.Length,"Premature final VIN");
                    final=identity;
                }
            }
            else if(phase=="INVENTORY")
            {
                Check(identity&&samples.Count==0&&!inventoryComplete&&nextPage<=224&&
                    request.SequenceEqual(new byte[]{1,(byte)nextPage})&&status!="RAW","Unexpected support page");
                if(status=="PASSED")
                {
                    Check(response is {Length:6}&&response[0]==0x41&&response[1]==nextPage,"Invalid support bitmap");
                    for(int bit=0;bit<31;bit++)
                        if((response![2+bit/8]&(0x80>>(bit%8)))!=0)advertised.Add(nextPage+bit+1);
                    // Continuation PID is not a measurement, but the native
                    // inventories may list it as a supported identifier.
                    bool hasNext=(response![5]&1)!=0;
                    Check(!hasNext||nextPage<224,"Support bitmap exceeds PID FF");
                    if(hasNext)advertised.Add(nextPage+32);
                    inventoryComplete=!hasNext;nextPage+=32;
                }
                else terminal=true;
            }
            else throw new InvalidDataException("Unexpected raw-only admission phase");
            if(status is "FAILED" or "NOT_TESTED")terminal=true;
        }
        Check(initial&&samples.Count>0,"No admitted samples to project");
        Check(RequiredText(Get(result,"observed_vin"))==expectedVin,"Declared initial VIN differs");
        var declared=Array(Get(result,"advertised_pids"),0,255).Select(x=>Int(x,1,255)).ToArray();
        Check(declared.Distinct().Count()==declared.Length&&
            declared.Where(x=>x%32!=0).Order().SequenceEqual(advertised.Where(x=>x%32!=0))&&
            declared.Where(x=>x%32==0).All(advertised.Contains),"Reported inventory differs from bitmaps");
        Check(Bool(Get(result,"inventory_complete"))==inventoryComplete,"Inventory completeness differs");
        int recorded=Int(Get(result,"completed_cycles"),0,cycles),possible=samples.Count/selected.Length;
        bool complete=Bool(Get(result,"completed"));
        Check(recorded==possible||!complete&&terminal&&samples.Count%selected.Length==0&&recorded==possible-1,
            "Recorded cycles differ from evidence");
        Check(Int(Get(result,"duration_ms"),0,int.MaxValue)+2L>=lastEnd,"Report duration precedes evidence");
        Check(!complete||final&&!terminal&&recorded==cycles&&selected.All(rawSeen.Contains)&&
            Null(Get(result,"stop_reason")),"Unsupported completed report");
        Check(complete||!string.IsNullOrWhiteSpace(Text(Get(result,"stop_reason"))),"Missing partial-report reason");
        // Windows may cancel the wrapper after a recorder has finished.
        if(!Null(inner)&&!Null(Get(root,"completed"))&& !Bool(Get(root,"completed")))complete=false;
        return new(expectedVin,complete,legacy?"LEGACY":"CAN",channel,samples,final);
    }
    internal static Dictionary<string,object?> Build(byte[] catalogueBytes,string referenceSha,
        byte[] sourceBytes,string expectedVin)
    {
        var catalogue=ObdJ1979DocumentedCatalogue.Parse(catalogueBytes,referenceSha);
        var source=ReplayApplication(sourceBytes,expectedVin);
        var rows=new List<Dictionary<string,object?>>();
        var counts=new SortedDictionary<string,int>(StringComparer.Ordinal);
        int signalRows=0;
        foreach(var sample in source.Samples)
        {
            var row=catalogue.Project(sample.Pid,source.Protocol,sample.Cycle,sample.Elapsed,sample.Status,
                sample.Response,ref signalRows);
            var status=(string)row["projection_status"]!;
            counts[status]=counts.GetValueOrDefault(status)+1;rows.Add(row);
        }
        return new(){
            ["schema"]=Schema,["reference_edition"]=catalogue.Edition,["reference_sha256"]=catalogue.ReferenceSha256,
            ["catalogue_sha256"]=catalogue.CatalogueSha256,["source_report_sha256"]=Sha(sourceBytes),
            ["expected_vin_independently_supplied"]=true,["declared_vin"]=source.Vin,["channel"]=source.Channel,
            ["source_completed"]=source.Completed,["final_identity_verified"]=source.FinalIdentityVerified,
            ["source_application_replay_verified"]=true,["source_replay_verified"]=false,
            ["reference_authenticity_verified"]=false,["normative_compliance_verified"]=false,
            ["physical_vehicle_verified"]=false,["authenticated"]=false,["permits_programming"]=false,
            ["diagnostic_verdict"]=null,["counts"]=counts,["rows"]=rows};
    }
    private static FileStream OpenInput(string path,int max)
    {
        var full=Path.GetFullPath(path);
        Check((File.GetAttributes(full)&(FileAttributes.ReparsePoint|FileAttributes.Directory))==0,
            "Regular local input required");
        var file=new FileStream(full,FileMode.Open,FileAccess.Read,FileShare.Read);
        if(file.Length<=0||file.Length>max){file.Dispose();throw new InvalidDataException("Input exceeds byte limit");}
        return file;
    }
    private static byte[] Read(FileStream file,int max)
    {
        file.Position=0;Check(file.Length>0&&file.Length<=max,"Input exceeds byte limit");
        var bytes=new byte[(int)file.Length];file.ReadExactly(bytes);
        Check(file.ReadByte()==-1,"Input grew during read");return bytes;
    }
    private static string Hash(FileStream file,int max)
    {
        file.Position=0;Check(file.Length>0&&file.Length<=max,"Reference exceeds byte limit");
        using var hash=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        byte[] buffer=new byte[65536];int length;long total=0;
        while((length=file.Read(buffer,0,buffer.Length))!=0)
        {
            total+=length;Check(total<=max,"Reference exceeds byte limit");hash.AppendData(buffer,0,length);
        }
        Check(total==file.Length,"Reference changed during read");
        return Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
    }
    internal static Dictionary<string,object?> ProjectFiles(string cataloguePath,string referencePath,string pin,
        string reportPath,string expectedVin)
    {
        Digest(pin);
        using var catalogue=OpenInput(cataloguePath,MaxCatalogueBytes);
        using var reference=OpenInput(referencePath,MaxReferenceBytes);
        using var report=OpenInput(reportPath,MaxReportBytes);
        var cat=Read(catalogue,MaxCatalogueBytes);var source=Read(report,MaxReportBytes);
        var referenceSha=Hash(reference,MaxReferenceBytes);Check(referenceSha==pin,"Reference SHA-256 mismatch");
        var projection=Build(cat,referenceSha,source,expectedVin);
        Check(cat.AsSpan().SequenceEqual(Read(catalogue,MaxCatalogueBytes))&&
            source.AsSpan().SequenceEqual(Read(report,MaxReportBytes))&&
            Hash(reference,MaxReferenceBytes)==pin,"Projection inputs changed during processing");
        return projection;
    }
    internal static void WriteNew(string output,Dictionary<string,object?> projection)
    {
        byte[] bytes=JsonSerializer.SerializeToUtf8Bytes(projection,new JsonSerializerOptions{WriteIndented=true});
        Check(bytes.Length<=MaxProjectionBytes,"Projection exceeds output limit");
        using var file=new FileStream(output,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        file.Write(bytes);file.Flush(true);
    }
}
