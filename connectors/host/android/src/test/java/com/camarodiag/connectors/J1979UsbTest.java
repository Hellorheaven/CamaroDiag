package com.camarodiag.connectors;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class J1979UsbTest {
    private static byte[] hex(String s){
        byte[] bytes=new byte[s.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return bytes;
    }
    private static byte[] concat(byte[]... arrays)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] a:arrays)out.write(a);return out.toByteArray();
    }
    private static byte[] frame(int kind,int seq,byte[] body){
        byte[] data=ByteBuffer.allocate(12+body.length).order(ByteOrder.LITTLE_ENDIAN)
            .put(new byte[]{'C','J',1,(byte)kind}).putShort((short)seq).putShort((short)body.length).put(body).array();
        CRC32 crc=new CRC32();crc.update(data,0,data.length-4);
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length-4,(int)crc.getValue());return data;
    }
    private static final class Sim implements J1979UsbCapture.Link {
        final byte[] bytes;int pos=0,writes=0;boolean closed;
        Sim(byte[] bytes){this.bytes=bytes;}
        @Override public int read(byte[] out,int offset,int size,int timeout){
            int n=Math.min(7,Math.min(size,bytes.length-pos));
            System.arraycopy(bytes,pos,out,offset,n);pos+=n;return n;
        }
        @Override public void write(byte[] data,int timeout){writes++;}
        @Override public void checkNoBufferedInput()throws IOException{
            if(writes==2&&pos!=bytes.length)throw new IOException("Trailing bytes");
        }
        @Override public void close(){closed=true;}
    }
    public static void main(String[] args)throws Exception{
        byte[] key=new byte[32],nonce=new byte[32],device=new byte[32];
        for(int i=0;i<32;i++)key[i]=(byte)i;Arrays.fill(nonce,(byte)'H');Arrays.fill(device,(byte)'D');
        byte[] hello=J1979UsbAuth.hello(0x7e0,0x7e8,false,10000,nonce);
        byte[] challenge=concat(new byte[]{'C','U',1,2},device,
            hex("b97b539ae306e6525316479277660def5260ffaa27908b36a82d071cd3f51694"));
        byte[] proof=concat(new byte[]{'C','U',1,3},hex("effe75029406a796ee6bc3a18c55a59298f7d4760996d8a3e40ff660ac691590"));
        assert Arrays.equals(proof,J1979UsbAuth.proof(hello,challenge,key));
        byte[] start=frame(1,0,Arrays.copyOfRange(hello,4,17));
        byte[] end=frame(3,1,new byte[]{4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0});
        byte[] capture=concat(start,end);
        Sim good=new Sim(concat(challenge,capture));ByteArrayOutputStream raw=new ByteArrayOutputStream();
        J1979UsbCapture.Result result=new J1979UsbCapture(good).runRequest(hello,10000,key,raw);
        assert result.deviceCredentialAuthenticated&&result.frameIntegrityVerified&&result.captureIntegrityVerified&&!result.physicalVehicleVerified;
        assert good.closed&&good.writes==2&&Arrays.equals(raw.toByteArray(),capture);
        byte[] bad=challenge.clone();bad[67]^=1;
        Sim wrong=new Sim(concat(bad,capture));
        result=new J1979UsbCapture(wrong).runRequest(hello,10000,key,new ByteArrayOutputStream());
        assert !result.deviceCredentialAuthenticated&&!result.frameIntegrityVerified&&wrong.closed&&wrong.writes==1;
        byte[] forgedEnd=frame(3,1,new byte[]{1,3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0});
        Sim forged=new Sim(concat(challenge,start,forgedEnd));
        result=new J1979UsbCapture(forged).runRequest(hello,10000,key,new ByteArrayOutputStream());
        assert !result.captureIntegrityVerified&&result.summary==null;
        byte[] initial=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .put((byte)1).put((byte)2).putInt(1).putShort((short)6)
            .put(new byte[]{1,0,0x41,0,0,0,0,0}).array();
        byte[] missingSummary=ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            .put((byte)1).put((byte)3).putInt(1).putInt(1).array();
        Sim missing=new Sim(concat(challenge,start,frame(2,1,initial),frame(3,2,missingSummary)));
        result=new J1979UsbCapture(missing).runRequest(hello,10000,key,new ByteArrayOutputStream());
        assert !result.captureIntegrityVerified&&result.steps.size()==1;
        J1979CampaignSemantics.Step rpm=new J1979CampaignSemantics.Step(6,1,1,new byte[]{1,12},
            new byte[]{0x41,12,0x1a,(byte)0xf8});
        assert rpm.measurement().number==1726&&rpm.measurement().unit.equals("rpm");
        J1979CampaignSemantics.Step freeze=new J1979CampaignSemantics.Step(7,1,1,new byte[]{2,5,0},
            new byte[]{0x42,5,0,90});
        assert freeze.measurement().mode==2&&freeze.measurement().number==50;
        assert new J1979CampaignSemantics.Step(8,2,1,new byte[]{1,(byte)0xa1},
            new byte[]{0x41,(byte)0xa1,1}).measurement()==null;
        J1979CampaignSemantics.Step codes=new J1979CampaignSemantics.Step(3,1,1,new byte[]{3},
            new byte[]{0x43,4,1,0x23,0x45,0x67,(byte)0x89,(byte)0xab,(byte)0xcd,(byte)0xef});
        assert codes.dtcCodes().equals(java.util.Arrays.asList("P0123","C0567","B09AB","U0DEF"));
        J1979CampaignSemantics.Step denied=new J1979CampaignSemantics.Step(4,3,1,new byte[]{7},new byte[]{0x7f,7,0x11});
        assert denied.dtcCodes()==null&&denied.negativeResponseCode()==0x11;
        assert new J1979CampaignSemantics.Step(5,1,1,new byte[]{10},new byte[]{0x4a,0}).dtcCodes().isEmpty();
        J1979CampaignSemantics.Step monitor=new J1979CampaignSemantics.Step(9,1,1,new byte[]{6,1},
            hex("4601010212340000FFFF01030280000001FFFE"));
        assert monitor.monitorRecords().size()==2;
        assert monitor.monitorRecords().get(0).valueEncoded==0x1234;
        assert monitor.monitorRecords().get(1).minimumEncoded==1;
        assert monitor.monitorRecords().get(1).maximumEncoded==65534;
        assert new J1979CampaignSemantics.Step(9,1,1,new byte[]{6,0},hex("460080000000")).monitorRecords()==null;
        Sim partial=new Sim(concat(challenge,Arrays.copyOf(capture,capture.length-1)));
        raw=new ByteArrayOutputStream();
        result=new J1979UsbCapture(partial).runRequest(hello,10000,key,raw);
        assert !result.frameIntegrityVerified&&result.deviceCredentialAuthenticated&&partial.closed&&raw.size()==capture.length-1;
    }
}
