using System.Security.Cryptography;

namespace CamaroDiagConnectors.Windows;

/// <summary>Bounded one-shot CU/3 USB session. No automatic discovery,
/// retries, writes to ECUs, or fallback to another scope.</summary>
internal sealed class J1979LiveUsbCapture(J1979UsbCapture.ILink link,Func<long>? clock=null)
{
    private readonly Func<long> now=clock??(()=>Environment.TickCount64);
    private bool used;
    private int Remaining(long start,int budget)
    {
        long elapsed=now()-start;
        if(elapsed<0||elapsed>=budget)throw new TimeoutException("CU/3 deadline exceeded");
        return budget-(int)elapsed;
    }
    private static void Guard(Func<bool> current)
    {
        if(!current())throw new OperationCanceledException("CU/3 owner or channel revoked");
    }
    private byte[] ReadExact(int size,long start,int budget,Func<bool> current,Stream? raw,MemoryStream? copy)
    {
        var result=new byte[size];int offset=0;
        while(offset<size)
        {
            Guard(current);
            int count=link.Read(result,offset,size-offset,Remaining(start,budget));
            if(count<=0||count>size-offset)throw new IOException("Truncated CU/3 stream");
            if(copy is not null&&copy.Length+count>J1979LiveNative.MaximumCapture)
                throw new InvalidDataException("CL/1 capture exceeds byte limit");
            raw?.Write(result,offset,count);copy?.Write(result,offset,count);offset+=count;
            Guard(current);_ = Remaining(start,budget);
        }
        return result;
    }
    internal J1979LiveNative.Report Run(J1979LiveNative.Profile requested,byte[] key,Stream raw,
        Func<bool> current,byte[]? nonce=null)
    {
        if(used)throw new InvalidOperationException("Fresh CU/3 session required");
        if(key is not {Length:32}||!raw.CanWrite)throw new ArgumentException("Invalid CU/3 capture parameters");
        var profile=requested.Validate();used=true;
        bool authenticated=false,sent=false,cancelled=false;
        string? error=null;long began=now();
        using var copy=new MemoryStream();
        try
        {
            Guard(current);
            byte[] hello=J1979LiveNative.Hello(profile,nonce);
            long start=now();link.Write(hello,Remaining(start,5000));
            byte[] challenge=ReadExact(J1979LiveNative.ChallengeSize,start,5000,current,null,null);
            byte[] proof=J1979LiveNative.Proof(hello,challenge,key);authenticated=true;
            try
            {
                Guard(current);link.CheckNoBufferedInput();
                link.Write(proof,Remaining(start,5000));sent=true;
            }
            finally{CryptographicOperations.ZeroMemory(proof);}
            int budget=profile.BudgetMs+15000;start=now();
            bool finished=false;
            for(int seq=0;seq<J1979LiveNative.MaximumSteps+2;seq++)
            {
                byte[] head=ReadExact(8,start,budget,current,raw,copy);
                var (kind,length)=J1979LiveNative.Header(head,seq);
                var tail=ReadExact(length+4,start,budget,current,raw,copy);
                var body=J1979LiveNative.Body(head,tail,length);
                if(seq==0)
                {
                    if(kind!=1||!body.AsSpan().SequenceEqual(profile.Encode()))
                        throw new InvalidDataException("CL/1 plan differs from approved scope");
                }
                else if(kind==1)throw new InvalidDataException("Duplicate CL/1 plan");
                if(kind==3)
                {
                    Guard(current);link.CheckNoBufferedInput();_ = Remaining(start,budget);
                    finished=true;break;
                }
            }
            if(!finished)throw new InvalidDataException("CL/1 record limit exceeded");
        }
        catch(OperationCanceledException e){cancelled=true;error=e.Message;}
        catch(Exception e) when(e is InvalidDataException or IOException or TimeoutException or ArgumentException or
            InvalidOperationException or UnauthorizedAccessException or OverflowException){error=e.Message;}
        finally
        {
            try{link.Dispose();}
            catch(Exception e) when(e is InvalidDataException or IOException or TimeoutException or InvalidOperationException)
            {error??=e.Message;}
            try{raw.Flush();}catch(IOException e){error??=e.Message;}
        }
        if(!current())cancelled=true;
        copy.Position=0;
        var decoded=J1979LiveNative.Decode(copy,profile);
        bool integrity=decoded.CaptureIntegrityVerified&&error is null&&!cancelled&&authenticated&&sent;
        return decoded with{
            Source="LIVE_USB_CU3",DeviceCredentialAuthenticated=authenticated,ClientProofSent=sent,
            Cancelled=cancelled,HostElapsedMs=Math.Max(0,now()-began),CaptureIntegrityVerified=integrity,
            Summary=integrity?decoded.Summary:null,Result=integrity?decoded.Result:null,
            Error=error??decoded.Error??(cancelled?"CU/3 cancelled":null)
        };
    }
}
