using System.Globalization;
using System.Numerics;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace CamaroDiag.J2534Bridge;

/// <summary>Offline declarative Mode 01 catalogue. No transport or executable
/// formula. Reference bytes are pinned, not authenticated or licensed by us.</summary>
internal sealed class ObdJ1979DocumentedCatalogue
{
    internal const int MaxCatalogueBytes = 4 * 1024 * 1024;
    internal const int MaxReferenceBytes = 128 * 1024 * 1024;
    internal const int MaxSignals = 64;
    internal const int MaxRows = 100000;
    private static readonly BigInteger ScaleLimit = 1000000000000L;
    internal sealed record Rational
    {
        internal BigInteger N { get; }
        internal BigInteger D { get; }
        internal Rational(BigInteger n, BigInteger d)
        {
            Check(d > 0, "Invalid denominator");
            var gcd = BigInteger.GreatestCommonDivisor(BigInteger.Abs(n), d);
            N = n / gcd; D = d / gcd;
        }
        internal Rational Add(Rational x) => new(N*x.D+x.N*D,D*x.D);
        internal Rational Multiply(BigInteger x) => new(N*x,D);
        internal int Compare(Rational x) => (N*x.D).CompareTo(x.N*D);
        internal object Json() => new { numerator=N.ToString(CultureInfo.InvariantCulture),
            denominator=D.ToString(CultureInfo.InvariantCulture) };
    }
    internal sealed record Bits(int Offset, int Width, string Order)
    {
        internal BigInteger Extract(ReadOnlySpan<byte> data)
        {
            Check(Offset+Width<=data.Length*8,"Signal outside received payload");
            BigInteger result=0;
            if(Order=="LE_BYTES")
            {
                for(int i=Width/8-1;i>=0;i--) result=(result<<8)|data[Offset/8+i];
            }
            else
            {
                for(int i=Offset;i<Offset+Width;i++)
                    result=(result<<1)|((data[i/8]>>(7-i%8))&1);
            }
            return result;
        }
    }
    internal sealed record Presence(Bits Bits,BigInteger Mask,BigInteger EqualsValue)
    {
        internal bool Matches(ReadOnlySpan<byte> data)=>(Bits.Extract(data)&Mask)==EqualsValue;
    }
    internal sealed record Signal(string Name,string Unit,Bits Bits,bool Signed,Rational Scale,Rational Offset,
        IReadOnlySet<BigInteger> Unavailable,Presence? Presence,Rational? Minimum,Rational? Maximum,
        IReadOnlyDictionary<BigInteger,string> Enumeration,string Reference)
    {
        internal Dictionary<string,object?> Project(ReadOnlySpan<byte> data)
        {
            var row=new Dictionary<string,object?> {
                ["name"]=Name,["unit"]=Unit,["reference"]=Reference,["status"]=null,
                ["raw"]=null,["value"]=null,["enumeration"]=null
            };
            if(Presence is not null&&!Presence.Matches(data))
            {
                row["status"]="NOT_PRESENT";return row;
            }
            var raw=Bits.Extract(data);
            row["raw"]=raw.ToString(CultureInfo.InvariantCulture);
            if(Unavailable.Contains(raw))row["status"]="UNAVAILABLE";
            else if(Enumeration.Count!=0)
            {
                row["enumeration"]=Enumeration.GetValueOrDefault(raw);
                row["status"]=Enumeration.ContainsKey(raw)?"ENUMERATED":"UNDEFINED_ENUMERATION";
            }
            else
            {
                var signed=raw;
                if(Signed&&(raw&(BigInteger.One<<(Bits.Width-1)))!=0)signed-=BigInteger.One<<Bits.Width;
                var value=Scale.Multiply(signed).Add(Offset);
                row["value"]=value.Json();
                row["status"]=Minimum is not null&&(value.Compare(Minimum)<0||value.Compare(Maximum!)>0)
                    ?"OUTSIDE_DECLARED_RANGE":"DECODED";
            }
            return row;
        }
    }
    internal sealed record Definition(int Pid,int Width,string[] Protocols,string Reference,Signal[] Signals);
    internal string Edition {get;}
    internal string ReferenceName {get;}
    internal string ReferenceSha256 {get;}
    internal string CatalogueSha256 {get;}
    private readonly Dictionary<(int,string),Definition> index=new();
    private readonly Definition[] definitions;
    private ObdJ1979DocumentedCatalogue(string edition,string name,string digest,string catalogueSha,Definition[] rows)
    {
        Edition=edition;ReferenceName=name;ReferenceSha256=digest;CatalogueSha256=catalogueSha;
        definitions=rows;
        foreach(var row in rows)
            foreach(var protocol in row.Protocols)
                Check(index.TryAdd((row.Pid,protocol),row),"Ambiguous PID/protocol definition");
    }
    internal static void Check(bool condition,string detail)
    {
        if(!condition)throw new InvalidDataException(detail);
    }
    internal static string Sha(ReadOnlySpan<byte> data)=>Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();
    internal static string Digest(string value)
    {
        Check(Regex.IsMatch(value??"", @"\A[0-9a-f]{64}\z"),"Invalid SHA-256");
        return value!;
    }
    // Reject duplicate properties instead of silently using the last value.
    internal static void Unique(JsonElement node)
    {
        if(node.ValueKind==JsonValueKind.Object)
        {
            var seen=new HashSet<string>(StringComparer.Ordinal);
            foreach(var item in node.EnumerateObject())
            {
                Check(seen.Add(item.Name),"Duplicate JSON property");Unique(item.Value);
            }
        }
        else if(node.ValueKind==JsonValueKind.Array)
            foreach(var item in node.EnumerateArray())Unique(item);
    }
    private static void Fields(JsonElement node,string[] required,params string[] optional)
    {
        Check(node.ValueKind==JsonValueKind.Object,"Expected object");
        var keys=node.EnumerateObject().Select(x=>x.Name).ToHashSet(StringComparer.Ordinal);
        Check(required.All(keys.Contains)&&keys.All(x=>required.Contains(x)||optional.Contains(x)),
            "Missing or unknown catalogue field");
    }
    internal static string Label(JsonElement node,int max=256)
    {
        Check(node.ValueKind==JsonValueKind.String,"Expected text");
        var s=node.GetString()!;
        Check(s.Length>0&&s.EnumerateRunes().Count()<=max&&s==s.Trim()&&
            !s.Any(c=>c<32||c==127),"Invalid catalogue text");
        return s;
    }
    internal static BigInteger Integer(JsonElement node,BigInteger low,BigInteger high)
    {
        Check(node.ValueKind==JsonValueKind.Number,"Expected integer");
        var text=node.GetRawText();
        Check(text.Length<=32&&Regex.IsMatch(text,@"\A-?(0|[1-9][0-9]*)\z"),"Expected integer");
        var n=BigInteger.Parse(text,CultureInfo.InvariantCulture);
        Check(n>=low&&n<=high,"Integer out of bounds");
        return n;
    }
    internal static int Int(JsonElement node,int low,int high)=>(int)Integer(node,low,high);
    internal static bool Bool(JsonElement node)
    {
        Check(node.ValueKind is JsonValueKind.True or JsonValueKind.False,"Expected boolean");
        return node.GetBoolean();
    }
    internal static JsonElement[] Array(JsonElement node,int min,int max)
    {
        Check(node.ValueKind==JsonValueKind.Array,"Expected array");
        Check(node.GetArrayLength()>=min&&node.GetArrayLength()<=max,"Array length out of bounds");
        return node.EnumerateArray().ToArray();
    }
    private static JsonElement[] OptionalArray(JsonElement node,string field,int max)=>
        node.TryGetProperty(field,out var value)?Array(value,0,max):[];
    private static Rational Fraction(JsonElement node)
    {
        var values=Array(node,2,2);
        return new Rational(Integer(values[0],-ScaleLimit,ScaleLimit),Integer(values[1],1,ScaleLimit));
    }
    private static Bits ParseBits(JsonElement node,int payloadWidth)
    {
        Fields(node,["offset","width","order"]);
        int start=Int(node.GetProperty("offset"),0,payloadWidth*8-1);
        int width=Int(node.GetProperty("width"),1,64);
        string order=Label(node.GetProperty("order"));
        Check(start+width<=payloadWidth*8&&order is "MSB0" or "LE_BYTES","Invalid bit scope");
        Check(order!="LE_BYTES"||start%8==0&&width%8==0,"Unaligned little-endian signal");
        return new Bits(start,width,order);
    }
    private static BigInteger Encoded(JsonElement node,int width)=>Integer(node,0,(BigInteger.One<<width)-1);
    private static Presence ParsePresence(JsonElement node,int width)
    {
        Fields(node,["bits","mask","equals"]);
        var bits=ParseBits(node.GetProperty("bits"),width);
        var mask=Integer(node.GetProperty("mask"),1,(BigInteger.One<<bits.Width)-1);
        var expected=Encoded(node.GetProperty("equals"),bits.Width);
        Check((expected&~mask)==0,"Presence value outside mask");
        return new(bits,mask,expected);
    }
    private static Signal ParseSignal(JsonElement node,int width)
    {
        Fields(node,["name","unit","bits","signed","scale","offset","reference"],
            "unavailable","presence","range","enumeration");
        var bits=ParseBits(node.GetProperty("bits"),width);
        var signed=Bool(node.GetProperty("signed"));
        var unavailable=new HashSet<BigInteger>();
        foreach(var x in OptionalArray(node,"unavailable",256))
            Check(unavailable.Add(Encoded(x,bits.Width)),"Duplicate unavailable value");
        var enums=new Dictionary<BigInteger,string>();
        foreach(var x in OptionalArray(node,"enumeration",256))
        {
            Fields(x,["raw","label"]);var value=Encoded(x.GetProperty("raw"),bits.Width);
            Check(!unavailable.Contains(value)&&enums.TryAdd(value,Label(x.GetProperty("label"))),
                "Ambiguous enumeration");
        }
        var scale=Fraction(node.GetProperty("scale"));var offset=Fraction(node.GetProperty("offset"));
        Rational? min=null,max=null;
        if(node.TryGetProperty("range",out var limits)&&limits.ValueKind!=JsonValueKind.Null)
        {
            var values=Array(limits,2,2);min=Fraction(values[0]);max=Fraction(values[1]);
            Check(min.Compare(max)<=0,"Inverted engineering range");
        }
        Check(enums.Count==0?scale.N!=0:!signed&&scale.N==scale.D&&offset.N==0&&min is null,
            "Incompatible numeric or enumeration definition");
        return new(Label(node.GetProperty("name")),Label(node.GetProperty("unit"),64),bits,signed,scale,offset,
            unavailable,node.TryGetProperty("presence",out var presence)?ParsePresence(presence,width):null,
            min,max,enums,Label(node.GetProperty("reference")));
    }
    private static Definition ParseDefinition(JsonElement node)
    {
        Fields(node,["pid","payload_bytes","protocols","reference","signals"]);
        int pid=Int(node.GetProperty("pid"),1,255),width=Int(node.GetProperty("payload_bytes"),1,4093);
        Check(pid%32!=0,"Support bitmap is not a measurement");
        var protocols=Array(node.GetProperty("protocols"),1,2).Select(x=>Label(x)).ToArray();
        Check(protocols.All(x=>x is "CAN" or "LEGACY")&&protocols.Distinct().Count()==protocols.Length&&
            (!protocols.Contains("LEGACY")||width<=5),"Invalid protocol scope");
        var signals=Array(node.GetProperty("signals"),1,MaxSignals).Select(x=>ParseSignal(x,width)).ToArray();
        Check(signals.Select(x=>x.Name).Distinct().Count()==signals.Length,"Duplicate signal name");
        return new(pid,width,protocols,Label(node.GetProperty("reference")),signals);
    }
    internal static ObdJ1979DocumentedCatalogue Parse(byte[] raw,string expectedReferenceDigest)
    {
        Check(raw.Length is >0 and <=MaxCatalogueBytes,"Catalogue exceeds byte limit");
        using var json=JsonDocument.Parse(raw,new JsonDocumentOptions{MaxDepth=32});
        var root=json.RootElement;Unique(root);
        Fields(root,["schema","edition","reference_name","reference_sha256","definitions"]);
        Check(Label(root.GetProperty("schema"))=="camarodiag-j1979-pid-catalogue-v1","Unsupported catalogue schema");
        string sha=Digest(Label(root.GetProperty("reference_sha256")));
        Check(sha==Digest(expectedReferenceDigest),"Reference digest differs from operator pin");
        var rows=Array(root.GetProperty("definitions"),1,247).Select(ParseDefinition).ToArray();
        return new(Label(root.GetProperty("edition")),Label(root.GetProperty("reference_name")),sha,Sha(raw),rows);
    }
    internal object[] Inventory()=>definitions.OrderBy(x=>x.Pid).ThenBy(x=>string.Join(",",x.Protocols),StringComparer.Ordinal)
        .Select(x=>(object)new {pid=x.Pid,payload_bytes=x.Width,protocols=x.Protocols,reference=x.Reference,
            signals=x.Signals.Select(s=>s.Name).ToArray()}).ToArray();
    internal Dictionary<string,object?> Project(int pid,string protocol,int cycle,long elapsed,string status,string? response,
        ref int signalRows)
    {
        Check(protocol is "CAN" or "LEGACY","Invalid projection transport");
        var row=new Dictionary<string,object?>{
            ["cycle"]=cycle,["pid"]=pid,["elapsed_ms"]=elapsed,["source_status"]=status,
            ["response_hex"]=response,["projection_status"]=null,["signals"]=System.Array.Empty<object>()};
        if(status!="RAW")row["projection_status"]="NO_CORRELATED_RAW_PAYLOAD";
        else
        {
            Check(response is not null&&response.Length>=6&&response.Length<=8190&&
                response.Length%2==0,"Invalid raw response width");
            var payload=Convert.FromHexString(response!);
            Check(payload[0]==0x41&&payload[1]==pid&& (protocol!="LEGACY"||payload.Length<=7),
                "Uncorrelated response envelope");
            if(!index.TryGetValue((pid,protocol),out var def))row["projection_status"]="NO_DOCUMENTED_DEFINITION";
            else if(payload.Length!=def.Width+2)row["projection_status"]="DOCUMENTED_WIDTH_MISMATCH";
            else
            {
                signalRows+=def.Signals.Length;Check(signalRows<=MaxRows,"Projection signal limit exceeded");
                row["projection_status"]="PROJECTED";row["reference"]=def.Reference;
                var data=payload.AsSpan(2);var signals=new List<Dictionary<string,object?>>();
                foreach(var signal in def.Signals)signals.Add(signal.Project(data));
                row["signals"]=signals;
            }
        }
        return row;
    }
}
