namespace CamaroDiag.J2534Bridge;

/// <summary>Non-numeric status views of selected J1979 Mode 01 PIDs.
/// The ECU's reported state is not evidence of a successful actuation.</summary>
internal static class ObdJ1979Status
{
    internal sealed record State(int Pid,string Name,long RawEncoded,string Code);
    internal sealed record Record(string RequestHex,IReadOnlyList<State> States);
    private sealed record Field(string Name,int Offset,int Shift,int Mask,
        IReadOnlyDictionary<int,string> Codes,int Support=-1);
    private sealed record Definition(int Width,(int Offset,int Mask)[] Reserved,Field[] Fields);

    private static Dictionary<int,string> Enumerate(params string[] codes)=>
        codes.Select((code,index)=>(code,index)).ToDictionary(x=>x.index,x=>x.code);
    private static readonly Dictionary<int,string> On=Enumerate("INACTIVE","ACTIVE");
    private static readonly Dictionary<int,string> Present=Enumerate("ABSENT","PRESENT");
    private static readonly Dictionary<int,string> Loop=Enumerate("RESERVED","OPEN_LOOP","CLOSED_LOOP","FAULT");
    private static readonly Dictionary<int,string> FuelLoop=new(){
        [0]="UNAVAILABLE",[1]="OPEN_WARMUP",[2]="CLOSED_FEEDBACK",[4]="OPEN_DRIVING",
        [8]="OPEN_FAULT",[16]="CLOSED_SENSOR_FAULT"};
    private static readonly Dictionary<int,string> Fuel=Enumerate("UNAVAILABLE","GASOLINE","METHANOL",
        "ETHANOL","DIESEL","LPG","CNG","PROPANE","ELECTRIC","BIFUEL_GASOLINE","BIFUEL_METHANOL",
        "BIFUEL_ETHANOL","BIFUEL_LPG","BIFUEL_CNG","BIFUEL_PROPANE","BIFUEL_ELECTRIC",
        "BIFUEL_COMBINED","HYBRID_GASOLINE","HYBRID_ETHANOL","HYBRID_DIESEL","HYBRID_ELECTRIC",
        "HYBRID_COMBINED","HYBRID_REGENERATION","BIFUEL_DIESEL");
    private static readonly Dictionary<int,string> Obd=new(){
        [1]="OBD_II",[2]="OBD",[3]="OBD_AND_OBD_II",[4]="OBD_I",[5]="NOT_COMPLIANT",
        [6]="EOBD",[7]="EOBD_OBD_II",[8]="EOBD_OBD",[9]="EOBD_OBD_OBD_II",
        [10]="JOBD",[11]="JOBD_OBD_II",[12]="JOBD_EOBD",[13]="JOBD_EOBD_OBD_II",
        [17]="EMD",[18]="EMD_PLUS",[19]="HD_OBD_PARTIAL",[20]="HD_OBD",[21]="WWH_OBD",
        [23]="HD_EOBD_I_NO_NOX",[24]="HD_EOBD_I_NOX",[25]="HD_EOBD_II_NO_NOX",
        [26]="HD_EOBD_II_NOX",[28]="BR_OBD_I",[29]="BR_OBD_II",[30]="KOBD",
        [31]="INDIA_OBD_I",[32]="INDIA_OBD_II",[33]="HD_EOBD_VI"};
    private static readonly Dictionary<int,string> Mi=new(){
        [0]="OFF",[1]="ON_DEMAND",[2]="SHORT",[3]="CONTINUOUS",[14]="ERROR",[15]="UNAVAILABLE"};
    private static readonly Dictionary<int,string> Induce=Enumerate("INACTIVE","ENABLED","ACTIVE","UNSUPPORTED");
    private static Field[] Oxygen(int sensorsPerBank)=>Enumerable.Range(0,8)
        .Select(i=>new Field($"oxygen-bank-{i/sensorsPerBank+1}-sensor-{i%sensorsPerBank+1}",0,i,1,Present)).ToArray();
    private static Field[] Nte(string prefix)=>new[]{"inside","outside","carve-out","deficiency"}
        .Select((name,i)=>new Field(prefix+"-nte-"+name,0,i,1,On)).ToArray();
    private static Field[] FuelControl()=>Enumerable.Range(0,8)
        .Select(i=>new Field($"fuel-{new[]{"pressure","quantity","timing","idle-balance"}[i%4]}-{i/4+1}",
            1,i,1,Enumerate("NOT_CLOSED_LOOP","CLOSED_LOOP"),i)).ToArray();

