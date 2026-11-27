using System.Security.Cryptography;

namespace CamaroDiagConnectors.Windows;

/// <summary>One native USB capture, with independent replay of the bytes
/// persisted to the evidence stream. Credential proof is not capture provenance.</summary>
internal sealed class J1979FreezeUsbCapture(J1979UsbCapture.ILink link,Func<long>? clock=null)
{
    private readonly Func<long> now=clock??(()=>Environment.TickCount64);
    private bool used;
    private int Remaining(long start,int budget)
    {
        long elapsed=now()-start;
        if(elapsed<0||elapsed>=budget)throw new TimeoutException("CU/2 deadline exceeded");
        return budget-(int)elapsed;
    }
    private byte[] ReadExact(int size,long started,int budget,Stream? raw,Stream? copy)
    {
        var data=new byte[size];int offset=0;
        while(offset<size)
        {
            int count=link.Read(data,offset,size-offset,Remaining(started,budget));
            if(count<=0||count>size-offset)throw new IOException("Truncated CU/2 stream");
            raw?.Write(data,offset,count);copy?.Write(data,offset,count);
            offset+=count;_ = Remaining(started,budget);
        }
        return data;
    }
    internal J1979FreezeNative.Report Run(J1979FreezeNative.Profile requested,byte[] key,
        Stream raw,Func<bool>? current=null,byte[]? nonce=null)
    {
        if(used)throw new InvalidOperationException("Fresh CU/2 session required");
        if(key.Length!=32||!raw.CanWrite)throw new ArgumentException("Invalid CU/2 capture parameters");
        var profile=requested.Validate();used=true;
        bool authenticated=false,sent=false,cancelled=false;
        string? error=null;
        using var copy=new MemoryStream();
        void Guard()
        {
            if(current is not null&&!current())throw new OperationCanceledException("CU/2 session revoked");
        }
        try
        {
            Guard();
            byte[] hello=J1979FreezeNative.Hello(profile,nonce);
            long start=now();link.Write(hello,Remaining(start,5000));
            var challenge=ReadExact(J1979FreezeNative.ChallengeSize,start,5000,null,null);
            byte[] proof=J1979FreezeNative.Proof(hello,challenge,key);authenticated=true;
            try
            {
                Guard();link.CheckNoBufferedInput();
                link.Write(proof,Remaining(start,5000));sent=true;
            }
            finally{CryptographicOperations.ZeroMemory(proof);}
            int budget=profile.Plan.BudgetMs+15000;start=now();
            bool finished=false;
            for(int seq=0;seq<CamaroDiag.J2534Bridge.ObdJ1979FreezeCampaign.MaximumSteps+2;seq++)
            {
                Guard();
                var head=ReadExact(8,start,budget,raw,copy);
                var (kind,length)=J1979FreezeNative.Header(head,seq);
                var tail=ReadExact(length+4,start,budget,raw,copy);
                var body=J1979FreezeNative.Body(head,tail,length);
                if(seq==0)
                {
                    if(kind!=1||!body.AsSpan().SequenceEqual(profile.Encode()))
                        throw new InvalidDataException("CF/1 plan differs from approved scope");
                }
                else if(kind==1)throw new InvalidDataException("Duplicate CF/1 plan");
                if(kind==3)
                {
                    Guard();link.CheckNoBufferedInput();_ = Remaining(start,budget);
                    finished=true;break;
                }
            }
            if(!finished)throw new InvalidDataException("CF/1 record limit exceeded");
        }
        catch(OperationCanceledException e){cancelled=true;error=e.Message;}
        catch(Exception e) when(e is InvalidDataException or IOException or TimeoutException or ArgumentException or InvalidOperationException or UnauthorizedAccessException)
        {error=e.Message;}
        finally
        {
            try{link.Dispose();}
            catch(Exception e) when(e is InvalidDataException or IOException or TimeoutException or InvalidOperationException)
            {error??=e.Message;}
            try{raw.Flush();}
            catch(IOException e){error??=e.Message;}
        }
        if(current is not null&&!current())cancelled=true;
        copy.Position=0;
        var report=J1979FreezeNative.Decode(copy,profile);
        return report with {
            Source="LIVE_USB_CU2",DeviceCredentialAuthenticated=authenticated,ClientProofSent=sent,
            Cancelled=cancelled,CaptureIntegrityVerified=report.CaptureIntegrityVerified&&
                error is null&&!cancelled&&authenticated&&sent,
            Error=error??report.Error??(cancelled?"CU/2 cancelled":null)
        };
    }
}
