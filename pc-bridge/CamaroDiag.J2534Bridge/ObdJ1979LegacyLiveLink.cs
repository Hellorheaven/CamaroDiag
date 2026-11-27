using System.Diagnostics;
using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>Selected legacy response source, not physical addressing.
/// The driver manages link framing/checksum; original records remain separate
/// from the normalized application record used by the common live recorder.</summary>
internal sealed class ObdJ1979LegacyLiveLink
{
    internal sealed record Frame(string Source,string RawHex,string PayloadHex,uint Timestamp,string? InFrameResponseHex);
    internal sealed record Trace(int LogicalQuery,string RequestHex,string? TxHex,
        IReadOnlyList<Frame> Frames,string Status,string? Detail);
    private readonly byte source;
    private readonly Func<byte[],int,ObdLegacyJ2534Transport.Exchange> exchange;
    private readonly Action requireActive;
    private readonly Func<long> clock;
    private int logical;
    private readonly ObdJ1979FreezeCampaign.Plan? freeze;
    private readonly ObdJ1979LiveRecorder.Plan? rawPlan;
    private readonly List<Trace> traces=[];
    internal IReadOnlyList<Trace> Traces=>traces.ToArray();

    internal ObdJ1979LegacyLiveLink(byte source,Func<byte[],int,ObdLegacyJ2534Transport.Exchange> exchange,
        Action requireActive,Func<long>? clock=null,ObdJ1979FreezeCampaign.Plan? freeze=null,ObdJ1979LiveRecorder.Plan? rawPlan=null)
    {
        if(source is 0 or 0xF1 or 0xFF)throw new ArgumentException("Invalid selected legacy source.");
        this.source=source;this.exchange=exchange;this.requireActive=requireActive;
        this.freeze=freeze?.Validate();
        this.rawPlan=rawPlan?.Validate();
        if(this.rawPlan is {RawOnly:false}||this.rawPlan is not null&&this.freeze is not null)
            throw new ArgumentException("Invalid exclusive legacy raw plan.");
        this.clock=clock??(()=>Stopwatch.GetTimestamp()*1000/Stopwatch.Frequency);
    }

    private bool Allows(byte[] request)=>freeze is not null ?
        request.SequenceEqual(new byte[]{1,0}) || request.Length==2&&request[0]==9&&request[1] is 0 or 1 or 2 ||
        request.Length==3&&request[0]==2&&freeze.Frames.Contains((int)request[2])&&
            (request[1]%32==0||request[1]==2||freeze.SelectedPids.Length==0||freeze.SelectedPids.Contains((int)request[1])) :
        request.Length==2&&
        (request[0]==9&&request[1] is 0 or 1 or 2||
         request[0]==1&&(request[1]%32==0||request[1]==0x1D||ObdJ1979ScalingContext.IsConfiguration(request[1])||ObdJ1979LiveRecorder.LegacyNumericPid(request[1])||rawPlan is not null&&rawPlan.Pids.Contains(request[1])));
    private int Remaining(long deadline,int maximum){
        requireActive();
        var left=deadline-clock();
        if(left<100)throw new TimeoutException("Legacy logical request deadline.");
        return (int)Math.Min(maximum,left);
    }
    private void Mark(string status,string? detail=null)=>traces[^1]=traces[^1] with {Status=status,Detail=detail};

