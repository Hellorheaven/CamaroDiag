package com.camarodiag.connectors;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.zip.CRC32;

/** One CU/1 capture on a caller-owned, explicitly selected USB link.
 * Frame integrity is not full semantic campaign validation or physical provenance.
 */
public final class J1979UsbCapture {
    public interface Link extends AutoCloseable {
        int read(byte[] data,int offset,int size,int timeoutMs) throws IOException;
        void write(byte[] data,int timeoutMs) throws IOException;
        void checkNoBufferedInput() throws IOException;
        @Override void close() throws IOException;
    }
    public static final class Result {
        public final boolean deviceCredentialAuthenticated,frameIntegrityVerified;
        public final boolean evidenceAuthenticated=false,physicalVehicleVerified=false;
        public final int records;
        public final String error,observedVin;
        public final boolean captureIntegrityVerified;
        public final List<J1979CampaignSemantics.Step> steps;
        public final J1979CampaignSemantics.Summary summary;
        Result(boolean authenticated,boolean framed,int records,String error,List<J1979CampaignSemantics.Step> steps,
               J1979CampaignSemantics.Summary summary) {
            this.deviceCredentialAuthenticated=authenticated;this.frameIntegrityVerified=framed;
            this.records=records;this.error=error;this.steps=Collections.unmodifiableList(new ArrayList<>(steps));
            this.summary=framed?summary:null;captureIntegrityVerified=this.summary!=null;
            String vin=null;for(J1979CampaignSemantics.Step s:steps)if(s.observedVin()!=null)vin=s.observedVin();
            observedVin=vin;
        }
    }
    private final Link link;
    private final LongSupplier clock;
    private boolean used;
    public J1979UsbCapture(Link link){this(link,()->System.nanoTime()/1_000_000L);}
    J1979UsbCapture(Link link,LongSupplier clock){
        this.link=Objects.requireNonNull(link);this.clock=Objects.requireNonNull(clock);
    }
    private int remaining(long started,int budget)throws IOException{
        long spent=clock.getAsLong()-started;
        if(spent<0||spent>=budget)throw new SocketTimeoutException("CU/1 deadline exceeded");
        return budget-(int)spent;
    }
    private byte[] exact(int size,long started,int budget,OutputStream raw)throws IOException{
        byte[] data=new byte[size];int offset=0;
        while(offset<size){
            int n=link.read(data,offset,size-offset,remaining(started,budget));
            if(n<=0||n>size-offset)throw new IOException("Truncated CU/1 capture");
            if(raw!=null)raw.write(data,offset,n);
            offset+=n;remaining(started,budget);
        }
        return data;
    }
    private static int u(byte b){return b&255;}
    private static long le32(byte[] data,int i){
        return (long)u(data[i])|((long)u(data[i+1])<<8)|((long)u(data[i+2])<<16)|((long)u(data[i+3])<<24);
    }
    public synchronized Result run(int tx,int rx,boolean extended,int budget,byte[] key,
                                   OutputStream raw)throws IOException{
        return runRequest(J1979UsbAuth.hello(tx,rx,extended,budget),budget,key,raw);
    }
    synchronized Result runRequest(byte[] hello,int budget,byte[] key,OutputStream raw)throws IOException{
        if(used)throw new IOException("Fresh CU/1 session required");
        if(raw==null||key==null||key.length!=32||budget<1||budget>600000)
            throw new IllegalArgumentException("Invalid capture parameters");
        used=true;
        boolean authenticated=false,framed=false;
        int records=0;
        String error=null;
        List<J1979CampaignSemantics.Step> steps=new ArrayList<>();
        J1979CampaignSemantics.Summary summary=null;
        try {
            long start=clock.getAsLong();
            link.write(hello,remaining(start,5000));
            byte[] challenge=exact(J1979UsbAuth.CHALLENGE_SIZE,start,5000,null);
            byte[] proof=J1979UsbAuth.proof(hello,challenge,key);
            authenticated=true;
            link.checkNoBufferedInput();
            link.write(proof,remaining(start,5000));
            start=clock.getAsLong();int limit=budget+15000;
            long[] counts=new long[6];
            for(int sequence=0;sequence<2048;sequence++){
                byte[] head=exact(8,start,limit,raw);
                int kind=u(head[3]),len=u(head[6])|(u(head[7])<<8);
                if(head[0]!='C'||head[1]!='J'||head[2]!=1||
                   (u(head[4])|(u(head[5])<<8))!=sequence||len>4110)
                    throw new IOException("Invalid CJ/1 frame");
                byte[] tail=exact(len+4,start,limit,raw);
                CRC32 crc=new CRC32();crc.update(head);crc.update(tail,0,len);
                if(crc.getValue()!=le32(tail,len))throw new IOException("CJ/1 CRC mismatch");
                if(sequence==0){
                    if(kind!=1||len!=13||!Arrays.equals(Arrays.copyOf(tail,13),Arrays.copyOfRange(hello,4,17)))
                        throw new IOException("Unexpected CJ/1 campaign profile");
                }else if(kind==2){
                    int state=u(tail[0]);
                    if(len<9||state<1||state>5)throw new IOException("Invalid CJ/1 observation");
                    int req=u(tail[1]),res=u(tail[6])|(u(tail[7])<<8);
                    if(req<1||req>7||res>4095||len!=8+req+res||((state<=3)!=(res>0)))
                        throw new IOException("Invalid CJ/1 observation length");
                    steps.add(new J1979CampaignSemantics.Step(sequence,state,le32(tail,2),
                        Arrays.copyOfRange(tail,8,8+req),Arrays.copyOfRange(tail,8+req,len)));
                    counts[state]++;
                }else if(kind==3){
                    if(len!=22||u(tail[0])<1||u(tail[0])>5||le32(tail,2)!=sequence-1||
                       le32(tail,6)!=counts[1]||le32(tail,10)!=counts[2]||
                       le32(tail,14)!=counts[3]||le32(tail,18)!=counts[4])
                        throw new IOException("Inconsistent CJ/1 summary");
                    link.checkNoBufferedInput();remaining(start,limit);
                    records++;framed=true;
                    summary=J1979CampaignSemantics.verify(steps,u(tail[0]),u(tail[1]));
                    break;
                }else throw new IOException("Unexpected CJ/1 record");
                records++;
            }
            if(!framed)throw new IOException("CJ/1 record limit exceeded");
        }catch(IOException failure){error=failure.getMessage();framed=false;}
        finally{
            try{link.close();}catch(IOException failure){framed=false;if(error==null)error=failure.getMessage();}
            try{raw.flush();}catch(IOException failure){framed=false;if(error==null)error=failure.getMessage();}
        }
        return new Result(authenticated,framed,records,error,steps,summary);
    }
}
