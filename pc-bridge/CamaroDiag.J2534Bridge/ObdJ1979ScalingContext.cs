namespace CamaroDiag.J2534Bridge;

/// <summary>
/// ECU-local engineering ranges from correlated Mode 01 configuration.
/// Do not combine records across responders, VINs, channels or sessions.
/// Historic application measurements are retained for evidence compatibility;
/// this projection supersedes their default scaling for dependent PIDs.
/// ISO 15031-5:2006 B.60/B.61.
/// </summary>
internal static class ObdJ1979ScalingContext
{
    internal sealed record Observation(string RequestHex,string? ResponseHex,string Status);
    internal sealed record Configuration(int Pid,string State,string? DataHex);
    internal sealed record Signal(string Name,string Unit,int RawEncoded,double? Number,string ScaleSource);
    internal sealed record Measurement(string RequestHex,int ConfigurationPid,IReadOnlyList<Signal> Signals);
    internal sealed record Projection(IReadOnlyList<Configuration> Configuration,IReadOnlyList<Measurement> Records);
    private sealed record Range(string State,byte[]? Data=null)
    {
        internal Configuration Export(int pid)=>new(pid,State,Data is null?null:Convert.ToHexString(Data));
    }

    internal static int? Dependency(int pid)=>pid switch {
        0x10=>0x50,
        0x0B or 0x44 or >=0x24 and <=0x2B or >=0x34 and <=0x3B=>0x4F,
        _=>null
    };

    internal static bool IsConfiguration(int pid)=>pid is 0x4F or 0x50;
    internal static bool ValidConfiguration(int pid,ReadOnlySpan<byte> data)=>
        IsConfiguration(pid)&&data.Length==4&&(pid!=0x50||(data[1]|data[2]|data[3])==0);

    private static byte[]? Bytes(string? hex)
    {
        if(hex is null||hex.Length>8190||hex.Length%2!=0)return null;
        try{return Convert.FromHexString(hex);}catch(FormatException){return null;}
    }

    internal sealed class Context
    {
        private readonly Range first,second;
        private Context(Range first,Range second){this.first=first;this.second=second;}
        internal IReadOnlyList<Configuration> Configurations=>[first.Export(0x4F),second.Export(0x50)];

        internal static Context Build(IEnumerable<Observation> observations)
        {
            var pages=new Dictionary<int,List<byte[]?>>();
            var configs=new Dictionary<int,List<byte[]?>>();
            foreach(var observation in observations){
                var req=Bytes(observation.RequestHex);
                if(req is not {Length:2}||req[0]!=1)continue;
                var res=observation.Status is "RAW" or "PASSED"?Bytes(observation.ResponseHex):null;
                var destination=req[1]%32==0?pages:IsConfiguration(req[1])?configs:null;
                if(destination is null)continue;
                if(!destination.TryGetValue(req[1],out var candidates))
                    destination.Add(req[1],candidates=[]);
                candidates.Add(res);
            }
            var supported=new HashSet<int>();
            var seen=new HashSet<int>();
            bool ended=false;
            for(int page=0;page<256;page+=32){
                if(!pages.TryGetValue(page,out var list)||list.Count!=1||
                    list[0] is not {Length:6} res||res[0]!=0x41||res[1]!=page)break;
                if(page==0xE0&&(res[5]&1)!=0)break;
                seen.Add(page);
                for(int bit=0;bit<31;bit++)
                    if((res[2+bit/8]&(0x80>>(bit%8)))!=0)supported.Add(page+bit+1);
                if((res[5]&1)==0){ended=true;break;}
            }
            Range Resolve(int pid)
            {
                if(!supported.Contains(pid)){
                    if(configs.ContainsKey(pid))return new("UNVERIFIED");
                    return seen.Contains((pid-1)/32*32)||ended?
                        new("DEFAULT_UNSUPPORTED"):new("UNVERIFIED");
                }
                if(!configs.TryGetValue(pid,out var candidates)||candidates.Count!=1||
                    candidates[0] is not {Length:6} response||response[0]!=0x41||
                    response[1]!=pid||!ValidConfiguration(pid,response.AsSpan(2)))
                    return new("UNVERIFIED");
                return new("ECU_CONFIGURATION",response[2..]);
            }
            return new(Resolve(0x4F),Resolve(0x50));
        }

