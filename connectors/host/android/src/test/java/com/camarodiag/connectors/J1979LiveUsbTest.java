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

public final class J1979LiveUsbTest {
    private static final String VIN="1G1FA1RXXR0123456";
    private static byte[] concat(byte[]... items)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(byte[] item:items)out.write(item);
        return out.toByteArray();
    }
    private static byte[] hmac(byte[] key,byte[] value)throws Exception{
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
        return mac.doFinal(value);
    }
    private static byte[] frame(int kind,int seq,byte[] body){
        byte[] data=ByteBuffer.allocate(12+body.length).order(ByteOrder.LITTLE_ENDIAN)
            .put(new byte[]{'C','L',1,(byte)kind}).putShort((short)seq).putShort((short)body.length).put(body).array();
        CRC32 crc=new CRC32();crc.update(data,0,data.length-4);
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length-4,(int)crc.getValue());
        return data;
    }
    private static byte[] observation(int phase,int state,int cycle,int pid,int start,byte[] request,byte[] response){
        return ByteBuffer.allocate(26+response.length).order(ByteOrder.LITTLE_ENDIAN)
            .put((byte)phase).put((byte)state).put((byte)1).put((byte)0).putShort((short)cycle)
            .put((byte)pid).put((byte)0).putInt(start).putInt(start+5).putShort((short)100)
            .putShort((short)response.length).putInt(5).put(request).put(response).array();
    }
    private static byte[][] bodies(J1979LiveUsbAuth.Profile profile)throws IOException{
        byte[] identity=concat(new byte[]{0x49,2,1},VIN.getBytes(StandardCharsets.US_ASCII));
        byte[] terminal=new byte[128];
        ByteBuffer buf=ByteBuffer.wrap(terminal).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte)0).put((byte)15).putShort((short)2).putInt(7).putInt(4).putInt(4)
            .putInt(0).putInt(0).putInt(125).putInt(60);
        terminal[32]=32;terminal[33]=16;buf.position(64);buf.putShort((short)2).putShort((short)2);
        return new byte[][]{
            profile.encode(),
            observation(1,1,0,0,0,new byte[]{9,2},identity),
            observation(2,1,0,0,5,new byte[]{1,0},new byte[]{0x41,0,8,0x10,0,0}),
            observation(4,2,1,5,10,new byte[]{1,5},new byte[]{0x41,5,0x50}),
            observation(4,2,1,12,15,new byte[]{1,12},new byte[]{0x41,12,0x12,0x34}),
            observation(4,2,2,5,110,new byte[]{1,5},new byte[]{0x41,5,0x51}),
            observation(4,2,2,12,115,new byte[]{1,12},new byte[]{0x41,12,0x12,0x35}),
            observation(5,1,0,0,120,new byte[]{9,2},identity),terminal};
    }
    private static byte[] capture(byte[][] bodies)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int i=0;i<bodies.length;i++)out.write(frame(i==0?1:i==bodies.length-1?3:2,i,bodies[i]));
        return out.toByteArray();
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
            if(writes==2&&pos!=bytes.length)throw new IOException("Trailing CL/1 bytes");
        }
        @Override public void close(){closed=true;}
    }
    public static void main(String[] args)throws Exception{
        J1979LiveUsbAuth.Profile profile=new J1979LiveUsbAuth.Profile(0x7e0,0x7e8,false,VIN,new int[]{5,12},2,100,1000);
        assert profile.encode().length==67;
        assert Arrays.equals(profile.encode(),J1979LiveUsbAuth.Profile.decodeAuth(profile.encodeAuth()).encode());
        byte[] key=new byte[32],nonce=new byte[32],device=new byte[32];
        for(int i=0;i<32;i++)key[i]=(byte)i;
        Arrays.fill(nonce,(byte)'H');Arrays.fill(device,(byte)'D');
        byte[] hello=J1979LiveUsbAuth.hello(profile,nonce);
        byte[] transcript=concat(new byte[]{'L'},Arrays.copyOfRange(hello,4,hello.length),device);
        byte[] challenge=concat(new byte[]{'C','U',3,2},device,hmac(key,transcript));
        transcript[0]='l';
        assert hello.length==107;
        assert Arrays.equals(J1979LiveUsbAuth.proof(hello,challenge,key),concat(new byte[]{'C','U',3,3},hmac(key,transcript)));
        byte[] raw=capture(bodies(profile));
        J1979LiveNativeReplay.Result decoded=J1979LiveNativeReplay.decode(new ByteArrayInputStream(raw),profile);
        assert decoded.captureIntegrityVerified&&decoded.completed()&&decoded.observationsReceived==7;
        assert ((Number)decoded.summary.get("raw")).intValue()==4;
        assert Boolean.FALSE.equals(decoded.report().get("physical_vehicle_verified"));
        assert !decoded.deviceCredentialAuthenticated;
        for(int[] change:new int[][]{{3,25,6},{4,6,5},{5,8,20},{8,12,5},{1,3,1},{2,28,0},{7,27,0}}){
            byte[][] rows=bodies(profile);rows[change[0]][change[1]]=(byte)change[2];
            assert !J1979LiveNativeReplay.decode(new ByteArrayInputStream(capture(rows)),profile).captureIntegrityVerified;
        }
        for(byte[] bad:new byte[][]{Arrays.copyOf(raw,raw.length-1),concat(raw,new byte[]{0}),Arrays.copyOf(raw,8)})
            assert !J1979LiveNativeReplay.decode(new ByteArrayInputStream(bad),profile).captureIntegrityVerified;
        Sim link=new Sim(concat(challenge,raw));ByteArrayOutputStream output=new ByteArrayOutputStream();
        J1979LiveNativeReplay.Result live=new J1979LiveUsbCapture(link).runRequest(profile,hello,key,output,()->true);
        assert live.captureIntegrityVerified&&live.deviceCredentialAuthenticated&&live.clientProofSent&&live.completed();
        assert link.writes==2&&link.closed&&Arrays.equals(raw,output.toByteArray());
        challenge[challenge.length-1]^=1;
        Sim denied=new Sim(challenge);
        J1979LiveNativeReplay.Result failed=new J1979LiveUsbCapture(denied).runRequest(profile,hello,key,new ByteArrayOutputStream(),()->true);
        assert !failed.captureIntegrityVerified&&!failed.deviceCredentialAuthenticated&&denied.writes==1&&denied.closed;
        Sim revoked=new Sim(challenge);
        J1979LiveNativeReplay.Result cancelled=new J1979LiveUsbCapture(revoked).runRequest(profile,hello,key,new ByteArrayOutputStream(),()->false);
        assert cancelled.cancelled&&!cancelled.captureIntegrityVerified&&revoked.writes==0&&revoked.closed;
    }
}