    private byte[][] Read(byte[] request,long deadline,int index){
        if(!Allows(request))throw new ArgumentException("Not a selected legacy live read.");
        var hex=Convert.ToHexString(request);
        traces.Add(new(index,hex,null,[],"NOT_TESTED",null));
        try{
            var result=exchange(request,Remaining(deadline,4000));
            var frames=result.Replies.Select(packet=>new Frame(packet.Source.ToString("X2"),packet.RawHex,
                Convert.ToHexString(packet.Payload),packet.Timestamp,packet.InFrameResponseHex)).ToArray();
            traces[^1]=traces[^1] with {TxHex=result.TxHex,Frames=frames};
            requireActive();
            if(clock()>=deadline){
                Mark("NOT_TESTED","LATE_RESPONSE");throw new TimeoutException("Late legacy response.");
            }
            if(result.Replies.Count==0){
                Mark("TIMEOUT","NO_SELECTED_RESPONSE");throw new TimeoutException("Selected legacy source silent.");
            }
            if(result.Replies.Any(packet=>packet.Source!=source)){
                Mark("FAILED","RESPONSE_SOURCE_CHANGED");throw new InvalidDataException("Legacy response source changed.");
            }
            Mark("CORRELATED");
            return result.Replies.Select(x=>x.Payload).ToArray();
        }catch(OperationCanceledException){Mark("NOT_TESTED","CANCELLED");throw;}
        catch(TimeoutException){
            if(traces[^1].Detail is null)Mark("TIMEOUT","TIMEOUT");
            throw;
        }
        catch(Exception e) when(e is IOException or InvalidDataException or ArgumentException or InvalidOperationException){
            if(traces[^1].Detail is null)Mark("FAILED",e.GetType().Name);
            throw;
        }
    }
    private byte[] Single(byte[] request,long deadline,int index){
        var rows=Read(request,deadline,index);
        if(rows.Length!=1){Mark("FAILED","AMBIGUOUS_SELECTED_SOURCE");throw new InvalidDataException("Ambiguous response source.");}
        return rows[0];
    }
    private static bool Negative(byte[] payload,byte mode)=>payload.Length==3&&payload[0]==0x7F&&
        payload[1]==mode&&payload[2] is not (0 or 0x78);

    private byte[] Identity(long deadline,int index){
        var initial=Single([1,0],deadline,index);
        if(initial.Length!=6||initial[0]!=0x41||initial[1]!=0){
            Mark("FAILED","INITIAL_PID00_UNVERIFIED");throw new InvalidDataException("Initial legacy PID00 unverified.");
        }
        Mark("PASSED");
        var info=Single([9,0],deadline,index);
        if(Negative(info,9)){Mark("NEGATIVE");return info;}
        if(info.Length!=7||info[0]!=0x49||info[1]!=0||info[2]!=1||(info[3]&0xC0)!=0xC0){
            Mark("FAILED","VIN_INFO_TYPES_NOT_VERIFIED");throw new InvalidDataException("Legacy VIN not advertised.");
        }
        Mark("PASSED");
        var count=Single([9,1],deadline,index);
        if(Negative(count,9)){Mark("NEGATIVE");return count;}
        if(!count.SequenceEqual(new byte[]{0x49,1,5})){
            Mark("FAILED","VIN_COUNT_INVALID");throw new InvalidDataException("Expected five legacy VIN fragments.");
        }
        Mark("PASSED");
        var rows=Read([9,2],deadline,index);
        if(rows.Length==1&&Negative(rows[0],9)){Mark("NEGATIVE");return rows[0];}
        if(rows.Length!=5||rows.Where((r,i)=>r.Length!=7||r[0]!=0x49||r[1]!=2||r[2]!=i+1).Any()){
            Mark("FAILED","VIN_FRAGMENT_SEQUENCE_INVALID");throw new InvalidDataException("Incomplete legacy VIN fragments.");
        }
        var raw=rows.SelectMany(x=>x.Skip(3)).ToArray();
        if(!raw.Take(3).SequenceEqual(new byte[3])||raw.Skip(3).Any(x=>!(x is >=(byte)'A' and <=(byte)'Z'&&
            x is not ((byte)'I' or (byte)'O' or (byte)'Q')||x is >=(byte)'0' and <=(byte)'9'))){
            Mark("FAILED","VIN_PADDING_OR_ASCII_INVALID");throw new InvalidDataException("Invalid legacy VIN characters.");
        }
        Mark("PASSED");
        return [0x49,2,1,..raw.Skip(3)];
    }

    internal byte[] Query(byte[] request,int timeoutMs){
        if(timeoutMs is <1 or >20000)throw new ArgumentOutOfRangeException(nameof(timeoutMs));
        int index=logical++;
        long deadline=clock()+timeoutMs;
        if(request.SequenceEqual(new byte[]{9,2}))return Identity(deadline,index);
        if(!Allows(request)||(freeze is null ? request.Length!=2||request[0]!=1 : request.Length!=3||request[0]!=2))
            throw new ArgumentException("Request outside selected legacy recipe.");
        return Single(request,deadline,index);
    }
}
