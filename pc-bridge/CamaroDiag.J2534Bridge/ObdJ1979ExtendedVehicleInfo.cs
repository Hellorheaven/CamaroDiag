using System.Text;
using System.Globalization;

namespace CamaroDiag.J2534Bridge;

/// <summary>Classic Mode 09, J1979DA October 2011 G8–G15. Complete
/// record lengths are checked independently of physical source attribution.
/// Numerical ratios are not regulatory or monitor success verdicts.</summary>
internal static class ObdJ1979ExtendedVehicleInfo
{
    internal sealed record Monitor(string Name,int Numerator,int Denominator)
    {
        public double? Ratio=>Denominator==0?null:(double)Numerator/Denominator;
    }
    internal sealed record Record(int InfoType,IReadOnlyList<string> Values,
        int? GeneralDenominator=null,int? IgnitionCycles=null,int? CounterCount=null,
        IReadOnlyList<Monitor>? Monitors=null);
    private static readonly string[] Spark = ["catalyst-bank-1", "catalyst-bank-2", "oxygen-sensor-bank-1", "oxygen-sensor-bank-2", "egr-vvt", "secondary-air", "evap", "secondary-oxygen-bank-1", "secondary-oxygen-bank-2"];
    private static readonly string[] Diesel = ["nmhc-catalyst", "nox-scr", "nox-adsorber", "particulate-filter", "exhaust-gas-sensor", "egr-vvt", "boost-pressure", "fuel-system"];
    internal static bool IsExtended(int type)=>type is 8 or 11 or 13 or 15;
    internal static bool IsLegacyCount(int type)=>type is 1 or 3 or 5 or 7 or 9 or 12 or 14;
    internal static byte CountType(byte type)=>(byte)(type==11?7:type-1);

    private static Record Counters(int type,ReadOnlySpan<byte> bytes)
    {
        if(type==8 && bytes.Length is not (32 or 40) ||
            type==11 && bytes.Length is not (32 or 36) || type is not (8 or 11))
            throw new InvalidDataException("Incorrect IPT counter count for InfoType.");
        var counters=new int[bytes.Length/2];
        for(int i=0;i<counters.Length;i++)
            counters[i]=(bytes[2*i]<<8)|bytes[2*i+1];
        var names=type==8?Spark:Diesel;
        var monitors=new List<Monitor>();
        for(int i=0;i<(counters.Length-2)/2;i++)
            monitors.Add(new(names[i],counters[2+2*i],counters[3+2*i]));
        return new(type,[],counters[0],counters[1],counters.Length,monitors.AsReadOnly());
    }

    private static string Ascii(ReadOnlySpan<byte> bytes)
    {
        if(bytes.IsEmpty || bytes.IndexOfAnyExceptInRange((byte)32,(byte)126)>=0)
            throw new InvalidDataException("Invalid identification text or padding.");
        return Encoding.ASCII.GetString(bytes);
    }

    private static Record Text(int type,ReadOnlySpan<byte> bytes)
    {
        string value;
        if(type==10){
            if(bytes.Length!=20||bytes[4]!=(byte)'-')
                throw new InvalidDataException("Malformed ECU name.");
            value=Ascii(bytes[..4].TrimEnd((byte)0))+"-"+Ascii(bytes[5..].TrimEnd((byte)0));
        }else if(type is 13 or 15){
            if(bytes.Length!=17)throw new InvalidDataException("Wrong identification width.");
            value=Ascii(bytes.TrimStart((byte)0));
        }else throw new InvalidDataException("Unknown extended identification type.");
        return new(type,[value]);
    }

    internal static Record Can(int type,ReadOnlySpan<byte> bytes)
    {
        if(bytes.Length<3||bytes[0]!=0x49||bytes[1]!=type)
            throw new InvalidDataException("Unrelated Mode 09 response.");
        if(type is 8 or 11){
            if(bytes.Length!=3+2*bytes[2])throw new InvalidDataException("Incorrect IPT item count.");
            return Counters(type,bytes[3..]);
        }
        if(type is 13 or 15 && bytes[2]==1)return Text(type,bytes[3..]);
        throw new InvalidDataException("Invalid extended InfoType.");
    }

    internal static Record Legacy(int type,int count,IReadOnlyList<byte[]> rows)
    {
        if(count is <1 or >10||rows.Count!=count)
            throw new InvalidDataException("Incorrect legacy message count.");
        var bytes=new byte[count*4];
        for(int i=0;i<count;i++){
            var row=rows[i];
            if(row.Length!=7||row[0]!=0x49||row[1]!=type||row[2]!=i+1)
                throw new InvalidDataException("Invalid legacy fragment sequence.");
            row.AsSpan(3).CopyTo(bytes.AsSpan(4*i));
        }
        if(type is 8 or 11)return Counters(type,bytes);
        if(type==10&&count==5)return Text(type,bytes);
        if(type is 13 or 15 && count==5 && bytes.AsSpan(0,3).SequenceEqual(new byte[3]))
            return Text(type,bytes.AsSpan(3));
        throw new InvalidDataException("Invalid extended legacy InfoType.");
    }

    internal static string Format(Record record)
    {
        if(record.CounterCount is null)return string.Join(", ",record.Values);
        var monitors=record.Monitors??[];
        return $"IPT type={record.InfoType:X2} OBD={record.GeneralDenominator} IGN={record.IgnitionCycles} "+
            string.Join("; ",monitors.Select(m=>$"{m.Name}={m.Numerator}/{m.Denominator} ratio="+
                (m.Ratio?.ToString("0.######",CultureInfo.InvariantCulture)??"N/A")));
    }
}
