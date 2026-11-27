package com.camaradiag.connectors;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static com.camaradiag.connectors.J1979LiveUsbAuth.require;

/** One approved CU/3 session. Record exactly the received CL/1 bytes and
 * independently replay them before returning any numeric or completion claim. */
public final class J1979LiveUsbCapture {
    private final J1979UsbCapture.Link link;
    private final LongSupplier clock;
    private boolean used;
    public J1979LiveUsbCapture(J1979UsbCapture.Link link){this(link,()->System.nanoTime()/1_000_000L);}
    J1979LiveUsbCapture(J1979UsbCapture.Link link,LongSupplier clock){
        this.link=Objects.requireNonNull(link);this.clock=Objects.requireNonNull(clock);
    }
    private int remaining(long start,int budget)throws IOException{
        long elapsed=clock.getAsLong()-start;
        if(elapsed<0||elapsed>=budget)throw new SocketTimeoutException("CU/3 deadline exceeded");
        return budget-(int)elapsed;
    }
    private void current(BooleanSupplier allowed)throws IOException{
        require(allowed.getAsBoolean(),"CU/3 session revoked");
    }
    private byte[] exact(int count,long start,int budget,BooleanSupplier allowed,
                         OutputStream raw,ByteArrayOutputStream copy)throws IOException{
        byte[] bytes=new byte[count];int offset=0;
        while(offset<count){
            current(allowed);
            int n=link.read(bytes,offset,count-offset,remaining(start,budget));
            require(n>0&&n<=count-offset,"Truncated CU/3 stream");
            require(copy==null||copy.size()+n<=J1979LiveNativeReplay.MAX_CAPTURE,"CL/1 exceeds capture limit");
            if(raw!=null)raw.write(bytes,offset,n);
            if(copy!=null)copy.write(bytes,offset,n);
            offset+=n;current(allowed);remaining(start,budget);
        }
        return bytes;
    }
    public synchronized J1979LiveNativeReplay.Result run(J1979LiveUsbAuth.Profile profile,byte[] key,
        OutputStream raw,BooleanSupplier allowed)throws IOException{
        return runRequest(profile,J1979LiveUsbAuth.hello(profile),key,raw,allowed);
    }
    synchronized J1979LiveNativeReplay.Result runRequest(J1979LiveUsbAuth.Profile profile,byte[] hello,byte[] key,
        OutputStream raw,BooleanSupplier allowed)throws IOException{
        if(used)throw new IOException("Fresh CU/3 session required");
        if(profile==null||key==null||key.length!=32||raw==null||allowed==null)
            throw new IllegalArgumentException("Invalid CU/3 session parameters");
        require(hello!=null&&hello.length==J1979LiveUsbAuth.HELLO_SIZE&&
            Arrays.equals(Arrays.copyOfRange(hello,4,4+J1979LiveUsbAuth.AUTH_SIZE),profile.encodeAuth()),
            "CU/3 hello differs from approved scope");
        used=true;boolean authenticated=false,sent=false;String error=null;
        long began=clock.getAsLong();
        ByteArrayOutputStream copy=new ByteArrayOutputStream();
        try{
            current(allowed);long start=clock.getAsLong();
            link.write(hello,remaining(start,5000));
            byte[] challenge=exact(68,start,5000,allowed,null,null);
            byte[] proof=J1979LiveUsbAuth.proof(hello,challenge,key);authenticated=true;
            try{
                current(allowed);link.checkNoBufferedInput();
                link.write(proof,remaining(start,5000));sent=true;
            }finally{Arrays.fill(proof,(byte)0);}
            start=clock.getAsLong();int budget=profile.budgetMs+15000;
            boolean finished=false;
            for(int seq=0;seq<J1979LiveNativeReplay.MAX_STEPS+2;seq++){
                byte[] head=exact(8,start,budget,allowed,raw,copy);
                int len=J1979LiveNativeReplay.header(head,seq);
                byte[] body=J1979LiveNativeReplay.body(head,exact(len+4,start,budget,allowed,raw,copy),len);
                int kind=head[3]&255;
                if(seq==0){
                    require(kind==1&&Arrays.equals(body,profile.encode()),"CL/1 scope differs from CU/3");
                }else require(kind!=1,"Duplicate CL/1 plan");
                if(kind==3){
                    current(allowed);link.checkNoBufferedInput();remaining(start,budget);
                    finished=true;break;
                }
            }
            require(finished,"CL/1 record count exceeded");
        }catch(IOException failure){error=failure.getMessage()==null?"CU/3 exchange failed":failure.getMessage();}
        finally{
            try{link.close();}catch(IOException failure){if(error==null)error="USB close failed";}
            try{raw.flush();}catch(IOException failure){if(error==null)error="Capture flush failed";}
        }
        J1979LiveNativeReplay.Result decoded=J1979LiveNativeReplay.decode(new ByteArrayInputStream(copy.toByteArray()),profile);
        boolean cancelled=!allowed.getAsBoolean();
        boolean verified=authenticated&&sent&&!cancelled&&error==null&&decoded.captureIntegrityVerified;
        return new J1979LiveNativeReplay.Result(decoded.profile,decoded.observationsReceived,verified,authenticated,sent,
            cancelled,"LIVE_USB_CU3",decoded.summary,decoded.result,
            error!=null?error:cancelled?"CU/3 cancelled":decoded.error,Math.max(0,clock.getAsLong()-began));
    }
}
