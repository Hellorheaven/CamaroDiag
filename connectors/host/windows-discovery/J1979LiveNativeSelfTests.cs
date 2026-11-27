using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace CamaroDiagConnectors.Windows;

internal static class J1979LiveNativeSelfTests
{
    private const string Vin="1G1FA1RXXR0123456";
    private static void Check(bool condition,string detail)
    {if(!condition)throw new InvalidOperationException("Native live regression: "+detail);}
    private static void P16(byte[] b,int at,int n)=>BinaryPrimitives.WriteUInt16LittleEndian(b.AsSpan(at),checked((ushort)n));
    private static void P32(byte[] b,int at,int n)=>BinaryPrimitives.WriteUInt32LittleEndian(b.AsSpan(at),checked((uint)n));
    private static byte[] Frame(int kind,int sequence,byte[] body)
    {
        var data=new byte[12+body.Length];data[0]=(byte)'C';data[1]=(byte)'L';data[2]=1;data[3]=(byte)kind;
        P16(data,4,sequence);P16(data,6,body.Length);body.CopyTo(data,8);
        BinaryPrimitives.WriteUInt32LittleEndian(data.AsSpan(8+body.Length),BinaryDiscovery.Crc32(data.AsSpan(0,8+body.Length)));
        return data;
    }
    private static byte[] Observation(int phase,int state,int cycle,int pid,int start,byte[] request,byte[] response)
    {
        var data=new byte[26+response.Length];
        data[0]=(byte)phase;data[1]=(byte)state;data[2]=1;P16(data,4,cycle);data[6]=(byte)pid;
        P32(data,8,start);P32(data,12,start+5);P16(data,16,100);P16(data,18,response.Length);P32(data,20,5);
        request.CopyTo(data,24);response.CopyTo(data,26);return data;
    }
    private static List<byte[]> Bodies(J1979LiveNative.Profile profile)
    {
        byte[] vin=[0x49,2,1,..Encoding.ASCII.GetBytes(Vin)];
        var terminal=new byte[128];terminal[1]=15;P16(terminal,2,2);
        P32(terminal,4,7);P32(terminal,8,4);P32(terminal,12,4);
        P32(terminal,24,125);P32(terminal,28,60);
        terminal[32]=32;terminal[33]=16;P16(terminal,64,2);P16(terminal,66,2);
        return [
            profile.Encode(),
            Observation(1,1,0,0,0,[9,2],vin),
            Observation(2,1,0,0,5,[1,0],[0x41,0,8,0x10,0,0]),
            Observation(4,2,1,5,10,[1,5],[0x41,5,0x50]),
            Observation(4,2,1,12,15,[1,12],[0x41,12,0x12,0x34]),
            Observation(4,2,2,5,110,[1,5],[0x41,5,0x51]),
            Observation(4,2,2,12,115,[1,12],[0x41,12,0x12,0x35]),
            Observation(5,1,0,0,120,[9,2],vin),
            terminal
        ];
    }
    private static byte[] Capture(List<byte[]> bodies)=>bodies.SelectMany((b,i)=>Frame(i==0?1:i==bodies.Count-1?3:2,i,b)).ToArray();
    private sealed class Sim(byte[] bytes):J1979UsbCapture.ILink
    {
        internal int Writes,Offset;
        internal bool Closed;
        public int Read(byte[] target,int offset,int count,int timeout)
        {
            int n=Math.Min(7,Math.Min(count,bytes.Length-Offset));
            Array.Copy(bytes,Offset,target,offset,n);Offset+=n;return n;
        }
        public void Write(byte[] data,int timeout){Writes++;}
        public void CheckNoBufferedInput()
        {
            if(Writes==2&&Offset!=bytes.Length)throw new IOException("Trailing CL/1 bytes");
        }
        public void Dispose(){Closed=true;}
    }
    internal static void Run()
    {
        var profile=new J1979LiveNative.Profile(0x7e0,0x7e8,false,Vin,[5,12],2,100,1000);
        byte[] key=Enumerable.Range(0,32).Select(i=>(byte)i).ToArray();
        byte[] nonce=Enumerable.Repeat((byte)'H',32).ToArray();
        byte[] device=Enumerable.Repeat((byte)'D',32).ToArray();
        byte[] hello=J1979LiveNative.Hello(profile,nonce);
        Check(hello.Length==107&&J1979LiveNative.Profile.DecodeAuth(profile.EncodeAuth()).Encode().SequenceEqual(profile.Encode()),"scope round trip");
        byte[] transcript=[(byte)'L',..hello[4..],..device];
        byte[] challenge=[(byte)'C',(byte)'U',3,2,..device,..HMACSHA256.HashData(key,transcript)];
        transcript[0]=(byte)'l';
        Check(J1979LiveNative.Proof(hello,challenge,key).SequenceEqual(
            new byte[]{(byte)'C',(byte)'U',3,3}.Concat(HMACSHA256.HashData(key,transcript))),"CU/3 proof");
        byte[] capture=Capture(Bodies(profile));
        var replay=J1979LiveNative.Decode(new MemoryStream(capture),profile);
        Check(replay.CaptureIntegrityVerified&&replay.Summary?.Completed==true&&replay.Summary.Raw==4,"complete recording");
        Check(replay.Result?.Samples.Length==4&&replay.Result.Observations.Length==7,"all samples and admission retained");
        Check(!replay.DeviceCredentialAuthenticated&&!replay.PhysicalVehicleVerified&&!replay.PermitsProgramming,"offline provenance");
        foreach(var (index,offset,value) in new (int,int,byte)[]{(3,25,6),(4,6,5),(5,8,20),(8,12,5),(1,3,1),(2,28,0),(7,27,0)})
        {
            var bodies=Bodies(profile);bodies[index][offset]=value;
            Check(!J1979LiveNative.Decode(new MemoryStream(Capture(bodies)),profile).CaptureIntegrityVerified,"semantic tamper despite CRC");
        }
        foreach(byte[] bad in new[]{capture[..^1],capture.Concat(new byte[]{0}).ToArray(),capture[..8]})
            Check(!J1979LiveNative.Decode(new MemoryStream(bad),profile).CaptureIntegrityVerified,"truncated/trailing stream");
        Check(!J1979LiveNative.Decode(new MemoryStream(capture),profile with{Pids=[12,5]}).CaptureIntegrityVerified,"exact ordered PID scope");
        var sim=new Sim(challenge.Concat(capture).ToArray());
        using var raw=new MemoryStream();
        var live=new J1979LiveUsbCapture(sim).Run(profile,key,raw,()=>true,nonce);
        Check(live.CaptureIntegrityVerified&&live.DeviceCredentialAuthenticated&&live.ClientProofSent&&
            sim.Writes==2&&sim.Closed&&raw.ToArray().SequenceEqual(capture),"live capture");
        challenge[^1]^=1;
        var denied=new Sim(challenge);
        using var rejected=new MemoryStream();
        var failure=new J1979LiveUsbCapture(denied).Run(profile,key,rejected,()=>true,nonce);
        Check(!failure.CaptureIntegrityVerified&&!failure.DeviceCredentialAuthenticated&&denied.Writes==1&&denied.Closed,"bad credential");
        var revoked=new Sim(challenge);
        using var unused=new MemoryStream();
        var cancel=new J1979LiveUsbCapture(revoked).Run(profile,key,unused,()=>false,nonce);
        Check(cancel.Cancelled&&!cancel.CaptureIntegrityVerified&&revoked.Writes==0&&revoked.Closed,"pre-I/O revocation");
    }
}