    private static readonly Dictionary<int,Definition> Definitions=new(){
        [0x03]=new(2,[],[new("fuel-system-1",0,0,255,FuelLoop),new("fuel-system-2",1,0,255,FuelLoop)]),
        [0x12]=new(1,[],[new("secondary-air",0,0,255,new Dictionary<int,string>{
            [1]="UPSTREAM",[2]="DOWNSTREAM",[4]="ATMOSPHERE_OR_OFF",[8]="DIAGNOSTIC"})]),
        [0x13]=new(1,[],Oxygen(4)),
        [0x1C]=new(1,[],[new("declared-obd-standard",0,0,255,Obd)]),
        [0x1D]=new(1,[],Oxygen(2)),
        [0x1E]=new(1,[(0,0xFE)],[new("power-take-off",0,0,1,On)]),
        [0x51]=new(1,[],[new("current-fuel-type",0,0,255,Fuel)]),
        [0x70]=new(10,[(0,0xC0),(9,0xF0)],[new("boost-control-a",9,0,3,Loop,2),
            new("boost-control-b",9,2,3,Loop,5)]),
        [0x71]=new(6,[(0,0xC0),(5,0xF0)],[new("vgt-control-a",5,0,3,Loop,2),
            new("vgt-control-b",5,2,3,Loop,5)]),
        [0x7D]=new(1,[(0,0xF0)],Nte("nox")),
        [0x7E]=new(1,[(0,0xF0)],Nte("pm")),
        [0x8B]=new(7,[(0,0x80),(1,0xF0)],[new("dpf-regeneration",1,0,1,On,0),
            new("dpf-regeneration-type",1,1,1,Enumerate("PASSIVE","ACTIVE"),1),
            new("nox-adsorber-regeneration",1,2,1,On,2),new("nox-adsorber-desulfurization",1,3,1,On,3)]),
        [0x8F]=new(7,[(0,0xF0),(1,0xFC),(4,0xFC)],[
            new("pm-sensor-bank-1-measuring",1,0,1,On,0),new("pm-sensor-bank-1-regenerating",1,1,1,On,0),
            new("pm-sensor-bank-2-measuring",4,0,1,On,2),new("pm-sensor-bank-2-regenerating",4,1,1,On,2)]),
        [0x90]=new(3,[(0,0x80)],[new("vehicle-mi",0,2,15,Mi),
            new("emissions-readiness",0,6,1,Enumerate("COMPLETE","INCOMPLETE")),
            new("mi-display-strategy",0,0,3,new Dictionary<int,string>{
                [0]="NONDISCRIMINATORY",[1]="DISCRIMINATORY",[3]="UNAVAILABLE"})]),
        [0x91]=new(5,[(0,0xF0)],[new("ecu-mi",0,0,15,Mi)]),
        [0x92]=new(2,[],FuelControl()),
        [0x94]=new(12,[(0,0xC0),(1,0x80)],[new("nox-warning",1,0,1,On,0),
            new("inducement-level-1",1,1,3,Induce,0),new("inducement-level-2",1,3,3,Induce,0),
            new("inducement-level-3",1,5,3,Induce,0)])
    };

    internal static int? Width(int pid)=>Definitions.TryGetValue(pid,out var d)?d.Width:null;
    internal static IReadOnlyList<State>? DecodeData(int pid,ReadOnlySpan<byte> data)
    {
        if(!Definitions.TryGetValue(pid,out var definition)||data.Length!=definition.Width)return null;
        foreach(var (offset,mask) in definition.Reserved)
            if((data[offset]&mask)!=0)return null;
        var states=new List<State>();
        foreach(var field in definition.Fields){
            if(field.Support>=0&&(data[0]&(1<<field.Support))==0)continue;
            int raw=(data[field.Offset]>>field.Shift)&field.Mask;
            states.Add(new(pid,field.Name,raw,field.Codes.GetValueOrDefault(raw,"UNKNOWN")));
        }
        return states.AsReadOnly();
    }

    internal static IReadOnlyList<State>? DecodeResponse(ReadOnlySpan<byte> request,ReadOnlySpan<byte> response)
    {
        if(request.Length!=2||request[0]!=1||response.Length<2||
            response[0]!=0x41||response[1]!=request[1])return null;
        return DecodeData(request[1],response[2..]);
    }

    internal static Record? TryRecord(string requestHex,string? responseHex,string status)
    {
        if(status is not ("RAW" or "PASSED")||requestHex.Length!=4||responseHex is null||
            responseHex.Length>28)return null;
        try{
            var states=DecodeResponse(Convert.FromHexString(requestHex),Convert.FromHexString(responseHex));
            return states is null?null:new(requestHex,states);
        }catch(FormatException){return null;}
    }

    internal static string Format(IReadOnlyList<State> states)=>states.Count==0?
        "No supported status field":string.Join("; ",states.Select(s=>$"{s.Name}={s.Code} (0x{s.RawEncoded:X})"));
}
