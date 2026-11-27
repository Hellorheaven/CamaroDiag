package com.camaradiag.connectors;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Dedicated Android OTG CU/1 channel. Caller must own the selected device
 * and connection generation, local consent and private output. Never probe
 * discovery or fall back to ELM/AT after an authentication failure.
 */
public final class UsbJ1979 {
    private UsbJ1979() {}
    @FunctionalInterface
    private interface Session<T>{
        T run(J1979UsbCapture.Link link,byte[] key)throws IOException;
    }
    public static J1979UsbCapture.Result capture(UsbManager manager,UsbDevice device,
            int controlId,int dataId,int tx,int rx,boolean extended,int budget,byte[] credential,
            OutputStream raw,BooleanSupplier stillSelected) throws IOException {
        if(!J1979UsbAuth.physicalPair(tx,rx,extended)||budget<1||budget>600000)
            throw new IllegalArgumentException("Invalid CU/1 profile");
        return open(manager,device,controlId,dataId,credential,raw,stillSelected,
            (link,key)->new J1979UsbCapture(link).run(tx,rx,extended,budget,key,raw));
    }
    public static J1979FreezeUsbCapture.Result captureFreeze(UsbManager manager,UsbDevice device,
            int controlId,int dataId,J1979FreezeUsbAuth.Profile profile,byte[] credential,
            OutputStream raw,BooleanSupplier stillSelected)throws IOException{
        if(profile==null)throw new IllegalArgumentException("Explicit CU/2 profile required");
        return open(manager,device,controlId,dataId,credential,raw,stillSelected,
            (link,key)->new J1979FreezeUsbCapture(link).run(profile,key,raw,stillSelected));
    }
    private static <T> T open(UsbManager manager,UsbDevice device,
            int controlId,int dataId,byte[] credential,OutputStream raw,
            BooleanSupplier stillSelected,Session<T> session)throws IOException{
        if(manager==null||device==null||credential==null||credential.length!=32||raw==null||
           stillSelected==null||!stillSelected.getAsBoolean())
            throw new IllegalArgumentException("Explicit native USB context required");
        if(!manager.hasPermission(device))throw new IOException("USB permission required");
        UsbInterface control=null,data=null;
        for(int i=0;i<device.getInterfaceCount();i++){
            UsbInterface candidate=device.getInterface(i);
            if(candidate.getId()==controlId&&candidate.getInterfaceClass()==UsbConstants.USB_CLASS_COMM){
                if(control!=null)throw new IOException("Ambiguous CDC control");control=candidate;
            }
            if(candidate.getId()==dataId&&candidate.getInterfaceClass()==UsbConstants.USB_CLASS_CDC_DATA){
                if(data!=null)throw new IOException("Ambiguous CDC data");data=candidate;
            }
        }
        if(control==null||data==null||controlId==dataId)throw new IOException("CDC interfaces not supported");
        UsbEndpoint in=null,out=null;
        for(int i=0;i<data.getEndpointCount();i++){
            UsbEndpoint ep=data.getEndpoint(i);
            if(ep.getType()!=UsbConstants.USB_ENDPOINT_XFER_BULK)continue;
            if(ep.getDirection()==UsbConstants.USB_DIR_IN){
                if(in!=null)throw new IOException("Ambiguous CDC input");in=ep;
            }else{
                if(out!=null)throw new IOException("Ambiguous CDC output");out=ep;
            }
        }
        if(in==null||out==null||in.getMaxPacketSize()<1||in.getMaxPacketSize()>1024)
            throw new IOException("Invalid CDC endpoints");
        byte[] key=credential.clone();
        UsbDeviceConnection conn=manager.openDevice(device);
        if(conn==null){Arrays.fill(key,(byte)0);throw new IOException("Cannot open USB device");}
        boolean claimedControl=false,claimedData=false;
        try{
            if(!stillSelected.getAsBoolean())throw new IOException("USB context changed");
            claimedControl=conn.claimInterface(control,false);
            if(!claimedControl)throw new IOException("Cannot claim CDC control");
            claimedData=conn.claimInterface(data,false);
            if(!claimedData)throw new IOException("Cannot claim CDC data");
            byte[] line={0,(byte)0xc2,1,0,0,0,8};
            if(!stillSelected.getAsBoolean()||conn.controlTransfer(0x21,0x20,0,controlId,line,7,1000)!=7||
               !stillSelected.getAsBoolean()||conn.controlTransfer(0x21,0x22,1,controlId,null,0,1000)!=0)
                throw new IOException("CDC initialization rejected");
            final UsbEndpoint input=in,output=out;
            J1979UsbCapture.Link link=new J1979UsbCapture.Link(){
                private final byte[] pending=new byte[4096];
                private int cursor=0,limit=0;
                private boolean closed;
                private long now(){return System.nanoTime()/1_000_000L;}
                private void current()throws IOException{
                    if(closed||!stillSelected.getAsBoolean()||!manager.hasPermission(device)||
                       !device.equals(manager.getDeviceList().get(device.getDeviceName())))
                        throw new IOException("USB session disconnected or revoked");
                }
                @Override public int read(byte[] bytes,int off,int count,int timeout)throws IOException{
                    current();long start=now();
                    while(cursor==limit){
                        long spent=now()-start;
                        if(spent<0||spent>=timeout)throw new SocketTimeoutException("USB read deadline exceeded");
                        current();
                        int n=conn.bulkTransfer(input,pending,pending.length,(int)Math.min(100,timeout-spent));
                        current();
                        if(n<=0)continue;
                        if(n>pending.length)throw new IOException("Invalid USB transfer length");
                        cursor=0;limit=n;
                    }
                    int n=Math.min(count,limit-cursor);
                    System.arraycopy(pending,cursor,bytes,off,n);cursor+=n;return n;
                }
                @Override public void write(byte[] bytes,int timeout)throws IOException{
                    current();
                    if(conn.bulkTransfer(output,bytes,bytes.length,Math.min(timeout,1000))!=bytes.length)
                        throw new IOException("USB short write; no retry");
                    current();
                }
                @Override public void checkNoBufferedInput()throws IOException{
                    current();
                    if(cursor!=limit)throw new IOException("Unexpected buffered native USB bytes");
                    // CDC has no EOF; detect immediate trailing data only.
                    int n=conn.bulkTransfer(input,pending,pending.length,50);
                    current();
                    if(n>0)throw new IOException("Unexpected trailing native USB bytes");
                }
                @Override public void close(){closed=true;}
            };
            return session.run(link,key);
        }finally{
            Arrays.fill(key,(byte)0);
            if(claimedData)conn.releaseInterface(data);
            if(claimedControl)conn.releaseInterface(control);
            conn.close();
        }
    }
}
