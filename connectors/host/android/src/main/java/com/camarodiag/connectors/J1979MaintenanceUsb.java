package com.camarodiag.connectors;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Dedicated CU/4 credential exchange and original CM/1 journal capture.
 * The host cannot send arbitrary ECU requests or retry the proof. */
public final class J1979MaintenanceUsb {
    public static final int HELLO_SIZE=67,CHALLENGE_SIZE=68,PROOF_SIZE=36,RESULT_SIZE=12;
    private final J1979UsbCapture.Link link;
    private final LongSupplier clock;
    private boolean used;
    public J1979MaintenanceUsb(J1979UsbCapture.Link link){this(link,()->System.nanoTime()/1_000_000L);}
    J1979MaintenanceUsb(J1979UsbCapture.Link link,LongSupplier clock){
        this.link=Objects.requireNonNull(link);this.clock=Objects.requireNonNull(clock);
    }
    private static void require(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
    private static boolean any(byte[] b,int start,int end){
        int bits=0;for(int i=start;i<end;i++)bits|=b[i];return bits!=0;
    }
    private static Map<String,Object> map(Object... pairs){
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    public static final class Profile {
        public final int operation,tx,rx,budgetMs;
        public final boolean extended;
        public final String vin;
        public Profile(int operation,int tx,int rx,boolean extended,String vin,int budgetMs){
            if((operation!=1&&operation!=2)||
               !(extended?tx==0x18da10f1&&rx==0x18daf110:tx==0x7e0&&rx==0x7e8)||
               budgetMs<1000||budgetMs>60000||vin==null||!vin.matches("[A-HJ-NPR-Z0-9]{17}"))
                throw new IllegalArgumentException("Invalid primary ECM maintenance scope");
            this.operation=operation;this.tx=tx;this.rx=rx;this.extended=extended;this.vin=vin;this.budgetMs=budgetMs;
        }
        public byte[] encode(){
            return ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN).put((byte)operation)
                .put((byte)(extended?1:0)).putInt(tx).putInt(rx).putInt(budgetMs)
                .put(vin.getBytes(StandardCharsets.US_ASCII)).array();
        }
        public Map<String,Object> view(){
            return map("operation",operation==1?"CLEAR":"EVAP","extended",extended,
                "request_can_id",Integer.toHexString(tx).toUpperCase(Locale.ROOT),
                "response_can_id",Integer.toHexString(rx).toUpperCase(Locale.ROOT),
                "budget_ms",(long)budgetMs,"expected_vin",vin);
        }
    }
    static byte[] hello(Profile p,byte[] nonce)throws IOException{return hello(p,nonce,false);}
    private static byte[] hello(Profile p,byte[] nonce,boolean archive)throws IOException{
        require(nonce!=null&&nonce.length==32&&any(nonce,0,32),"Invalid CU/4 nonce");
        return ByteBuffer.allocate(HELLO_SIZE).put(new byte[]{'C','U',(byte)(archive?5:4),1}).put(p.encode()).put(nonce).array();
    }
    private static boolean header(byte[] bytes,int size,int op){return header(bytes,size,op,4);}
    private static boolean header(byte[] bytes,int size,int op,int version){
        return bytes!=null&&bytes.length==size&&bytes[0]=='C'&&bytes[1]=='U'&&bytes[2]==version&&bytes[3]==op;
    }
    static byte[] proof(byte[] request,byte[] challenge,byte[] key)throws IOException{return proof(request,challenge,key,false);}
    private static byte[] proof(byte[] request,byte[] challenge,byte[] key,boolean archive)throws IOException{
        int version=archive?5:4;
        require(header(request,HELLO_SIZE,1,version)&&header(challenge,CHALLENGE_SIZE,2,version)&&key!=null&&key.length==32&&
            any(request,35,67)&&any(challenge,4,36),"Invalid CU/4 transcript");
        byte[] transcript=new byte[96];transcript[0]=(byte)(archive?'R':'M');
        System.arraycopy(request,4,transcript,1,63);System.arraycopy(challenge,4,transcript,64,32);
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            byte[] expected=mac.doFinal(transcript);
            try{require(MessageDigest.isEqual(expected,Arrays.copyOfRange(challenge,36,68)),"CU/4 device proof rejected");}
            finally{Arrays.fill(expected,(byte)0);}
            transcript[0]=(byte)(archive?'r':'m');byte[] answer=mac.doFinal(transcript);
            try{return ByteBuffer.allocate(PROOF_SIZE).put(new byte[]{'C','U',(byte)version,3}).put(answer).array();}
            finally{Arrays.fill(answer,(byte)0);}
        }catch(GeneralSecurityException failure){throw new IOException("CU/4 cryptography unavailable",failure);}
        finally{Arrays.fill(transcript,(byte)0);}
    }
    private int remaining(long start,int budget,BooleanSupplier allowed)throws IOException{
        require(allowed.getAsBoolean(),"CU/4 owner revoked");
        long elapsed=clock.getAsLong()-start;
        if(elapsed<0||elapsed>=budget)throw new SocketTimeoutException("CU/4 deadline exceeded");
        return budget-(int)elapsed;
    }
    private byte[] read(int count,long start,int budget,BooleanSupplier allowed,
                        OutputStream raw,ByteArrayOutputStream copy)throws IOException{
        byte[] bytes=new byte[count];int offset=0;
        while(offset<count){
            int n=link.read(bytes,offset,count-offset,remaining(start,budget,allowed));
            require(n>0&&n<=count-offset,"Truncated CU/4 stream");
            if(copy!=null){
                require(copy.size()+n<=J1979MaintenanceJournal.MAX_JOURNAL,"CM/1 size limit");
                copy.write(bytes,offset,n);
            }
            if(raw!=null)raw.write(bytes,offset,n);
            offset+=n;remaining(start,budget,allowed);
        }
        return bytes;
    }
    public synchronized Map<String,Object> run(Profile profile,byte[] key,OutputStream raw,BooleanSupplier allowed)throws IOException{
        byte[] nonce=new byte[32];new SecureRandom().nextBytes(nonce);
        return run(profile,key,raw,allowed,nonce);
    }
    synchronized Map<String,Object> run(Profile profile,byte[] key,OutputStream raw,
                                       BooleanSupplier allowed,byte[] nonce)throws IOException{
        require(!used&&profile!=null&&key!=null&&key.length==32&&raw!=null&&allowed!=null,"Fresh CU/4 session required");
        byte[] request=hello(profile,nonce);used=true;
        long began=clock.getAsLong();
        boolean authenticated=false,attempted=false,sent=false;
        Boolean envelopeRecovery=null;String error=null;
        Map<String,Object> inspection=null;
        ByteArrayOutputStream copy=new ByteArrayOutputStream();
        try{
            long start=clock.getAsLong();link.write(request,remaining(start,5000,allowed));
            byte[] challenge=read(CHALLENGE_SIZE,start,5000,allowed,null,null);
            byte[] answer=proof(request,challenge,key);authenticated=true;
            try{
                link.checkNoBufferedInput();int timeout=remaining(start,5000,allowed);
                attempted=true;link.write(answer,timeout);sent=true;
            }finally{Arrays.fill(answer,(byte)0);}
            start=clock.getAsLong();int budget=profile.budgetMs+40000;
            byte[] envelope=read(RESULT_SIZE,start,budget,allowed,null,null);
            require(header(envelope,RESULT_SIZE,4)&&(envelope[4]==2||envelope[4]==3)&&
                envelope[5]==0&&envelope[6]==0&&envelope[7]==0,"Invalid CU/4 result envelope");
            long length=ByteBuffer.wrap(envelope,8,4).order(ByteOrder.LITTLE_ENDIAN).getInt()&0xffffffffL;
            require(length>0&&length<=J1979MaintenanceJournal.MAX_JOURNAL,"Invalid CU/4 journal length");
            envelopeRecovery=(envelope[4]&1)!=0;
            read((int)length,start,budget,allowed,raw,copy);
            remaining(start,budget,allowed);link.checkNoBufferedInput();remaining(start,budget,allowed);
            inspection=J1979MaintenanceJournal.inspect(copy.toByteArray(),profile.vin);
            require(profile.view().equals(inspection.get("scope")),"CM/1 differs from authenticated CU/4 scope");
            require(envelopeRecovery.equals(inspection.get("recovery_required")),"CU/4 recovery envelope differs from journal");
        }catch(IOException failure){error=failure.getMessage()==null?"CU/4 exchange failed":failure.getMessage();inspection=null;}
        finally{
            try{link.close();}catch(IOException failure){if(error==null)error="USB close failed";}
            try{raw.flush();}catch(IOException failure){if(error==null)error="Journal flush failed";}
        }
        boolean cancelled=!allowed.getAsBoolean();
        if(cancelled&&error==null)error="CU/4 context revoked";
        if(error!=null||cancelled)inspection=null;
        Object terminal=inspection==null?null:inspection.get("terminal");
        boolean unknown=attempted&&(terminal==null||"UNCERTAIN".equals(((Map<?,?>)terminal).get("outcome")));
        return map("schema","camarodiag-functional-j1979-native-maintenance-v1","protocol","SAE-J1979",
            "source","LIVE_USB_CU4","scope",profile.view(),"device_credential_authenticated",authenticated,
            "proof_transmission_attempted",attempted,"client_proof_sent",sent,"cancelled",cancelled,
            "journal_transfer_verified",inspection!=null,"journal_bytes_received",copy.size(),
            "terminal_present",terminal!=null,"recovery_required",attempted&&(inspection==null||
                Boolean.TRUE.equals(inspection.get("recovery_required"))),"command_outcome_unknown",unknown,
            "declared_recovery_in_envelope",envelopeRecovery,"inspection",inspection,"physical_vehicle_verified",false,
            "evidence_authenticated",false,"operator_consent_independently_verified",false,"permits_automatic_retry",false,
            "permits_programming",false,"host_elapsed_ms",Math.max(0,clock.getAsLong()-began),"error",error);
    }
    public synchronized Map<String,Object> runArchive(Profile profile,byte[] key,OutputStream raw,
                                                       BooleanSupplier allowed)throws IOException{
        require(!used&&profile!=null&&key!=null&&key.length==32&&raw!=null&&allowed!=null,"Fresh CU/5 session required");
        byte[] nonce=new byte[32];new SecureRandom().nextBytes(nonce);
        byte[] request=hello(profile,nonce,true);used=true;
        long began=clock.getAsLong();boolean authenticated=false;
        Map<String,Object> declared=null,inspection=null;
        String error=null;
        ByteArrayOutputStream copy=new ByteArrayOutputStream();
        try{
            long start=clock.getAsLong();link.write(request,remaining(start,5000,allowed));
            byte[] challenge=read(CHALLENGE_SIZE,start,5000,allowed,null,null);
            byte[] answer=proof(request,challenge,key,true);authenticated=true;
            try{link.checkNoBufferedInput();link.write(answer,remaining(start,5000,allowed));}
            finally{Arrays.fill(answer,(byte)0);}
            start=clock.getAsLong();int budget=40000;
            byte[] head=read(RESULT_SIZE,start,budget,allowed,null,null);
            require(header(head,RESULT_SIZE,4,5)&&(head[4]&~7)==0&&head[5]>=1&&head[5]<=4&&
                head[6]==0&&head[7]==0,"Invalid CU/5 archive header");
            long length=ByteBuffer.wrap(head,8,4).order(ByteOrder.LITTLE_ENDIAN).getInt()&0xffffffffL;
            boolean terminal=(head[4]&1)!=0,damaged=(head[4]&2)!=0,recovery=(head[4]&4)!=0;
            require(length>=47&&length<=J1979MaintenanceJournal.MAX_JOURNAL&&(!terminal||head[5]>=2)&&
                ((terminal&&!damaged)||recovery),"Invalid CU/5 archive declaration");
            declared=map("bytes",(int)length,"records",(int)head[5],"terminal_present",terminal,
                "damaged_tail",damaged,"recovery_required",recovery);
            read((int)length,start,budget,allowed,raw,copy);
            link.checkNoBufferedInput();remaining(start,budget,allowed);
            Map<String,Object> result=J1979MaintenanceJournal.inspect(copy.toByteArray(),profile.vin);
            require(profile.view().equals(result.get("scope"))&&Boolean.valueOf(terminal).equals(result.get("terminal_present"))&&
                ((java.util.List<?>)result.get("records")).size()==head[5]&&
                (!Boolean.TRUE.equals(result.get("recovery_required"))||recovery),"CM/1 archive differs from declaration");
            inspection=result;
        }catch(IOException failure){error=failure.getMessage()==null?"CU/5 exchange failed":failure.getMessage();}
        finally{
            try{link.close();}catch(IOException failure){if(error==null)error="USB close failed";}
            try{raw.flush();}catch(IOException failure){if(error==null)error="Journal flush failed";}
        }
        if(!allowed.getAsBoolean()&&error==null)error="CU/5 context revoked";
        if(error!=null)inspection=null;
        return map("schema","camarodiag-j1979-native-maintenance-archive-v1","protocol","SAE-J1979",
            "source","READ_ONLY_USB_CU5","scope",profile.view(),"device_credential_authenticated",authenticated,
            "archive_transfer_verified",inspection!=null,"journal_bytes_received",copy.size(),
            "declared_archive",declared,"inspection",inspection,"recovery_required",inspection==null||
                Boolean.TRUE.equals(declared.get("recovery_required")),"maintenance_command_sent",false,
            "physical_vehicle_verified",false,"physical_stop_independently_verified",false,"evidence_authenticated",false,
            "permits_automatic_retry",false,"permits_programming",false,"host_elapsed_ms",Math.max(0,clock.getAsLong()-began),
            "error",error);
    }

}
