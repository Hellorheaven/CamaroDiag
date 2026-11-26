using System.Buffers.Binary;

namespace CamaroDiagConnectors.Windows;

internal static class J1979UsbSelfTests
{
    private static void Require(bool ok){if(!ok)throw new Exception("CU/1 test failed");}
    private static byte[] Frame(byte kind,ushort seq,byte[] body){
        byte[] data=new byte[12+body.Length];"CJ"u8.CopyTo(data);data[2]=1;data[3]=kind;
        BinaryPrimitives.WriteUInt16LittleEndian(data.AsSpan(4),seq);
        BinaryPrimitives.WriteUInt16LittleEndian(data.AsSpan(6),(ushort)body.Length);
        body.CopyTo(data,8);
        BinaryPrimitives.WriteUInt32LittleEndian(data.AsSpan(data.Length-4),BinaryDiscovery.Crc32(data.AsSpan(0,data.Length-4)));
        return data;
    }
    private sealed class Sim(byte[] input):J1979UsbCapture.ILink {
        private int pos;
        internal int Writes;
        internal bool Closed;
        public int Read(byte[] data,int offset,int size,int timeoutMs){
            int n=Math.Min(7,Math.Min(size,input.Length-pos));Array.Copy(input,pos,data,offset,n);pos+=n;return n;
        }
        public void Write(byte[] data,int timeoutMs)=>Writes++;
        public void CheckNoBufferedInput(){if(Writes==2&&pos!=input.Length)throw new IOException("Trailing bytes");}
        public void Dispose()=>Closed=true;
    }
    internal static void Run(){
        byte[] key=Enumerable.Range(0,32).Select(i=>(byte)i).ToArray();
        byte[] hello=J1979UsbAuth.Hello(0x7e0,0x7e8,false,10000,Enumerable.Repeat((byte)'H',32).ToArray());
        byte[] challenge=[.. "CU\x01\x02"u8,.. Enumerable.Repeat((byte)'D',32),
            .. Convert.FromHexString("b97b539ae306e6525316479277660def5260ffaa27908b36a82d071cd3f51694")];
        byte[] proof=[.. "CU\x01\x03"u8,.. Convert.FromHexString("effe75029406a796ee6bc3a18c55a59298f7d4760996d8a3e40ff660ac691590")];
        Require(J1979UsbAuth.Proof(hello,challenge,key).SequenceEqual(proof));
        byte[] summary=new byte[22];summary[0]=4;
        byte[] capture=[.. Frame(1,0,hello[4..17]),.. Frame(3,1,summary)];
        var good=new Sim([.. challenge,.. capture]);using var raw=new MemoryStream();
        var result=new J1979UsbCapture(good).RunRequest(hello,10000,key,raw);
        Require(result.DeviceCredentialAuthenticated&&result.FrameIntegrityVerified&&result.CaptureIntegrityVerified&&!result.PhysicalVehicleVerified&&good.Closed&&good.Writes==2);
        Require(raw.ToArray().SequenceEqual(capture));
        byte[] bad=challenge.ToArray();bad[^1]^=1;
        var wrong=new Sim([.. bad,.. capture]);using var badRaw=new MemoryStream();
        result=new J1979UsbCapture(wrong).RunRequest(hello,10000,key,badRaw);
        Require(!result.DeviceCredentialAuthenticated&&!result.FrameIntegrityVerified&&wrong.Closed&&wrong.Writes==1);
        byte[] forged=summary.ToArray();forged[0]=1;forged[1]=3;
        var fake=new Sim([.. challenge,.. Frame(1,0,hello[4..17]),.. Frame(3,1,forged)]);
        using var fakeRaw=new MemoryStream();
        result=new J1979UsbCapture(fake).RunRequest(hello,10000,key,fakeRaw);
        Require(!result.CaptureIntegrityVerified&&result.Summary is null);
        byte[] initial=[1,2,1,0,0,0,6,0,1,0,0x41,0,0,0,0,0];
        byte[] missing=new byte[22];missing[0]=1;missing[1]=3;missing[2]=1;missing[6]=1;
        var incomplete=new Sim([.. challenge,.. Frame(1,0,hello[4..17]),.. Frame(2,1,initial),.. Frame(3,2,missing)]);
        using var incompleteRaw=new MemoryStream();
        result=new J1979UsbCapture(incomplete).RunRequest(hello,10000,key,incompleteRaw);
        Require(!result.CaptureIntegrityVerified&&result.Steps.Count==1);
        var codes=new J1979CampaignSemantics.Step(3,1,1,[3],[0x43,4,1,0x23,0x45,0x67,0x89,0xab,0xcd,0xef]);
        Require(codes.DtcCodes!.SequenceEqual(new[]{"P0123","C0567","B09AB","U0DEF"}));
        var denied=new J1979CampaignSemantics.Step(4,3,1,[7],[0x7f,7,0x11]);
        Require(denied.DtcCodes is null&&denied.NegativeResponseCode==0x11);
        Require(new J1979CampaignSemantics.Step(5,1,1,[10],[0x4a,0]).DtcCodes!.Count==0);
        var partial=new Sim([.. challenge,.. capture[..^1]]);using var partialRaw=new MemoryStream();
        result=new J1979UsbCapture(partial).RunRequest(hello,10000,key,partialRaw);
        Require(result.DeviceCredentialAuthenticated&&!result.FrameIntegrityVerified&&partial.Closed&&partialRaw.Length==capture.Length-1);
    }
}
