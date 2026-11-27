package com.camaradiag.connectors;

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

/** CU/3 binds the exact raw Mode 01 recording scope to both nonces.
 * No implicit upgrade from a generic native USB connection. */
public final class J1979LiveUsbAuth {
    public static final int SCOPE_SIZE=58,PLAN_SIZE=67,AUTH_SIZE=71,HELLO_SIZE=107;
    public static final int CHALLENGE_SIZE=68,PROOF_SIZE=36,MAX_SAMPLES=10000;
    private J1979LiveUsbAuth(){}
    static void require(boolean value,String message)throws IOException{
        if(!value)throw new IOException(message);
    }
    static int u(byte b){return b&255;}
    static boolean validVin(String vin){return vin!=null&&vin.matches("[A-HJ-NPR-Z0-9]{17}");}
    static boolean nonzero(byte[] bytes,int from,int to){
        int value=0;for(int i=from;i<to;i++)value|=bytes[i];return value!=0;
    }
    public static final class Profile {
        public final int tx,rx,cycles,intervalMs,budgetMs;
        public final boolean extended;
        public final String vin;
        private final int[] pids;
        public Profile(int tx,int rx,boolean extended,String vin,int[] pids,int cycles,int intervalMs,int budgetMs){
            if(!J1979UsbAuth.physicalPair(tx,rx,extended)||!validVin(vin)||
               pids==null||pids.length<1||pids.length>32||cycles<1||cycles>1000||
               cycles*pids.length>MAX_SAMPLES||intervalMs<100||intervalMs>60000||
               budgetMs<1000||budgetMs>600000)throw new IllegalArgumentException("Invalid CU/3 live scope");
            this.pids=pids.clone();
            boolean[] seen=new boolean[256];
            for(int pid:this.pids){
                if(pid<1||pid>255||pid%32==0||seen[pid])
                    throw new IllegalArgumentException("Invalid selected raw PID");
                seen[pid]=true;
            }
            this.tx=tx;this.rx=rx;this.extended=extended;this.vin=vin;
            this.cycles=cycles;this.intervalMs=intervalMs;this.budgetMs=budgetMs;
        }
        public int[] pids(){return pids.clone();}
        public byte[] encode(){
            byte[] wire=new byte[PLAN_SIZE];
            ByteBuffer b=ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(tx).putInt(rx).put((byte)(extended?1:0)).put(vin.getBytes(StandardCharsets.US_ASCII))
                .put((byte)pids.length);
            for(int pid:pids)b.put((byte)pid);
            b.position(59);b.putShort((short)cycles).putShort((short)intervalMs).putInt(budgetMs);
            return wire;
        }
        public byte[] encodeAuth(){
            byte[] plan=encode();
            return ByteBuffer.allocate(AUTH_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .put(plan,0,9).putInt(budgetMs).put(plan,9,SCOPE_SIZE).array();
        }
        public static Profile decode(byte[] wire)throws IOException{
            require(wire!=null&&wire.length==PLAN_SIZE&&u(wire[8])<=1&&u(wire[26])>=1&&u(wire[26])<=32,
                "Invalid CL/1 plan");
            ByteBuffer b=ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
            int[] pids=new int[u(wire[26])];
            for(int i=0;i<pids.length;i++)pids[i]=u(wire[27+i]);
            try{
                Profile result=new Profile(b.getInt(0),b.getInt(4),wire[8]==1,
                    new String(wire,9,17,StandardCharsets.US_ASCII),pids,
                    b.getShort(59)&65535,b.getShort(61)&65535,b.getInt(63));
                require(Arrays.equals(wire,result.encode()),"Noncanonical CL/1 plan");
                return result;
            }catch(IllegalArgumentException e){throw new IOException("Invalid CL/1 profile",e);}
        }
        public static Profile decodeAuth(byte[] data)throws IOException{
            require(data!=null&&data.length==AUTH_SIZE,"Invalid CU/3 auth profile");
            byte[] plan=new byte[PLAN_SIZE];
            System.arraycopy(data,0,plan,0,9);System.arraycopy(data,13,plan,9,SCOPE_SIZE);
            Profile profile=decode(plan);
            require(Arrays.equals(data,profile.encodeAuth()),"Contradictory CU/3 budget");
            return profile;
        }
    }
    private static boolean header(byte[] bytes,int size,int op){
        return bytes!=null&&bytes.length==size&&bytes[0]=='C'&&bytes[1]=='U'&&bytes[2]==3&&bytes[3]==op;
    }
    public static byte[] hello(Profile profile){
        byte[] nonce=new byte[32];new SecureRandom().nextBytes(nonce);return hello(profile,nonce);
    }
    static byte[] hello(Profile profile,byte[] nonce){
        if(profile==null||nonce==null||nonce.length!=32||!nonzero(nonce,0,32))
            throw new IllegalArgumentException("Invalid CU/3 nonce");
        return ByteBuffer.allocate(HELLO_SIZE).put(new byte[]{'C','U',3,1})
            .put(profile.encodeAuth()).put(nonce).array();
    }
    public static byte[] proof(byte[] request,byte[] challenge,byte[] key)throws IOException{
        require(header(request,HELLO_SIZE,1)&&header(challenge,CHALLENGE_SIZE,2)&&
            key!=null&&key.length==32&&nonzero(request,4+AUTH_SIZE,HELLO_SIZE)&&nonzero(challenge,4,36),
            "Invalid CU/3 transcript");
        Profile.decodeAuth(Arrays.copyOfRange(request,4,4+AUTH_SIZE));
        byte[] transcript=new byte[1+AUTH_SIZE+64];
        System.arraycopy(request,4,transcript,1,AUTH_SIZE+32);
        System.arraycopy(challenge,4,transcript,1+AUTH_SIZE+32,32);
        try{
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key,"HmacSHA256"));
            transcript[0]='L';byte[] server=mac.doFinal(transcript);
            try{require(MessageDigest.isEqual(server,Arrays.copyOfRange(challenge,36,68)),
                "CU/3 server proof rejected");}
            finally{Arrays.fill(server,(byte)0);}
            transcript[0]='l';byte[] tag=mac.doFinal(transcript);
            try{return ByteBuffer.allocate(PROOF_SIZE).put(new byte[]{'C','U',3,3}).put(tag).array();}
            finally{Arrays.fill(tag,(byte)0);}
        }catch(GeneralSecurityException e){throw new IOException("CU/3 cryptography unavailable",e);}
        finally{Arrays.fill(transcript,(byte)0);}
    }
}
