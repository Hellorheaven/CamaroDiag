package com.camarodiag.connectors;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.zip.CRC32;

/** CF/1 framing with an explicit separate semantic replay boundary. A valid
 * CRC/sequence/terminal does not establish recipe consistency or provenance. */
public final class J1979FreezeUsbCapture {
    public static final int MAX_STEPS=8*(8+2+247)+2,MAX_BODY=4108;
    public static final long MAX_CAPTURE=(MAX_STEPS+2L)*(MAX_BODY+12);
    private static final String[] OUTCOMES={"","RECEIVED","TIMEOUT","TRANSPORT_ERROR",
        "BUDGET","CONTEXT_CHANGED","LATE_RESPONSE"};
    private static final String[] STOPS={null,"BUDGET_EXHAUSTED","TRANSPORT_ERROR","CONTEXT_CHANGED",
        "VIN_UNVERIFIED","VIN_MISMATCH","FINAL_VIN_UNVERIFIED","FINAL_VIN_MISMATCH"};
    private static final String[] STATES={"UNAVAILABLE","ABSENT","COMPLETE","PARTIAL","CHANGED"};

    public static final class Observation {
        public final String outcome;
        public final boolean attempted;
        public final long durationMs;
        private final byte[] request,response;
        private Observation(byte[] request,byte[] response,String outcome,boolean attempted,long durationMs){
            this.request=request.clone();this.response=response==null?null:response.clone();
            this.outcome=outcome;this.attempted=attempted;this.durationMs=durationMs;
        }
        public byte[] request(){return request.clone();}
        public byte[] response(){return response==null?null:response.clone();}
    }

    public static final class Terminal {
        public final String stopReason;
        public final int visitedFrames,steps;
        public final boolean completed,identityRechecked,allFramesStable;
        public final long durationMs;
        public final List<String> frameStates;
        private Terminal(String stopReason,int visitedFrames,int steps,int flags,long durationMs,List<String> frameStates){
            this.stopReason=stopReason;this.visitedFrames=visitedFrames;this.steps=steps;
            completed=(flags&1)!=0;identityRechecked=(flags&2)!=0;allFramesStable=(flags&4)!=0;
            this.durationMs=durationMs;this.frameStates=Collections.unmodifiableList(new ArrayList<>(frameStates));
        }
    }

    public static final class Result {
        public final boolean deviceCredentialAuthenticated,clientProofSent,frameIntegrityVerified,cancelled;
        public final boolean evidenceAuthenticated=false,physicalVehicleVerified=false,permitsProgramming=false;
        public final J1979FreezeUsbAuth.Profile profile;
        public final List<Observation> observations;
        public final Terminal terminal;
        public final String error;
        private Result(boolean authenticated,boolean proofSent,boolean framed,boolean cancelled,
                       J1979FreezeUsbAuth.Profile profile,List<Observation> observations,Terminal terminal,String error){
            deviceCredentialAuthenticated=authenticated;clientProofSent=proofSent;frameIntegrityVerified=framed;
            this.cancelled=cancelled;this.profile=profile;this.terminal=terminal;this.error=error;
            this.observations=Collections.unmodifiableList(new ArrayList<>(observations));
        }
    }

