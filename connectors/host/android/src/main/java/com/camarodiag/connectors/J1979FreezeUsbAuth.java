package com.camarodiag.connectors;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Canonical CU/2 plan, distinct from the CU/1 profile and discovery. */
public final class J1979FreezeUsbAuth {
    public static final int PLAN_SIZE=104,HELLO_SIZE=140,CHALLENGE_SIZE=68,PROOF_SIZE=36;
    private J1979FreezeUsbAuth(){}

    public static final class Profile {
        public final int tx,rx,budgetMs;
        public final boolean extended;
        public final String vin;
        private final int[] frames,pids;

        public Profile(int tx,int rx,boolean extended,int budgetMs,String vin,int[] frames,int[] pids){
            if(!J1979UsbAuth.physicalPair(tx,rx,extended)||budgetMs<1000||budgetMs>600000||
               vin==null||!vin.matches("[A-HJ-NPR-Z0-9]{17}")||frames==null||pids==null||
               frames.length<1||frames.length>8||pids.length>64)
                throw new IllegalArgumentException("Invalid CU/2 profile");
            this.frames=frames.clone();this.pids=pids.clone();
            boolean[] found=new boolean[256];
            for(int frame:this.frames){
                if(frame<0||frame>255||found[frame])throw new IllegalArgumentException("Invalid freeze frame selection");
                found[frame]=true;
            }
            Arrays.fill(found,false);
            for(int pid:this.pids){
                if(pid<1||pid>255||pid==2||pid%32==0||found[pid])
                    throw new IllegalArgumentException("Invalid freeze PID selection");
                found[pid]=true;
            }
            this.tx=tx;this.rx=rx;this.extended=extended;this.budgetMs=budgetMs;this.vin=vin;
        }
        public int[] frames(){return frames.clone();}
        public int[] pids(){return pids.clone();}
        public byte[] encode(){
            byte[] wire=new byte[PLAN_SIZE];
            ByteBuffer p=ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
            p.putInt(tx).putInt(rx).put((byte)(extended?1:0)).putInt(budgetMs)
                .put(vin.getBytes(StandardCharsets.US_ASCII)).put((byte)frames.length);
            for(int frame:frames)p.put((byte)frame);
            p.position(39);p.put((byte)pids.length);
            for(int pid:pids)p.put((byte)pid);
            return wire;
        }
        public static Profile decode(byte[] wire)throws IOException{
            if(wire==null||wire.length!=PLAN_SIZE||(wire[8]&255)>1||
               (wire[30]&255)<1||(wire[30]&255)>8||(wire[39]&255)>64)
                throw new IOException("Invalid CF/1 plan");
            ByteBuffer p=ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
            int[] frames=new int[wire[30]&255],pids=new int[wire[39]&255];
            for(int i=0;i<frames.length;i++)frames[i]=wire[31+i]&255;
            for(int i=0;i<pids.length;i++)pids[i]=wire[40+i]&255;
            try{
                Profile result=new Profile(p.getInt(0),p.getInt(4),wire[8]==1,p.getInt(9),
                    new String(wire,13,17,StandardCharsets.US_ASCII),frames,pids);
                if(!Arrays.equals(wire,result.encode()))throw new IOException("Noncanonical CF/1 plan");
                return result;
            }catch(IllegalArgumentException failure){throw new IOException("Invalid CF/1 profile",failure);}
        }
    }

    private static boolean nonzero(byte[] bytes,int from,int to){
        int any=0;for(int i=from;i<to;i++)any|=bytes[i];return any!=0;
    }
    private static boolean header(byte[] data,int size,int op){
        return data!=null&&data.length==size&&data[0]=='C'&&data[1]=='U'&&data[2]==2&&data[3]==op;
    }
    public static byte[] hello(Profile profile){
        byte[] nonce=new byte[32];new SecureRandom().nextBytes(nonce);return hello(profile,nonce);
    }
    static byte[] hello(Profile profile,byte[] nonce){
        if(profile==null||nonce==null||nonce.length!=32||!nonzero(nonce,0,32))
            throw new IllegalArgumentException("Invalid CU/2 nonce");
        return ByteBuffer.allocate(HELLO_SIZE).put(new byte[]{'C','U',2,1})
            .put(profile.encode()).put(nonce).array();
    }
    public static byte[] proof(byte[] request,byte[] challenge,byte[] key)throws IOException{
        if(!header(request,HELLO_SIZE,1)||!header(challenge,CHALLENGE_SIZE,2)||
           key==null||key.length!=32||!nonzero(request,108,140)||!nonzero(challenge,4,36))
            throw new IOException("Invalid CU/2 transcript");
        Profile.decode(Arrays.copyOfRange(request,4,108));
        byte[] transcript=new byte[169];
        System.arraycopy(request,4,transcript,1,136);
        System.arraycopy(challenge,4,transcript,137,32);
        try{
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key,"HmacSHA256"));
            transcript[0]='F';
            byte[] server=mac.doFinal(transcript);
            try{
                if(!MessageDigest.isEqual(server,Arrays.copyOfRange(challenge,36,68)))
                    throw new IOException("CU/2 server proof rejected");
            }finally{Arrays.fill(server,(byte)0);}
            transcript[0]='f';
            byte[] tag=mac.doFinal(transcript);
            try{
                return ByteBuffer.allocate(PROOF_SIZE).put(new byte[]{'C','U',2,3}).put(tag).array();
            }finally{Arrays.fill(tag,(byte)0);}
        }catch(GeneralSecurityException failure){throw new IOException("CU/2 cryptography unavailable",failure);}
        finally{Arrays.fill(transcript,(byte)0);}
    }
}
