package com.camarodiag.connectors;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** CU/1 command codec, separate from public discovery. No key provisioning. */
public final class J1979UsbAuth {
    private J1979UsbAuth() {}
    public static final int HELLO_SIZE=49, CHALLENGE_SIZE=68, PROOF_SIZE=36;
    public static boolean physicalPair(int tx,int rx,boolean extended) {
        if(!extended)return tx>=0x7e0&&tx<=0x7e7&&rx==tx+8;
        if(tx<0||tx>0x1fffffff||(tx&0x1fff00ff)!=0x18da00f1)return false;
        int source=(tx>>>8)&255;
        return source<0xf0&&rx==(0x18daf100|source);
    }
    private static boolean nonzero(byte[] data,int start,int end) {
        int acc=0;for(int i=start;i<end;i++)acc|=data[i];return acc!=0;
    }
    public static byte[] hello(int tx,int rx,boolean extended,int budgetMs) {
        byte[] nonce=new byte[32];new SecureRandom().nextBytes(nonce);
        return hello(tx,rx,extended,budgetMs,nonce);
    }
    static byte[] hello(int tx,int rx,boolean extended,int budgetMs,byte[] nonce) {
        if(!physicalPair(tx,rx,extended)||budgetMs<1||budgetMs>600000||
           nonce==null||nonce.length!=32||!nonzero(nonce,0,32))
            throw new IllegalArgumentException("Invalid CU/1 profile or nonce");
        return ByteBuffer.allocate(HELLO_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .put(new byte[]{'C','U',1,1}).putInt(tx).putInt(rx)
            .put((byte)(extended?1:0)).putInt(budgetMs).put(nonce).array();
    }
    private static boolean header(byte[] data,int size,int op) {
        return data!=null&&data.length==size&&data[0]=='C'&&data[1]=='U'&&data[2]==1&&data[3]==op;
    }
    public static byte[] proof(byte[] request,byte[] challenge,byte[] key) throws IOException {
        if(!header(request,HELLO_SIZE,1)||!header(challenge,CHALLENGE_SIZE,2)||
           key==null||key.length!=32||!nonzero(request,17,49)||!nonzero(challenge,4,36))
            throw new IOException("Invalid CU/1 transcript");
        ByteBuffer p=ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN);
        int tx=p.getInt(4),rx=p.getInt(8),ext=p.get(12)&255,budget=p.getInt(13);
        if(ext>1||!physicalPair(tx,rx,ext==1)||budget<1||budget>600000)
            throw new IOException("Invalid CU/1 profile");
        byte[] transcript=new byte[78];
        System.arraycopy(request,4,transcript,1,45);
        System.arraycopy(challenge,4,transcript,46,32);
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key,"HmacSHA256"));
            transcript[0]='S';
            byte[] server=mac.doFinal(transcript);
            if(!MessageDigest.isEqual(server,Arrays.copyOfRange(challenge,36,68)))
                throw new IOException("Device authentication failed");
            transcript[0]='C';
            byte[] result=new byte[PROOF_SIZE];result[0]='C';result[1]='U';result[2]=1;result[3]=3;
            System.arraycopy(mac.doFinal(transcript),0,result,4,32);
            return result;
        } catch(GeneralSecurityException error) {
            throw new IOException("CU/1 cryptography unavailable",error);
        } finally { Arrays.fill(transcript,(byte)0); }
    }
}