    private final J1979UsbCapture.Link link;
    private final LongSupplier clock;
    private boolean used;
    public J1979FreezeUsbCapture(J1979UsbCapture.Link link){this(link,()->System.nanoTime()/1_000_000L);}
    J1979FreezeUsbCapture(J1979UsbCapture.Link link,LongSupplier clock){
        this.link=Objects.requireNonNull(link);this.clock=Objects.requireNonNull(clock);
    }
    private static int u(byte b){return b&255;}
    private static int le16(byte[] bytes,int offset){return u(bytes[offset])|(u(bytes[offset+1])<<8);}
    private static long le32(byte[] bytes,int offset){
        return (long)u(bytes[offset])|((long)u(bytes[offset+1])<<8)|
            ((long)u(bytes[offset+2])<<16)|((long)u(bytes[offset+3])<<24);
    }
    private static int header(byte[] head,int sequence)throws IOException{
        if(head.length!=8||head[0]!='C'||head[1]!='F'||head[2]!=1||u(head[3])<1||u(head[3])>3||
           le16(head,4)!=sequence||le16(head,6)<1||le16(head,6)>MAX_BODY)
            throw new IOException("Invalid CF/1 header");
        return le16(head,6);
    }
    private static byte[] body(byte[] head,byte[] tail,int length)throws IOException{
        if(tail.length!=length+4)throw new IOException("Truncated CF/1 record");
        CRC32 crc=new CRC32();crc.update(head);crc.update(tail,0,length);
        if(crc.getValue()!=le32(tail,length))throw new IOException("CF/1 CRC mismatch");
        return Arrays.copyOf(tail,length);
    }
    private static Observation observation(byte[] bytes)throws IOException{
        if(bytes.length<10||u(bytes[0])<1||u(bytes[0])>6||u(bytes[1])>1||
           u(bytes[2])<2||u(bytes[2])>3||bytes[3]!=0)
            throw new IOException("Invalid CF/1 observation");
        int state=u(bytes[0]),size=u(bytes[2]),responseSize=le16(bytes,8);
        boolean attempted=bytes[1]==1,received=state==1||state==6;
        long duration=le32(bytes,4);
        if(responseSize>4095||bytes.length!=10+size+responseSize||duration>3600000||
           received!=(responseSize>0)||((received||state==2||state==3)&&!attempted)||
           ((state==4||state==5)&&attempted))
            throw new IOException("Inconsistent CF/1 observation");
        return new Observation(Arrays.copyOfRange(bytes,10,10+size),
            received?Arrays.copyOfRange(bytes,10+size,bytes.length):null,OUTCOMES[state],attempted,duration);
    }
    private static Terminal terminal(byte[] body,J1979FreezeUsbAuth.Profile profile,List<Observation> rows)throws IOException{
        if(body.length!=20||u(body[0])>=STOPS.length||u(body[1])>profile.frames().length||
           (u(body[2])&~7)!=0||body[3]!=0||le32(body,4)!=rows.size()||rows.isEmpty())
            throw new IOException("Malformed CF/1 terminal");
        long duration=le32(body,8),sum=0;
        for(Observation row:rows)sum+=row.durationMs;
        if(duration>3600000||sum>duration)throw new IOException("Inconsistent CF/1 duration");
        int visited=u(body[1]);List<String> states=new ArrayList<>();
        for(int i=0;i<8;i++){
            int state=u(body[12+i]);
            if(state>=STATES.length||(i>=visited&&state!=0))throw new IOException("Invalid CF/1 frame status");
            if(i<visited)states.add(STATES[state]);
        }
        return new Terminal(STOPS[u(body[0])],visited,rows.size(),u(body[2]),duration,states);
    }
    private static byte[] exact(InputStream stream,int count)throws IOException{
        byte[] bytes=new byte[count];int offset=0;
        while(offset<count){
            int n=stream.read(bytes,offset,count-offset);
            if(n<=0||n>count-offset)throw new EOFException("Truncated CF/1 stream");
            offset+=n;
        }
        return bytes;
    }

    public static Result decode(InputStream stream,J1979FreezeUsbAuth.Profile expected){
        J1979FreezeUsbAuth.Profile profile=null;
        Terminal summary=null;
        List<Observation> rows=new ArrayList<>();
        String error=null;
        try{
            for(int seq=0;seq<MAX_STEPS+2;seq++){
                byte[] head=exact(stream,8);int length=header(head,seq);
                byte[] payload=body(head,exact(stream,length+4),length);
                int kind=u(head[3]);
                if(seq==0){
                    if(kind!=1)throw new IOException("Missing CF/1 plan");
                    profile=J1979FreezeUsbAuth.Profile.decode(payload);
                    if(expected!=null&&!Arrays.equals(payload,expected.encode()))
                        throw new IOException("CF/1 plan differs from approved scope");
                }else if(kind==2){
                    if(rows.size()>=MAX_STEPS)throw new IOException("CF/1 observation limit exceeded");
                    rows.add(observation(payload));
                }else if(kind==3){
                    if(profile==null)throw new IOException("Missing CF/1 plan");
                    summary=terminal(payload,profile,rows);
                    if(stream.read()!=-1)throw new IOException("Trailing CF/1 bytes");
                    break;
                }else throw new IOException("Duplicate CF/1 plan");
            }
            if(summary==null)throw new IOException("Incomplete CF/1 capture");
        }catch(IOException failure){error=failure.getMessage()==null?"CF/1 read failed":failure.getMessage();}
        return new Result(false,false,error==null,false,profile,rows,summary,error);
    }

