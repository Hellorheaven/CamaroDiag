package com.camarodiag.connectors;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Deterministic USB protocol fixtures; no device I/O. */
public final class J1979FreezeUsbTest {
    private static final String VIN="1G1FA1E30D1234567";
    private static byte[] concat(byte[]... values)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(byte[] value:values)out.write(value);
        return out.toByteArray();
    }
    private static byte[] hmac(byte[] key,byte[] value)throws Exception{
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value);
    }
    private static byte[] frame(int kind,int seq,byte[] body){
        byte[] data=ByteBuffer.allocate(12+body.length).order(ByteOrder.LITTLE_ENDIAN)
            .put(new byte[]{'C','F',1,(byte)kind}).putShort((short)seq)
            .putShort((short)body.length).put(body).array();
        CRC32 crc=new CRC32();crc.update(data,0,data.length-4);
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(data.length-4,(int)crc.getValue());return data;
    }
    private static byte[] observation(int seq,byte[] request,byte[] response){
        return frame(2,seq,ByteBuffer.allocate(10+request.length+response.length)
            .order(ByteOrder.LITTLE_ENDIAN).put((byte)1).put((byte)1)
            .put((byte)request.length).put((byte)0).putInt(5)
            .putShort((short)response.length).put(request).put(response).array());
    }
    private static byte[] capture(J1979FreezeUsbAuth.Profile profile)throws IOException{
        byte[] identity=concat(new byte[]{0x49,2,1},VIN.getBytes(StandardCharsets.US_ASCII));
        byte[] terminal=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .put((byte)0).put((byte)1).put((byte)7).put((byte)0).putInt(6).putInt(30)
            .put((byte)2).array();
        return concat(frame(1,0,profile.encode()),
            observation(1,new byte[]{9,2},identity),
            observation(2,new byte[]{2,0,0},new byte[]{0x42,0,0,0x40,0x10,0,0}),
            observation(3,new byte[]{2,2,0},new byte[]{0x42,2,0,3,1}),
            observation(4,new byte[]{2,12,0},new byte[]{0x42,12,0,0x1f,0x40}),
            observation(5,new byte[]{2,2,0},new byte[]{0x42,2,0,3,1}),
            observation(6,new byte[]{9,2},identity),frame(3,7,terminal));
    }
    private static final class Sim implements J1979UsbCapture.Link {
        final byte[] bytes;int pos,writes;boolean closed;
        Sim(byte[] bytes){this.bytes=bytes;}
        @Override public int read(byte[] out,int offset,int size,int timeout){
            int n=Math.min(7,Math.min(size,bytes.length-pos));
            System.arraycopy(bytes,pos,out,offset,n);pos+=n;return n;
        }
        @Override public void write(byte[] data,int timeout){writes++;}
        @Override public void checkNoBufferedInput()throws IOException{
            if(writes==2&&pos!=bytes.length)throw new IOException("Trailing data");
        }
        @Override public void close(){closed=true;}
    }
    public static void main(String[] args)throws Exception{
        J1979FreezeUsbAuth.Profile profile=new J1979FreezeUsbAuth.Profile(0x7e0,0x7e8,
            false,60000,VIN,new int[]{0},new int[]{12});
        byte[] wire=profile.encode();
        assert wire.length==104;
        assert Arrays.equals(wire,J1979FreezeUsbAuth.Profile.decode(wire).encode());
        byte[] invalid=wire.clone();invalid[33]=1;
        try{J1979FreezeUsbAuth.Profile.decode(invalid);throw new AssertionError("Noncanonical scope accepted");}
        catch(IOException expected){/* rejection */}
        byte[] key=new byte[32],nonce=new byte[32],device=new byte[32];
        for(int i=0;i<32;i++)key[i]=(byte)i;
        Arrays.fill(nonce,(byte)'H');Arrays.fill(device,(byte)'D');
        byte[] hello=J1979FreezeUsbAuth.hello(profile,nonce);
        byte[] transcript=concat(new byte[]{'F'},Arrays.copyOfRange(hello,4,hello.length),device);
        byte[] challenge=concat(new byte[]{'C','U',2,2},device,hmac(key,transcript));
        transcript[0]='f';
        assert Arrays.equals(J1979FreezeUsbAuth.proof(hello,challenge,key),
            concat(new byte[]{'C','U',2,3},hmac(key,transcript)));
        byte[] capture=capture(profile);
        J1979FreezeUsbCapture.Result decoded=J1979FreezeUsbCapture.decode(
            new ByteArrayInputStream(capture),profile);
        assert decoded.frameIntegrityVerified;
        assert decoded.observations.size()==6;
        assert decoded.terminal.completed&&decoded.terminal.allFramesStable;
        assert !decoded.evidenceAuthenticated&&!decoded.physicalVehicleVerified&&!decoded.permitsProgramming;
        byte[] modified=decoded.observations.get(0).request();modified[0]=4;
        assert decoded.observations.get(0).request()[0]==9;
        byte[] damaged=capture.clone();damaged[50]^=1;
        for(byte[] bad:new byte[][]{damaged,Arrays.copyOf(capture,capture.length-1),concat(capture,new byte[]{0})}){
            assert !J1979FreezeUsbCapture.decode(new ByteArrayInputStream(bad),profile).frameIntegrityVerified;
        }
        Sim link=new Sim(concat(challenge,capture));
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        J1979FreezeUsbCapture.Result live=new J1979FreezeUsbCapture(link).runRequest(
            profile,hello,key,output,()->true);
        assert live.frameIntegrityVerified&&live.deviceCredentialAuthenticated&&live.clientProofSent;
        assert link.writes==2&&link.closed&&Arrays.equals(output.toByteArray(),capture);
        challenge[challenge.length-1]^=1;
        Sim wrong=new Sim(challenge);
        J1979FreezeUsbCapture.Result refused=new J1979FreezeUsbCapture(wrong).runRequest(
            profile,hello,key,new ByteArrayOutputStream(),()->true);
        assert !refused.frameIntegrityVerified&&!refused.deviceCredentialAuthenticated;
        assert wrong.writes==1&&wrong.closed;
        System.out.println("CU/2 Android protocol fixtures passed (simulation)");
    }
}
