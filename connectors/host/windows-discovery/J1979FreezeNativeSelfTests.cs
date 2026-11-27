using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace CamaroDiagConnectors.Windows;

internal static class J1979FreezeNativeSelfTests
{
    private const string Vin="1G1FA1E30D1234567";
    private static void Require(bool condition,string detail)
    {
        if(!condition)throw new InvalidOperationException("Native freeze regression: "+detail);
    }

    private static byte[] Frame(int kind,int sequence,byte[] body)
    {
        var data=new byte[12+body.Length];data[0]=(byte)'C';data[1]=(byte)'F';data[2]=1;data[3]=(byte)kind;
        BinaryPrimitives.WriteUInt16LittleEndian(data.AsSpan(4),(ushort)sequence);
        BinaryPrimitives.WriteUInt16LittleEndian(data.AsSpan(6),(ushort)body.Length);
        body.CopyTo(data,8);
        BinaryPrimitives.WriteUInt32LittleEndian(data.AsSpan(8+body.Length),BinaryDiscovery.Crc32(data.AsSpan(0,8+body.Length)));
        return data;
    }

    private static byte[] Observation(int sequence,byte[] request,byte[] response)
    {
        byte[] body=new byte[10+request.Length+response.Length];
        body[0]=1;body[1]=1;body[2]=(byte)request.Length;
        BinaryPrimitives.WriteUInt32LittleEndian(body.AsSpan(4),5);
        BinaryPrimitives.WriteUInt16LittleEndian(body.AsSpan(8),(ushort)response.Length);
        request.CopyTo(body,10);response.CopyTo(body,10+request.Length);
        return Frame(2,sequence,body);
    }

    private static byte[] Capture(J1979FreezeNative.Profile profile,bool forgedOrder=false,bool forgedTerminal=false)
    {
        byte[] identity=[0x49,2,1,..Encoding.ASCII.GetBytes(Vin)];
        var rows=new List<byte[]>{
            Frame(1,0,profile.Encode()),
            Observation(1,[9,2],identity),
            Observation(2,[2,0,0],[0x42,0,0,0x40,0x10,0,0]),
            Observation(3,[2,2,0],[0x42,2,0,3,1]),
            Observation(4,forgedOrder?[2,13,0]:[2,12,0],[0x42,12,0,0x1f,0x40]),
            Observation(5,[2,2,0],[0x42,2,0,3,1]),
            Observation(6,[9,2],identity)
        };
        byte[] terminal=new byte[20];terminal[1]=1;terminal[2]=7;
        BinaryPrimitives.WriteUInt32LittleEndian(terminal.AsSpan(4),6);
        BinaryPrimitives.WriteUInt32LittleEndian(terminal.AsSpan(8),30);
        terminal[12]=forgedTerminal?(byte)1:(byte)2;
        rows.Add(Frame(3,7,terminal));return rows.SelectMany(x=>x).ToArray();
    }

    private sealed class Sim(byte[] data):J1979UsbCapture.ILink
    {
        internal int Writes,Offset;
        internal bool Disposed;
        public int Read(byte[] target,int offset,int count,int timeoutMs)
        {
            int size=Math.Min(7,Math.Min(count,data.Length-Offset));
            Array.Copy(data,Offset,target,offset,size);Offset+=size;return size;
        }
        public void Write(byte[] bytes,int timeoutMs){Writes++;}
        public void CheckNoBufferedInput()
        {
            if(Writes==2&&Offset!=data.Length)throw new IOException("Trailing native bytes");
        }
        public void Dispose(){Disposed=true;}
    }

    internal static void Run()
    {
        var profile=new J1979FreezeNative.Profile(0x7e0,0x7e8,false,new(Vin,[0],[12],60000));
        byte[] wire=profile.Encode();
        Require(wire.Length==104&&J1979FreezeNative.Profile.Decode(wire).Encode().SequenceEqual(wire),"canonical plan");
        byte[] key=Enumerable.Range(0,32).Select(i=>(byte)i).ToArray();
        byte[] nonce=Enumerable.Repeat((byte)'H',32).ToArray();
        byte[] device=Enumerable.Repeat((byte)'D',32).ToArray();
        byte[] hello=J1979FreezeNative.Hello(profile,nonce);
        byte[] transcript=[(byte)'F',..hello[4..],..device];
        byte[] challenge=[(byte)'C',(byte)'U',2,2,..device,..HMACSHA256.HashData(key,transcript)];
        transcript[0]=(byte)'f';
        Require(J1979FreezeNative.Proof(hello,challenge,key).SequenceEqual(
            new byte[]{(byte)'C',(byte)'U',2,3}.Concat(HMACSHA256.HashData(key,transcript))),"CU/2 proof");
        byte[] capture=Capture(profile);
        var report=J1979FreezeNative.Decode(new MemoryStream(capture),profile);
        Require(report.CaptureIntegrityVerified&&report.Summary?.AllFramesStable==true,"complete replay");
        Require(!report.PhysicalVehicleVerified&&!report.EvidenceAuthenticated&&!report.PermitsProgramming,"no provenance");
        foreach(byte[] bad in new[]{capture[..^1],capture.Concat(new byte[]{0}).ToArray(),
                Capture(profile,true),Capture(profile,false,true)})
            Require(!J1979FreezeNative.Decode(new MemoryStream(bad),profile).CaptureIntegrityVerified,"reject malformed recipe");
        var sim=new Sim(challenge.Concat(capture).ToArray());
        using var raw=new MemoryStream();
        var live=new J1979FreezeUsbCapture(sim).Run(profile,key,raw,()=>true,nonce);
        Require(live.CaptureIntegrityVerified&&live.DeviceCredentialAuthenticated&&live.ClientProofSent&&
            sim.Writes==2&&sim.Disposed&&raw.ToArray().SequenceEqual(capture),"live persistence");
        challenge[^1]^=1;
        var denied=new Sim(challenge);
        using var rejected=new MemoryStream();
        var failed=new J1979FreezeUsbCapture(denied).Run(profile,key,rejected,()=>true,nonce);
        Require(!failed.CaptureIntegrityVerified&&!failed.DeviceCredentialAuthenticated&&denied.Writes==1&&denied.Disposed,
            "never send client proof after invalid server proof");
    }
}
