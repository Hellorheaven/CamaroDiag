using System.Globalization;

namespace CamaroDiag.J2534Bridge;

/// <summary>Contextual fuel trim and current-monitoring-cycle projection.
/// SAE J1979DA 2011, Figure B3 and tables B7-B10, B46, B66-B69.
/// Never merge sources, confuse PID 01 with PID 41, or infer unsupported
/// upper banks from a missing response.</summary>
internal static class ObdJ1979OperatingContext
{
    internal sealed record Observation(string RequestHex,string? ResponseHex,string Status);
    internal sealed record TrimSignal(int Bank,string Name,int RawEncoded,double? Number,string Unit,string Attribution);
    internal sealed record Trim(string RequestHex,int? Frame,int? Layout1d,IReadOnlyList<TrimSignal> Signals,
        bool? UpperBankVerified);
    internal sealed record Monitor(string Name,bool? Supported,bool? Enabled,bool? Complete,
        bool EnabledBit,bool IncompleteBit,bool Consistent);
    internal sealed record Cycle(string RequestHex,string RawHex,string? BaselineRawHex,bool SupportVerified,
        bool CompressionIgnition,IReadOnlyList<Monitor> Monitors,bool Consistent);
    internal sealed record Projection(Cycle? DriveCycle,IReadOnlyList<Trim> FuelTrims);

    private static readonly string?[] Continuous=["misfire","fuel-system","components"];
    private static readonly string?[] Spark=["catalyst","heated-catalyst","evaporative-system","secondary-air",
        null,"oxygen-sensor","oxygen-sensor-heater","egr-vvt"];
    private static readonly string?[] Diesel=["nmhc-catalyst","nox-aftertreatment",null,"boost-pressure",
        null,"exhaust-gas-sensor","particulate-filter","egr-vvt"];

    internal static bool IsTrim(int pid)=>pid is >=6 and <=9 or >=0x55 and <=0x58;
    internal static bool ValidTrim(int pid,int width)=>IsTrim(pid)&&width is 1 or 2;
    private static byte[]? Bytes(string? value)
    {
        if(value is null||value.Length>8190||value.Length%2!=0)return null;
        try{return Convert.FromHexString(value);}catch(FormatException){return null;}
    }

    private static IEnumerable<(byte[] Request,byte[] Data)> Correlated(IEnumerable<Observation> observations)
    {
        foreach(var row in observations){
            if(row.Status is not ("RAW" or "PASSED"))continue;
            var request=Bytes(row.RequestHex);var response=Bytes(row.ResponseHex);
            if(request is null||response is null||response.Length<=request.Length)continue;
            if(request.Length==2&&request[0]==1&&response[0]==0x41&&response[1]==request[1])
                yield return (request,response[2..]);
            else if(request.Length==3&&request[0]==2&&request[2]==0&&
                response[0]==0x42&&response[1]==request[1]&&response[2]==0)
                yield return (request,response[3..]);
        }
    }

    internal static Trim? DecodeTrim(byte[] request,byte[] data,int? layout)
    {
        if(request.Length is not (2 or 3)||request[0] is not (1 or 2)||
            request.Length!=(request[0]==2?3:2)||(request[0]==2&&request[2]!=0)||
            !ValidTrim(request[1],data.Length))return null;
        if(layout is <0 or >255)return null;
        int pid=request[1];
        bool secondary=pid>=0x55,shortTerm=pid is 6 or 8 or 0x55 or 0x57;
        int bank=pid is 6 or 7 or 0x55 or 0x56?1:2;
        string prefix=(shortTerm?"short":"long")+"-term-"+(secondary?"secondary-o2-":"")+"fuel-trim-bank-";
        bool upper=layout is int bitmap&&(bitmap&(bank==1?0x30:0xC0))!=0;
        var signals=new List<TrimSignal>();
        for(int index=0;index<data.Length;index++){
            int current=bank+index*2,raw=data[index];
            bool verified=index==0||upper;
            signals.Add(new(current,prefix+current.ToString(CultureInfo.InvariantCulture),raw,
                verified?(raw-128)*100d/128:null,"%",verified?"DEFINED":"UNVERIFIED_UPPER_BANK"));
        }
        return new(Convert.ToHexString(request),request.Length==3?request[2]:null,layout,
            signals.AsReadOnly(),data.Length==2?upper:null);
    }

