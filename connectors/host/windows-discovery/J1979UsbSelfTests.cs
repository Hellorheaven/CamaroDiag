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
        Require(J1979NativeReport.VinMatch(result,null)=="NOT_REQUESTED");
        Require(J1979NativeReport.VinMatch(result,"1G1FA1RXXR0123456")=="UNVERIFIED");
        using(var json=new MemoryStream()){
            J1979NativeReport.Write(json,result,"test","synthetic","ECM","sim",0x7e0,0x7e8,false,10000,0,"sim.cj");
            using var document=System.Text.Json.JsonDocument.Parse(json.ToArray());
            var modes=document.RootElement.GetProperty("service_observations").EnumerateArray().ToArray();
            Require(modes.Length==8&&modes.All(m=>m.GetProperty("attempted").GetInt32()==0));
        }
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
        var rpm=new J1979CampaignSemantics.Step(6,1,1,[1,12],[0x41,12,0x1a,0xf8]);
        Require(rpm.PidMeasurement is { Number:1726, Unit:"rpm" });
        var freeze=new J1979CampaignSemantics.Step(7,1,1,[2,5,0],[0x42,5,0,90]);
        Require(freeze.PidMeasurement is { Mode:2,Number:50 });
        Require(new J1979CampaignSemantics.Step(8,2,1,[1,0xa1],[0x41,0xa1,1]).PidMeasurement is null);
        var codes=new J1979CampaignSemantics.Step(3,1,1,[3],[0x43,4,1,0x23,0x45,0x67,0x89,0xab,0xcd,0xef]);
        Require(codes.DtcCodes!.SequenceEqual(new[]{"P0123","C0567","B09AB","U0DEF"}));
        var denied=new J1979CampaignSemantics.Step(4,3,1,[7],[0x7f,7,0x11]);
        Require(denied.DtcCodes is null&&denied.NegativeResponseCode==0x11);
        Require(new J1979CampaignSemantics.Step(5,1,1,[10],[0x4a,0]).DtcCodes!.Count==0);
        var monitor=new J1979CampaignSemantics.Step(9,1,1,[6,1],Convert.FromHexString("4601010212340000FFFF01030280000001FFFE"));
        Require(monitor.MonitorRecords is {Count:2} records&&records[0].ValueEncoded==0x1234&&
            records[1].MinimumEncoded==1&&records[1].MaximumEncoded==65534);
        Require(new J1979CampaignSemantics.Step(9,1,1,[6,0],Convert.FromHexString("460080000000")).MonitorRecords is null);
        var cal=new J1979CampaignSemantics.Step(10,1,1,[9,4],Convert.FromHexString("49040143414C31000000000000000000000000"));
        Require(cal.Identification?.Values.SequenceEqual(new[]{"CAL1"})==true);
        var cvn=new J1979CampaignSemantics.Step(11,1,1,[9,6],Convert.FromHexString("49060201020304FFFFFFFF"));
        Require(cvn.Identification?.Values.SequenceEqual(new[]{"01020304","FFFFFFFF"})==true);
        Require(new J1979CampaignSemantics.Step(12,1,1,[9,4],
            Convert.FromHexString("49040143004131000000000000000000000000")).Identification is null);
        var ready=new J1979CampaignSemantics.Step(13,1,1,[1,1],Convert.FromHexString("410182112105")).MonitorReadiness!;
        Require(ready.MilOn&&ready.ConfirmedDtcCount==2&&!ready.CompressionIgnition);
        Require(!ready.SupportedMonitors["misfire"]&&!ready.SupportedMonitors["catalyst"]&&
            ready.SupportedMonitors["oxygen-sensor"]&&!ready.SupportedMonitors.ContainsKey("evaporative-system"));
        var diesel=new J1979CampaignSemantics.Step(14,1,1,[1,1],Convert.FromHexString("410100084040")).MonitorReadiness!;
        Require(diesel.CompressionIgnition&&!diesel.SupportedMonitors["particulate-filter"]&&
            !diesel.SupportedMonitors.ContainsKey("oxygen-sensor-heater"));
        var page=new J1979CampaignSemantics.Step(15,1,1,[1,0],Convert.FromHexString("410080000003")).InventoryPage!;
        Require(page.HasNextPage&&page.SupportedIds.SequenceEqual(new[]{1,31}));
        var freeze=new J1979CampaignSemantics.Step(16,1,1,[2,2,0],Convert.FromHexString("4202000123")).FreezeFrameTrigger!;
        Require(freeze.Present&&freeze.Dtc=="P0123");
        Require(new J1979CampaignSemantics.Step(17,1,1,[2,2,0],Convert.FromHexString("4202000000")).FreezeFrameTrigger is {Present:false,Dtc:null});
        var odo=new J1979CampaignSemantics.Step(18,1,1,[1,0xA6],Convert.FromHexString("41A60012D687"));
        Require(odo.PidMeasurement is {Number:123456.7,Unit:"km"});
        Require(new J1979CampaignSemantics.Step(19,1,1,[1,0xA6],Convert.FromHexString("41A6FFFFFFFF")).PidMeasurement?.Number==429496729.5);
        Require(new J1979CampaignSemantics.Step(20,2,1,[1,0xA6],Convert.FromHexString("41A60012D687")).PidMeasurement is null);
        var partial=new Sim([.. challenge,.. capture[..^1]]);using var partialRaw=new MemoryStream();
        result=new J1979UsbCapture(partial).RunRequest(hello,10000,key,partialRaw);
        Require(result.DeviceCredentialAuthenticated&&!result.FrameIntegrityVerified&&partial.Closed&&partialRaw.Length==capture.Length-1);
    }
}
