using System.Globalization;
using System.Text.Json;

namespace CamaroDiagConnectors.Windows;

/// <summary>Strict cross-host verification of the CL/1 scalar JSON contract.
/// Only engineering quantities may vary in their finite decimal representation.
/// No caller-supplied report is treated as an authority for ECU identity.</summary>
internal static class J1979PortableScalarVerification
{
    private static readonly HashSet<string> Continuous=new(StringComparer.Ordinal)
    {
        "number","minimum","maximum","mean","last_verified_value"
    };
    private const int MaxNodes=2_000_000;
    private const int MaxBytes=64*1024*1024;

    internal static void Verify(ReadOnlyMemory<byte> expected,ReadOnlyMemory<byte> supplied)
    {
        if(expected.Length is <=0 or >MaxBytes||supplied.Length is <=0 or >MaxBytes)
            throw new InvalidDataException("Scalar JSON exceeds byte limit");
        var options=new JsonDocumentOptions{MaxDepth=64,AllowTrailingCommas=false,CommentHandling=JsonCommentHandling.Disallow};
        try
        {
            using var canonical=JsonDocument.Parse(expected,options);
            using var candidate=JsonDocument.Parse(supplied,options);
            int nodes=0;
            if(!Same(canonical.RootElement,candidate.RootElement,"",ref nodes))
                throw new InvalidDataException("Projection differs from independent CL/1 scalar replay");
        }
        catch(JsonException e){throw new InvalidDataException("Invalid scalar JSON",e);}
    }
    private static bool Same(JsonElement expected,JsonElement actual,string field,ref int nodes)
    {
        if(++nodes>MaxNodes)throw new InvalidDataException("Scalar JSON node limit");
        if(expected.ValueKind!=actual.ValueKind)return false;
        switch(expected.ValueKind)
        {
            case JsonValueKind.Object:
            {
                var fields=new Dictionary<string,JsonElement>(StringComparer.Ordinal);
                foreach(var p in actual.EnumerateObject())
                {
                    if(p.Name.Length>256||!fields.TryAdd(p.Name,p.Value))
                        throw new InvalidDataException("Repeated or oversized scalar JSON property");
                    if(fields.Count>128)throw new InvalidDataException("Scalar JSON object field limit");
                }
                int count=0;
                foreach(var p in expected.EnumerateObject())
                {
                    count++;
                    if(!fields.TryGetValue(p.Name,out var value)||!Same(p.Value,value,p.Name,ref nodes))return false;
                }
                return count==fields.Count;
            }
            case JsonValueKind.Array:
            {
                if(expected.GetArrayLength()!=actual.GetArrayLength())return false;
                var a=expected.EnumerateArray();var b=actual.EnumerateArray();
                while(a.MoveNext())
                    if(!b.MoveNext()||!Same(a.Current,b.Current,field,ref nodes))return false;
                return !b.MoveNext();
            }
            case JsonValueKind.Number:
            {
                var e=expected.GetRawText();var a=actual.GetRawText();
                if(e.Length>64||a.Length>64)return false;
                if(Continuous.Contains(field))
                {
                    if(!expected.TryGetDouble(out var x)||!actual.TryGetDouble(out var y)||
                        !double.IsFinite(x)||!double.IsFinite(y))return false;
                    return Math.Abs(x-y)<=Math.Max(1e-9,Math.Abs(x)*1e-9);
                }
                // Strict JSON integer representation, not a decimal or exponent
                // that happens to round to the same numerical identifier.
                return e==a&&long.TryParse(a,NumberStyles.AllowLeadingSign,CultureInfo.InvariantCulture,out _);
            }
            case JsonValueKind.String:return string.Equals(expected.GetString(),actual.GetString(),StringComparison.Ordinal);
            case JsonValueKind.True:case JsonValueKind.False:case JsonValueKind.Null:return true;
            default:return false;
        }
    }
}