        internal Measurement? Decode(byte[] request,byte[] response)
        {
            if(request.Length is not (2 or 3)||request[0] is not (1 or 2))return null;
            int mode=request[0],pid=request[1];
            if(request.Length!=(mode==2?3:2)||(mode==2&&request[2]!=0))return null;
            if(Dependency(pid) is not int dep||response.Length<=request.Length||
                response[0]!=mode+0x40||!response.AsSpan(1,request.Length-1).SequenceEqual(request.AsSpan(1)))
                return null;
            var data=response.AsSpan(request.Length);
            int width=pid==0x0B?1:pid is 0x10 or 0x44?2:4;
            if(data.Length!=width)return null;
            var config=dep==0x4F?first:second;
            byte[] maxima=config.Data??new byte[4];

            Signal Field(string name,string unit,int raw,int index,double defaultScale,
                Func<int,double> customScale,int offset=0)
            {
                int max=maxima[index];
                double scale=max==0?defaultScale:customScale(max);
                return new(name,unit,raw,config.State=="UNVERIFIED"?null:(raw-offset)*scale,
                    config.State=="UNVERIFIED"?"UNVERIFIED":max==0?"DEFAULT":"ECU_CONFIGURATION");
            }
            int a=width==1?data[0]:(data[0]<<8)|data[1];
            var fields=new List<Signal>();
            switch(pid){
                case 0x0B:fields.Add(Field("intake-manifold-absolute-pressure","kPa",a,3,1,x=>x*10d/255));break;
                case 0x10:fields.Add(Field("mass-air-flow","g/s",a,0,.01,x=>x*10d/65535));break;
                case 0x44:fields.Add(Field("commanded-equivalence-ratio","ratio",a,0,1d/32768,x=>x/65535d));break;
                default:
                    bool voltage=pid<0x34;
                    string prefix=$"wideband-o2-sensor-{pid-(voltage?0x24:0x34)+1}-";
                    fields.Add(Field(prefix+"equivalence-ratio","ratio",a,0,1d/32768,x=>x/65535d));
                    int b=(data[2]<<8)|data[3];
                    if(voltage)fields.Add(Field(prefix+"voltage","V",b,1,1d/8192,x=>x/65535d));
                    else {
                        var current=Field(prefix+"current","mA",b,2,1d/256,x=>x/32768d,32768);
                        if(maxima[2]!=0&&config.State!="UNVERIFIED")
                            current=current with {Number=(b>=32768?b-65536:b)*maxima[2]/32768d};
                        fields.Add(current);
                    }
                    break;
            }
            return new(Convert.ToHexString(request),dep,fields.AsReadOnly());
        }
    }

    internal static Projection Project(IEnumerable<Observation> observations)
    {
        var rows=observations.ToArray();
        var context=Context.Build(rows);
        var records=new List<Measurement>();
        foreach(var row in rows){
            if(row.Status is not ("RAW" or "PASSED"))continue;
            var req=Bytes(row.RequestHex);var res=Bytes(row.ResponseHex);
            if(req is null||res is null)continue;
            if(context.Decode(req,res) is { } record)records.Add(record);
        }
        return new(context.Configurations,records.AsReadOnly());
    }

    internal static object Evidence(Projection projection)=>new {
        configuration=projection.Configuration.Select(c=>new {pid=c.Pid,state=c.State,data_hex=c.DataHex}),
        records=projection.Records.Select(r=>new {request_hex=r.RequestHex,configuration_pid=r.ConfigurationPid,
            signals=r.Signals.Select(s=>new {name=s.Name,unit=s.Unit,raw_encoded=s.RawEncoded,
                number=s.Number,scale_source=s.ScaleSource})})
    };
}
