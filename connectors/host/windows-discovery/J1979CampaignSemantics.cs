using System.Text;

namespace CamaroDiagConnectors.Windows;

internal static class J1979CampaignSemantics
{
    private static readonly string[] States=["","PASSED","RAW","NEGATIVE","TIMEOUT","FAILED"];
    private static readonly string[] Ends=["","COMPLETE","INITIAL_UNVERIFIED","BUDGET","LINK_FAULT","SINK_STOPPED"];
    private static readonly HashSet<int> One=[4,5,6,7,8,9,10,11,13,14,15,17,19,29,44,45,46,47,48,51,69,70,71,72,73,74,75,76,82,90,91,92,97,98];
    private static readonly HashSet<int> Two=[12,16,31,33,34,35,49,50,60,61,62,63,66,67,68,77,78,83,84,89,93,94,99];
    internal sealed record Measurement(int Mode,int Pid,string Name,double Number,string Unit);
    internal sealed record MonitorRecord(int MonitorId,int TestId,int UnitScalingId,int ValueEncoded,int MinimumEncoded,int MaximumEncoded);
    // Match the existing bridge's explicit classic SAE J1979 PID formulas.
    private static Measurement? DecodeMeasurement(byte[] req,byte[] res){
        int mode=req[0];
        if(mode is not (1 or 2))return null;
        int pid=req[1],offset=mode==2?3:2;
        if(res.Length<=offset)return null;
        int a=res[offset],b=res.Length>offset+1?res[offset+1]:0;
        return pid switch {
            0x04 => new(mode,pid,"calculated-engine-load",a * 100d / 255,"%"),
            0x05 => new(mode,pid,"coolant-temperature",(double)a - 40,"degC"),
            0x06 => new(mode,pid,"short-term-fuel-trim-bank-1",a * 100d / 128 - 100,"%"),
            0x07 => new(mode,pid,"long-term-fuel-trim-bank-1",a * 100d / 128 - 100,"%"),
            0x08 => new(mode,pid,"short-term-fuel-trim-bank-2",a * 100d / 128 - 100,"%"),
            0x09 => new(mode,pid,"long-term-fuel-trim-bank-2",a * 100d / 128 - 100,"%"),
            0x0C => new(mode,pid,"engine-rpm",(a * 256d + b) / 4,"rpm"),
            0x0D => new(mode,pid,"vehicle-speed",(double)a,"km/h"),
            0x0E => new(mode,pid,"timing-advance",a / 2d - 64,"degBTDC"),
            0x0F => new(mode,pid,"intake-air-temperature",(double)a - 40,"degC"),
            0x10 => new(mode,pid,"mass-air-flow",(a * 256d + b) / 100,"g/s"),
            0x11 => new(mode,pid,"throttle-position",a * 100d / 255,"%"),
            0x1F => new(mode,pid,"engine-runtime",a * 256d + b,"s"),
            0x2F => new(mode,pid,"fuel-tank-level",a * 100d / 255,"%"),
            0x33 => new(mode,pid,"barometric-pressure",(double)a,"kPa"),
            0x42 => new(mode,pid,"control-module-voltage",(a * 256d + b) / 1000,"V"),
            0x46 => new(mode,pid,"ambient-air-temperature",(double)a - 40,"degC"),
            0x52 => new(mode,pid,"ethanol-content",a * 100d / 255,"%"),
            0x5C => new(mode,pid,"engine-oil-temperature",(double)a - 40,"degC"),
            0x5E => new(mode,pid,"engine-fuel-rate",(a * 256d + b) / 20,"L/h"),
            0x0A => new(mode,pid,"fuel-pressure-gauge",a * 3d,"kPa"),
            0x0B => new(mode,pid,"intake-manifold-absolute-pressure",(double)a,"kPa"),
            0x21 => new(mode,pid,"distance-with-mil-on",a * 256d + b,"km"),
            0x22 => new(mode,pid,"fuel-rail-pressure-relative-vacuum",(a * 256d + b) * 0.079,"kPa"),
            0x23 => new(mode,pid,"fuel-rail-pressure-direct-injection",(a * 256d + b) * 10,"kPa"),
            0x2C => new(mode,pid,"commanded-egr",a * 100d / 255,"%"),
            0x2D => new(mode,pid,"egr-error",(a - 128) * 100d / 128,"%"),
            0x2E => new(mode,pid,"commanded-evaporative-purge",a * 100d / 255,"%"),
            0x30 => new(mode,pid,"warm-ups-since-dtc-clear",(double)a,"count"),
            0x31 => new(mode,pid,"distance-since-dtc-clear",a * 256d + b,"km"),
            0x32 => new(mode,pid,"evap-vapor-pressure",unchecked((short)(a * 256 + b)) / 4d,"Pa"),
            0x3C => new(mode,pid,"catalyst-temperature-bank-1-sensor-1",(a * 256d + b) / 10 - 40,"degC"),
            0x3D => new(mode,pid,"catalyst-temperature-bank-2-sensor-1",(a * 256d + b) / 10 - 40,"degC"),
            0x3E => new(mode,pid,"catalyst-temperature-bank-1-sensor-2",(a * 256d + b) / 10 - 40,"degC"),
            0x3F => new(mode,pid,"catalyst-temperature-bank-2-sensor-2",(a * 256d + b) / 10 - 40,"degC"),
            0x43 => new(mode,pid,"absolute-engine-load",(a * 256d + b) * 100 / 255,"%"),
            0x44 => new(mode,pid,"commanded-equivalence-ratio",(a * 256d + b) / 32768,"ratio"),
            0x45 => new(mode,pid,"relative-throttle-position",a * 100d / 255,"%"),
            0x47 => new(mode,pid,"absolute-throttle-position-b",a * 100d / 255,"%"),
            0x48 => new(mode,pid,"absolute-throttle-position-c",a * 100d / 255,"%"),
            0x49 => new(mode,pid,"accelerator-pedal-position-d",a * 100d / 255,"%"),
            0x4A => new(mode,pid,"accelerator-pedal-position-e",a * 100d / 255,"%"),
            0x4B => new(mode,pid,"accelerator-pedal-position-f",a * 100d / 255,"%"),
            0x4C => new(mode,pid,"commanded-throttle-actuator",a * 100d / 255,"%"),
            0x4D => new(mode,pid,"time-run-with-mil-on",a * 256d + b,"min"),
            0x4E => new(mode,pid,"time-since-dtc-clear",a * 256d + b,"min"),
            0x53 => new(mode,pid,"absolute-evap-vapor-pressure",(a * 256d + b) / 200,"kPa"),
            0x54 => new(mode,pid,"evap-vapor-pressure-offset",(a * 256d + b) - 32767,"Pa"),
            0x59 => new(mode,pid,"fuel-rail-pressure-absolute",(a * 256d + b) * 10,"kPa"),
            0x5A => new(mode,pid,"relative-accelerator-pedal-position",a * 100d / 255,"%"),
            0x5B => new(mode,pid,"hybrid-battery-pack-remaining-life",a * 100d / 255,"%"),
            0x5D => new(mode,pid,"fuel-injection-timing",((a * 256d + b) - 26880) / 128,"deg"),
            0x61 => new(mode,pid,"driver-demand-engine-torque",(double)a - 125,"%"),
            0x62 => new(mode,pid,"actual-engine-torque",(double)a - 125,"%"),
            0x63 => new(mode,pid,"engine-reference-torque",a * 256d + b,"Nm"),
            _=>null
        };
    }
    internal sealed record VehicleInfo(int InfoType,IReadOnlyList<string> Values);
    private static string? PaddedAscii(byte[] data,int start,int length){
        int end=start+length;
        while(end>start&&data[end-1]==0)end--;
        if(end==start)return null;
        for(int i=start;i<end;i++)if(data[i] is <32 or >126)return null;
        return Encoding.ASCII.GetString(data,start,end-start);
    }
    internal sealed record Readiness(bool MilOn,int ConfirmedDtcCount,bool CompressionIgnition,int ByteB,int ByteC,int ByteD,
        IReadOnlyDictionary<string,bool> SupportedMonitors);
    private static Readiness DecodeReadiness(byte[] response){
        int a=response[2],b=response[3],c=response[4],d=response[5];
        bool compression=(b&8)!=0;
        var monitors=new Dictionary<string,bool>(StringComparer.Ordinal);
        void Add(string?[] names,int support,int incomplete){
            for(int bit=0;bit<names.Length;bit++)
                if(names[bit] is string name&&(support&(1<<bit))!=0)monitors.Add(name,(incomplete&(1<<bit))==0);
        }
        Add(["misfire","fuel-system","components"],b,b>>4);
        Add(compression?
            ["nmhc-catalyst","nox-aftertreatment",null,"boost-pressure",null,"exhaust-gas-sensor","particulate-filter","egr-vvt"]:
            ["catalyst","heated-catalyst","evaporative-system","secondary-air",null,"oxygen-sensor","oxygen-sensor-heater","egr-vvt"],c,d);
        return new((a&128)!=0,a&127,compression,b,c,d,new System.Collections.ObjectModel.ReadOnlyDictionary<string,bool>(monitors));
    }
    internal sealed record SupportedPage(int Mode,int Base,IReadOnlyList<int> SupportedIds,bool HasNextPage);
    internal sealed record FreezeTrigger(bool Present,string? Dtc);
    internal sealed class Step {
        public int Sequence{get;}
        public string RequestHex{get;}
        public string? ResponseHex{get;}
        public string Status{get;}
        public long DurationMs{get;}
        internal int State{get;}
        internal byte[] Request{get;}
        internal byte[] Response{get;}
        internal Step(int seq,int state,long duration,byte[] req,byte[] res){
            if(!ReadOnly(req)||state is <1 or >5||(state<=3)!=(res.Length>0)||
               (state<=2&&Positive(req,res)!=state)||
               (state==3&&(res.Length!=3||res[0]!=0x7f||res[1]!=req[0]||res[2] is 0 or 0x78)))
                throw new IOException("CJ/1 application evidence inconsistent");
            Sequence=seq;State=state;DurationMs=duration;Request=req.ToArray();Response=res.ToArray();
            RequestHex=Convert.ToHexString(req);ResponseHex=res.Length==0?null:Convert.ToHexString(res);Status=States[state];
        }
        internal IReadOnlyList<string>? DtcCodes {
            get {
                int mode=Request[0];
                if(State!=1||mode is not (3 or 7 or 10))return null;
                var codes=new List<string>();
                const string hex="0123456789ABCDEF";
                for(int i=2;i<Response.Length;i+=2){
                    int a=Response[i],b=Response[i+1];
                    codes.Add(new string(new[]{"PCBU"[a>>6],hex[(a>>4)&3],hex[a&15],hex[b>>4],hex[b&15]}));
                }
                return codes.AsReadOnly();
            }
        }
        internal int? NegativeResponseCode=>State==3?Response[2]:null;
        internal Measurement? PidMeasurement=>State==1?DecodeMeasurement(Request,Response):null;
        internal IReadOnlyList<MonitorRecord>? MonitorRecords {
            get {
                if(State!=1||Request[0]!=6||Request[1]%32==0)return null;
                var records=new List<MonitorRecord>();
                int Word(int i)=>Response[i]*256+Response[i+1];
                for(int i=1;i<Response.Length;i+=9)
                    records.Add(new(Response[i],Response[i+1],Response[i+2],Word(i+3),Word(i+5),Word(i+7)));
                return records.AsReadOnly();
            }
        }
        internal SupportedPage? InventoryPage {
            get {
                if(State!=1||Request.Length<2||Request[1]%32!=0)return null;
                int mode=Request[0],page=Request[1],offset=mode==2?3:2;
                var ids=new List<int>();
                for(int i=0;i<31;i++)if((Response[offset+i/8]&(128>>(i%8)))!=0)ids.Add(page+i+1);
                return new(mode,page,ids.AsReadOnly(),(Response[offset+3]&1)!=0);
            }
        }
        internal FreezeTrigger? FreezeFrameTrigger {
            get {
                if(State!=1||!Request.AsSpan().SequenceEqual(new byte[]{2,2,0}))return null;
                int a=Response[3],b=Response[4];
                return a==0&&b==0?new(false,null):new(true,$"{ "PCBU"[a>>6]}{(a>>4)&3:X}{a&15:X}{b:X2}");
            }
        }
        internal Readiness? MonitorReadiness=>State==1&&Request.AsSpan().SequenceEqual(new byte[]{1,1})?DecodeReadiness(Response):null;
        internal VehicleInfo? Identification {
            get {
                if(State!=1||Request[0]!=9)return null;
                int type=Request[1];var values=new List<string>();
                if(type==2&&ObservedVin is string vin)values.Add(vin);
                else if(type==4){
                    for(int i=3;i<Response.Length;i+=16){
                        string? value=PaddedAscii(Response,i,16);if(value is null)return null;values.Add(value);
                    }
                }else if(type==6){
                    for(int i=3;i<Response.Length;i+=4)values.Add(Convert.ToHexString(Response.AsSpan(i,4)));
                }else if(type==10){
                    if(Response[2]!=1||Response[7]!='-')return null;
                    string? acronym=PaddedAscii(Response,3,4),description=PaddedAscii(Response,8,15);
                    if(acronym is null||description is null)return null;values.Add(acronym+"-"+description);
                }else return null;
                return new(type,values.AsReadOnly());
            }
        }
        internal string? ObservedVin=>State==1&&Request.AsSpan().SequenceEqual(new byte[]{9,2})?Encoding.ASCII.GetString(Response,3,17):null;
    }
    internal sealed record Summary(string End,int Flags,int Steps);
    private static bool ReadOnly(byte[] r){
        if(r.Length==0)return false;int m=r[0];
        return r.Length==1&&(m is 3 or 7 or 10)||r.Length==2&&((m is 1 or 6 or 9)||m==8&&r[1]%32==0)||r.Length==3&&m==2&&r[2]==0;
    }
    private static int Positive(byte[] req,byte[] r){
        int m=req[0];if(r.Length==0||r[0]!=m+64)return 0;
        if(m is 3 or 7 or 10){
            if(r.Length<2||r.Length!=2+2*r[1])return 0;
            for(int i=2;i<r.Length;i+=2)if(r[i]==0&&r[i+1]==0)return 0;
            return 1;
        }
        if(r.Length<2||r[1]!=req[1])return 0;
        int pid=req[1],off=m==2?3:2;
        if(m==2&&(r.Length<3||r[2]!=req[2]))return 0;
        if(pid%32==0)return r.Length==off+4&&!(pid==224&&(r[^1]&1)!=0)?1:0;
        if(m is 1 or 2){
            int width=m==2&&pid==2?2:pid==1?4:One.Contains(pid)?1:Two.Contains(pid)?2:0;
            return width>0?(r.Length==off+width?1:0):(r.Length>off?2:0);
        }
        if(m==6){
            if(r.Length<10||(r.Length-1)%9!=0)return 0;
            for(int i=1;i<r.Length;i+=9)if(r[i]!=req[1])return 0;
            return 1;
        }
        if(m==9){
            if(pid==2){
                if(r.Length!=20||r[2]!=1)return 0;
                for(int i=3;i<20;i++){int c=r[i];if(!((c>='0'&&c<='9')||(c>='A'&&c<='Z'))||c=='I'||c=='O'||c=='Q')return 0;}
                return 1;
            }
            int width=pid==4?16:pid==6?4:pid==10?20:0;
            return width>0?(r.Length>=3&&r[2]>0&&r.Length==3+r[2]*width?1:0):(r.Length>2?2:0);
        }
        return 0;
    }
    internal static Summary Verify(IReadOnlyList<Step> steps,int end,int flags){
        if(end is <1 or >5||flags is <0 or >255)throw new IOException("Invalid CJ/1 summary");
        return new Replay(steps).Verify(end,flags);
    }
    private sealed class Replay(IReadOnlyList<Step> steps){
        private int index,stop,computed;private byte[]? initial;
        private byte[]? Read(params byte[] req){
            if(stop!=0)return null;
            if(index==steps.Count){stop=1;return null;}
            Step s=steps[index++];
            if(!s.Request.AsSpan().SequenceEqual(req))throw new IOException("Unexpected CJ/1 request order");
            if(s.State==5)stop=2;return s.State==1?s.Response:null;
        }
        private byte[]? Page(int m,int p)=>m==2?Read((byte)m,(byte)p,0):Read((byte)m,(byte)p);
        private bool Inventory(int m,bool cached,bool members){
            int off=m==2?3:2;
            for(int b=0;b<=224&&stop==0;b+=32){
                byte[]? bitmap=cached&&b==0?initial:Page(m,b);if(bitmap is null)return false;
                if(members)for(int bit=0;bit<31&&stop==0;bit++){
                    if((bitmap[off+bit/8]&(128>>(bit%8)))==0)continue;
                    int p=b+bit+1;if((m is 2 or 9)&&p==2)continue;Page(m,p);
                }
                if((bitmap[off+3]&1)==0)return stop==0;
            }
            return false;
        }
        internal Summary Verify(int end,int flags){
            initial=Read(1,0);
            if(initial is not null){
                computed|=1;Read(9,2);Read(3);Read(7);Read(10);
                if(Inventory(1,true,true))computed|=2;
                byte[]? trigger=Read(2,2,0);
                if(trigger is not null){computed|=4;if(trigger[3]!=0||trigger[4]!=0){computed|=8;if(Inventory(2,false,true))computed|=16;}}
                if(Inventory(6,false,true))computed|=32;
                if(Inventory(8,false,false))computed|=64;
                if(Inventory(9,false,true))computed|=128;
            }
            int expected=stop==2?4:stop==1?(steps.Count==0&&end==4?4:3):initial is null?2:1;
            if(index!=steps.Count||expected!=end||computed!=flags)throw new IOException("CJ/1 summary contradicts exact campaign plan");
            return new Summary(Ends[end],flags,steps.Count);
        }
    }
}