    private int remaining(long start,int budget)throws IOException{
        long elapsed=clock.getAsLong()-start;
        if(elapsed<0||elapsed>=budget)throw new SocketTimeoutException("CU/2 deadline exceeded");
        return budget-(int)elapsed;
    }
    private byte[] readExact(int count,long start,int budget,OutputStream raw,OutputStream copy)throws IOException{
        byte[] bytes=new byte[count];int offset=0;
        while(offset<count){
            int n=link.read(bytes,offset,count-offset,remaining(start,budget));
            if(n<=0||n>count-offset)throw new IOException("Truncated CU/2 stream");
            if(raw!=null)raw.write(bytes,offset,n);
            if(copy!=null)copy.write(bytes,offset,n);
            offset+=n;remaining(start,budget);
        }
        return bytes;
    }
    private static void current(BooleanSupplier allowed)throws IOException{
        if(!allowed.getAsBoolean())throw new IOException("CU/2 session revoked");
    }
    public synchronized Result run(J1979FreezeUsbAuth.Profile profile,byte[] key,OutputStream raw,
                                   BooleanSupplier allowed)throws IOException{
        return runRequest(profile,J1979FreezeUsbAuth.hello(profile),key,raw,allowed);
    }
    synchronized Result runRequest(J1979FreezeUsbAuth.Profile profile,byte[] hello,byte[] key,
                                   OutputStream raw,BooleanSupplier allowed)throws IOException{
        if(used)throw new IOException("Fresh CU/2 session required");
        if(profile==null||key==null||key.length!=32||raw==null||allowed==null)
            throw new IllegalArgumentException("Invalid CU/2 capture parameters");
        used=true;boolean authenticated=false,sent=false;
        String error=null;ByteArrayOutputStream copy=new ByteArrayOutputStream();
        try{
            current(allowed);long start=clock.getAsLong();
            link.write(hello,remaining(start,5000));
            byte[] challenge=readExact(68,start,5000,null,null);
            byte[] proof=J1979FreezeUsbAuth.proof(hello,challenge,key);authenticated=true;
            try{
                current(allowed);link.checkNoBufferedInput();
                link.write(proof,remaining(start,5000));sent=true;
            }finally{Arrays.fill(proof,(byte)0);}
            start=clock.getAsLong();int limit=profile.budgetMs+15000;
            boolean finished=false;
            for(int seq=0;seq<MAX_STEPS+2;seq++){
                current(allowed);
                byte[] head=readExact(8,start,limit,raw,copy);int length=header(head,seq);
                byte[] payload=body(head,readExact(length+4,start,limit,raw,copy),length);
                if(seq==0){
                    if(head[3]!=1||!Arrays.equals(payload,profile.encode()))
                        throw new IOException("CF/1 scope differs from CU/2 plan");
                }else if(head[3]==1)throw new IOException("Duplicate CF/1 plan");
                if(head[3]==3){
                    current(allowed);link.checkNoBufferedInput();remaining(start,limit);finished=true;break;
                }
            }
            if(!finished)throw new IOException("CF/1 record limit exceeded");
        }catch(IOException failure){error=failure.getMessage()==null?"CU/2 exchange failed":failure.getMessage();}
        finally{
            try{link.close();}catch(IOException failure){if(error==null)error="USB close failed";}
            try{raw.flush();}catch(IOException failure){if(error==null)error="Capture flush failed";}
        }
        Result decoded=decode(new ByteArrayInputStream(copy.toByteArray()),profile);
        boolean cancelled=!allowed.getAsBoolean();
        return new Result(authenticated,sent,authenticated&&sent&&!cancelled&&error==null&&decoded.frameIntegrityVerified,
            cancelled,decoded.profile,decoded.observations,decoded.terminal,
            error!=null?error:cancelled?"CU/2 cancelled":decoded.error);
    }
}