    internal static Cycle? DecodeCycle(byte[] data,byte[]? baseline)
    {
        if(data.Length!=4||data[0]!=0||(data[1]&0x80)!=0)return null;
        int b=data[1],c=data[2],d=data[3];
        bool compression=(b&8)!=0;
        int reserved=compression?0x14:0x10;
        if(((c|d)&reserved)!=0)return null;
        bool valid=baseline is {Length:4}&&((baseline[1]&8)!=0)==compression;
        var monitors=new List<Monitor>();
        void Add(string?[] names,int? support,int enabled,int incomplete){
            for(int bit=0;bit<names.Length;bit++){
                if(names[bit] is not string name)continue;
                int mask=1<<bit;
                bool? supported=support is int word?(word&mask)!=0:null;
                bool en=(enabled&mask)!=0,inc=(incomplete&mask)!=0;
                monitors.Add(new(name,supported,supported==true?en:null,supported==true?!inc:null,
                    en,inc,supported!=false||!(en||inc)));
            }
        }
        Add(Continuous,valid?baseline![1]:null,b,b>>4);
        Add(compression?Diesel:Spark,valid?baseline![2]:null,c,d);
        return new("0141",Convert.ToHexString(data),baseline is null?null:Convert.ToHexString(baseline),
            valid,compression,monitors.AsReadOnly(),monitors.All(x=>x.Consistent));
    }

    internal static Projection Project(IEnumerable<Observation> observations)
    {
        var rows=Correlated(observations).ToArray();
        var layouts=rows.Where(x=>x.Request.SequenceEqual(new byte[]{1,0x1D})&&x.Data.Length==1).ToArray();
        int? layout=layouts.Length==1?layouts[0].Data[0]:null;
        var baselines=rows.Where(x=>x.Request.SequenceEqual(new byte[]{1,1})&&x.Data.Length==4).ToArray();
        var cycles=rows.Where(x=>x.Request.SequenceEqual(new byte[]{1,0x41})).ToArray();
        var cycle=cycles.Length==1?DecodeCycle(cycles[0].Data,baselines.Length==1?baselines[0].Data:null):null;
        var trims=rows.Where(x=>IsTrim(x.Request[1])).Select(x=>DecodeTrim(x.Request,x.Data,layout))
            .OfType<Trim>().ToArray();
        return new(cycle,trims);
    }

    internal sealed record Layout(string State,int? RawEncoded);

    internal static Layout ResolveLayout(IEnumerable<Observation> observations)
    {
        var rows=Correlated(observations).ToArray();
        var pages=rows.Where(x=>x.Request.SequenceEqual(new byte[]{1,0})).ToArray();
        var values=rows.Where(x=>x.Request.SequenceEqual(new byte[]{1,0x1D})).ToArray();
        if(pages.Length!=1||pages[0].Data.Length!=4)return new("UNVERIFIED",null);
        bool advertised=(pages[0].Data[3]&8)!=0;
        if(!advertised)return new(values.Length==0?"NOT_ADVERTISED_BY_SELECTED_ECU":"UNVERIFIED",null);
        if(values.Length!=1||values[0].Data.Length!=1)return new("UNVERIFIED",null);
        return new("VERIFIED",values[0].Data[0]);
    }

    internal static IReadOnlyList<ObdJ1979MultiSignal.Signal>? LiveSignals(byte[] request,byte[] response,Layout layout)
    {
        if(request.Length!=2||request[0]!=1||response.Length<=2||response[0]!=0x41||
            response[1]!=request[1])return null;
        var decoded=DecodeTrim(request,response[2..],layout.RawEncoded);
        return decoded?.Signals.Where(x=>x.Number.HasValue).Select(x=>
            new ObdJ1979MultiSignal.Signal(request[1],x.Name,x.Unit,x.Number!.Value,x.RawEncoded)).ToArray();
    }

    internal static IEnumerable<string> Display(Projection projection)
    {
        foreach(var trim in projection.FuelTrims)
            foreach(var signal in trim.Signals)
                yield return trim.RequestHex+" "+signal.Name+"="+(signal.Number is double number?
                    number.ToString("0.###",CultureInfo.CurrentCulture)+" %":
                    "RAW="+signal.RawEncoded.ToString("X2",CultureInfo.InvariantCulture));
        if(projection.DriveCycle is {} cycle)
            foreach(var monitor in cycle.Monitors.Where(m=>m.Supported==true))
                yield return "0141 "+monitor.Name+" "+(monitor.Enabled==true?"●":"○")+" "+(monitor.Complete==true?"✓":"✗");
    }

    internal static object Evidence(Projection p)=>new {
        drive_cycle=p.DriveCycle is {} c?new {
            request_hex=c.RequestHex,raw_hex=c.RawHex,baseline_raw_hex=c.BaselineRawHex,
            support_verified=c.SupportVerified,compression_ignition=c.CompressionIgnition,
            monitors=c.Monitors.Select(m=>new {name=m.Name,supported=m.Supported,enabled=m.Enabled,
                complete=m.Complete,enabled_bit=m.EnabledBit,incomplete_bit=m.IncompleteBit,consistent=m.Consistent}),
            consistent=c.Consistent
        }:null,
        fuel_trims=p.FuelTrims.Select(t=>new {request_hex=t.RequestHex,frame=t.Frame,layout_1d=t.Layout1d,
            signals=t.Signals.Select(s=>new {bank=s.Bank,name=s.Name,raw_encoded=s.RawEncoded,
                number=s.Number,unit=s.Unit,attribution=s.Attribution}),upper_bank_verified=t.UpperBankVerified})
    };
}
