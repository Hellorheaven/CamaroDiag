using System.Buffers.Binary;

namespace CamaroDiagConnectors.Windows;

// Caller supplies an exclusively owned, bounded USB channel; no discovery fallback.
internal sealed class J1979UsbCapture(J1979UsbCapture.ILink link,Func<long>? clock=null)
{
    internal interface ILink : IDisposable
    {
        int Read(byte[] data,int offset,int size,int timeoutMs);
        void Write(byte[] data,int timeoutMs);
        void CheckNoBufferedInput();
    }
    internal sealed record Result(bool DeviceCredentialAuthenticated,bool FrameIntegrityVerified,
        int Records,string? Error)
    {
        public bool EvidenceAuthenticated=>false;
        public bool PhysicalVehicleVerified=>false;
    }
    private readonly Func<long> now=clock??(()=>Environment.TickCount64);
    private bool used;
    private int Remaining(long start,int budget)
    {
        long spent=now()-start;
        if(spent<0||spent>=budget)throw new TimeoutException("CU/1 deadline exceeded");
        return budget-(int)spent;
    }
    private byte[] Exact(int size,long start,int budget,Stream? raw)
    {
        byte[] data=new byte[size];int offset=0;
        while(offset<size){
            int n=link.Read(data,offset,size-offset,Remaining(start,budget));
            if(n<=0||n>size-offset)throw new IOException("Truncated CU/1 capture");
            raw?.Write(data,offset,n);offset+=n;_ = Remaining(start,budget);
        }
        return data;
    }
    internal Result Run(uint tx,uint rx,bool ext,int budget,byte[] key,Stream raw)=>
        RunRequest(J1979UsbAuth.Hello(tx,rx,ext,budget),budget,key,raw);
    internal Result RunRequest(byte[] hello,int budget,byte[] key,Stream raw)
    {
        if(used)throw new IOException("Fresh CU/1 session required");
        if(key.Length!=32||budget is <1 or >600000||!raw.CanWrite)throw new ArgumentException("Invalid capture parameters");
        used=true;bool authenticated=false,framed=false;int records=0;string? error=null;
        try{
            long start=now();
            link.Write(hello,Remaining(start,5000));
            byte[] challenge=Exact(J1979UsbAuth.ChallengeSize,start,5000,null);
            byte[] proof=J1979UsbAuth.Proof(hello,challenge,key);authenticated=true;
            link.CheckNoBufferedInput();link.Write(proof,Remaining(start,5000));
            start=now();int limit=budget+15000;
            var counts=new uint[6];
            for(int seq=0;seq<2048;seq++){
                byte[] head=Exact(8,start,limit,raw);
                int kind=head[3],len=BinaryPrimitives.ReadUInt16LittleEndian(head.AsSpan(6));
                if(head[0]!='C'||head[1]!='J'||head[2]!=1||
                   BinaryPrimitives.ReadUInt16LittleEndian(head.AsSpan(4))!=seq||len>4110)
                    throw new IOException("Invalid CJ/1 frame");
                byte[] tail=Exact(len+4,start,limit,raw);
                byte[] combined=new byte[len+8];head.CopyTo(combined,0);tail.AsSpan(0,len).CopyTo(combined.AsSpan(8));
                if(BinaryDiscovery.Crc32(combined)!=BinaryPrimitives.ReadUInt32LittleEndian(tail.AsSpan(len)))
                    throw new IOException("CJ/1 CRC mismatch");
                uint U32(int at)=>BinaryPrimitives.ReadUInt32LittleEndian(tail.AsSpan(at));
                if(seq==0){
                    if(kind!=1||len!=13||!tail.AsSpan(0,13).SequenceEqual(hello.AsSpan(4,13)))
                        throw new IOException("Unexpected CJ/1 campaign profile");
                }else if(kind==2){
                    int state=tail[0];
                    if(len<9||state is <1 or >5)throw new IOException("Invalid CJ/1 observation");
                    int req=tail[1],res=BinaryPrimitives.ReadUInt16LittleEndian(tail.AsSpan(6));
                    if(req is <1 or >7||res>4095||len!=8+req+res||((state<=3)!=(res>0)))
                        throw new IOException("Invalid CJ/1 observation length");
                    counts[state]++;
                }else if(kind==3){
                    if(len!=22||tail[0] is <1 or >5||U32(2)!=(uint)(seq-1)||
                       U32(6)!=counts[1]||U32(10)!=counts[2]||U32(14)!=counts[3]||U32(18)!=counts[4])
                        throw new IOException("Inconsistent CJ/1 summary");
                    link.CheckNoBufferedInput();_ = Remaining(start,limit);records++;framed=true;break;
                }else throw new IOException("Unexpected CJ/1 record");
                records++;
            }
            if(!framed)throw new IOException("CJ/1 record limit exceeded");
        }catch(Exception ex) when(ex is IOException or TimeoutException or UnauthorizedAccessException or InvalidOperationException){
            error=ex.Message;framed=false;
        }finally{
            try{link.Dispose();}catch(Exception ex) when(ex is IOException or TimeoutException or InvalidOperationException){error??=ex.Message;framed=false;}
            try{raw.Flush();}catch(IOException ex){error??=ex.Message;framed=false;}
        }
        return new(authenticated,framed,records,error);
    }
}
