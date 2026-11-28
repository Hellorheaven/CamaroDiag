using System.Globalization;

namespace CamaroDiag.J2534Bridge;

/// <summary>Historical non-CAN monitor data. OEM component IDs and raw
/// limits remain visible; comparison never establishes monitor completion.</summary>
internal static class ObdJ1979LegacyMonitorValues
{
    internal sealed record Scale(string Definition,string Name,string Unit,double Value,
        double? Minimum,double? Maximum,string NumericRelation);
    internal sealed record Record(int Mode,int TestId,int? SensorMask,string EncodedDataHex,int ValueEncoded,
        int? MinimumEncoded=null,int? MaximumEncoded=null,int? ComponentAndLimitEncoded=null,
        int? ComponentId=null,string? LimitType=null,int? LimitEncoded=null,string? EncodedRelation=null,
        Scale? Scaled=null);
    internal sealed record Projected(string RequestHex,int RecordIndex,Record Record);

    private static (string Name,string Unit,double Factor)? Definition(int tid)=>tid switch
    {
        1=>("rich-lean-threshold","V",0.005),
        2=>("lean-rich-threshold","V",0.005),
        3=>("switch-low-voltage","V",0.005),
        4=>("switch-high-voltage","V",0.005),
        5=>("rich-lean-switch-time","s",0.004),
        6=>("lean-rich-switch-time","s",0.004),
        7=>("cycle-minimum-voltage","V",0.005),
        8=>("cycle-maximum-voltage","V",0.005),
        9=>("transition-interval","s",0.04),
        10=>("sensor-period","s",0.04),
        _=>null
    };

    internal static Record? Decode(ReadOnlySpan<byte> request,ReadOnlySpan<byte> response)
    {
        if(request.Length is not (2 or 3)||request[0] is not (5 or 6))return null;
        int mode=request[0],tid=request[1];
        if(tid%32==0||request.Length!=(mode==5?3:2))return null;
        if(mode==5){
            int mask=request[2];
            if(mask is not (1 or 2 or 4 or 8 or 16 or 32 or 64 or 128)||
               response.Length!=(tid is >=1 and <=4?4:6)||
               response[0]!=0x45||response[1]!=tid||response[2]!=mask)return null;
            int value=response[3];
            int? minimum=response.Length==6?response[4]:null;
            int? maximum=response.Length==6?response[5]:null;
            Scale? scaled=null;
            if(Definition(tid) is {} d){
                var relation=minimum is null||maximum is null?"NOT_APPLICABLE":
                    minimum>maximum?"INVERTED_LIMITS":value<minimum?"BELOW":value>maximum?"ABOVE":"WITHIN";
                scaled=new("ISO15031-5:2006-ANNEX-C",d.Name,d.Unit,value*d.Factor,
                    minimum*d.Factor,maximum*d.Factor,relation);
            }
            return new(5,tid,mask,Convert.ToHexString(response[3..]),value,minimum,maximum,Scaled:scaled);
        }
        if(response.Length!=7||response[0]!=0x46||response[1]!=tid)return null;
        int component=response[2],encoded=(response[3]<<8)|response[4],limit=(response[5]<<8)|response[6];
        return new(6,tid,null,Convert.ToHexString(response[2..]),encoded,
            ComponentAndLimitEncoded:component,ComponentId:component&127,
            LimitType:(component&128)!=0?"MINIMUM":"MAXIMUM",LimitEncoded:limit,
            EncodedRelation:encoded<limit?"BELOW":encoded>limit?"ABOVE":"EQUAL");
    }

    internal static IReadOnlyList<Projected> Project(IEnumerable<ObdJ1979LegacyScan.Step> steps,string source)
    {
        var results=new List<Projected>();
        foreach(var step in steps){
            if(step.Status!="PASSED"||step.Responses.Count>128||
                step.RequestHex.Length is not (4 or 6))continue;
            var group=new List<Projected>();
            try{
                byte[] request=Convert.FromHexString(step.RequestHex);
                foreach(var frame in step.Responses){
                    if(frame.Source!=source||frame.PayloadHex.Length>14)break;
                    var record=Decode(request,Convert.FromHexString(frame.PayloadHex));
                    if(record is null)break;
                    group.Add(new(step.RequestHex,group.Count,record));
                }
            }catch(FormatException){continue;}
            if(group.Count==step.Responses.Count)results.AddRange(group);
        }
        return results;
    }

    internal static object Evidence(Projected item)
    {
        var record=item.Record;
        var fields=new Dictionary<string,object?>{
            ["request_hex"]=item.RequestHex,["record_index"]=item.RecordIndex,
            ["mode"]=record.Mode,["test_id"]=record.TestId,["sensor_mask"]=record.SensorMask,
            ["encoded_data_hex"]=record.EncodedDataHex,["value_encoded"]=record.ValueEncoded,
            ["monitor_verdict"]=null,["scaled"]=record.Scaled is {} s?new {
                definition=s.Definition,name=s.Name,unit=s.Unit,value=s.Value,
                minimum=s.Minimum,maximum=s.Maximum,numeric_relation=s.NumericRelation}:null
        };
        if(record.Mode==5){
            fields["minimum_encoded"]=record.MinimumEncoded;
            fields["maximum_encoded"]=record.MaximumEncoded;
        }else{
            fields["component_and_limit_encoded"]=record.ComponentAndLimitEncoded;
            fields["component_id"]=record.ComponentId;
            fields["limit_type"]=record.LimitType;
            fields["limit_encoded"]=record.LimitEncoded;
            fields["encoded_relation"]=record.EncodedRelation;
        }
        return fields;
    }

    internal static string Format(Record record)
    {
        if(record.Scaled is {} s)
            return record.EncodedDataHex+" · "+s.Name+": "+s.Value.ToString("G",CultureInfo.InvariantCulture)
                +" "+s.Unit+(s.Minimum is {} low&&s.Maximum is {} high?
                    FormattableString.Invariant($" [{low}–{high}]"):"");
        return record.Mode==6?
            FormattableString.Invariant($"{record.EncodedDataHex} · CID={record.ComponentId:X2} {record.LimitType} RAW={record.ValueEncoded:X4} LIMIT_RAW={record.LimitEncoded:X4}"):
            record.EncodedDataHex;
    }
}
