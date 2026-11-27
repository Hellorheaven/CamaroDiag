package com.camarodiag.connectors;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.CRC32;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class J1979MaintenanceUsbTest {
    private static final String VIN="1G1FA1RXXR0123456";
    private static final byte[] KEY=new byte[32],NONCE=new byte[32],DEVICE=new byte[32];
    static {
        for(int i=0;i<32;i++)KEY[i]=(byte)i;
        Arrays.fill(NONCE,(byte)'H');Arrays.fill(DEVICE,(byte)'D');
    }
    private static byte[] concat(byte[]... parts)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(byte[] b:parts)out.write(b);
        return out.toByteArray();
    }
    private static byte[] hex(String s){
        byte[] b=new byte[s.length()/2];
        for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return b;
    }
    private static byte[] mac(byte[] data)throws Exception{
        Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(KEY,"HmacSHA256"));return m.doFinal(data);
    }
    private static byte[] row(int stage,int status,String cmd,byte[] response,int index){
        byte[] req=hex(cmd);
        return ByteBuffer.allocate(18+response.length).order(ByteOrder.LITTLE_ENDIAN)
            .put((byte)stage).put((byte)status).put((byte)req.length)
            .put(req[0]).put(req.length==2?req[1]:(byte)0).put((byte)0).putShort((short)response.length)
            .putInt(index*10).putInt(index*10+5).putShort((short)1800).put(response).array();
    }
    private static byte[] summary(int outcome,int reason,int count,int nrc,int a,int b){
        return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .put((byte)outcome).put((byte)reason).put((byte)count).put((byte)nrc)
            .put((byte)a).put((byte)b).putShort((short)0).putInt(count*10-5).array();
    }
    private static byte[] record(J1979MaintenanceUsb.Profile profile,int number,int kind,byte[] summary,
                                 byte[][] rows,int count)throws IOException{
        ByteArrayOutputStream body=new ByteArrayOutputStream();body.write(profile.encode());
        if(kind!=1){
            body.write(summary);
            for(int i=0;i<count;i++)body.write(rows[i]);
        }
        byte[] payload=body.toByteArray();
        byte[] framed=ByteBuffer.allocate(12+payload.length+4).order(ByteOrder.LITTLE_ENDIAN)
            .put(new byte[]{'C','M',1,(byte)kind,(byte)number,(byte)count,0,0})
            .putInt(payload.length).put(payload).array();
        CRC32 crc=new CRC32();crc.update(framed,0,framed.length-4);
        ByteBuffer.wrap(framed).order(ByteOrder.LITTLE_ENDIAN).putInt(framed.length-4,(int)crc.getValue());
        return framed;
    }
    private static byte[][] fixture(J1979MaintenanceUsb.Profile profile,int failure)throws IOException{
        boolean clear=profile.operation==1;
        String[] plan=clear?new String[]{"0902","03","07","0A","0101","010D","010C","0902","010D","010C","04",
            "03","07","0A","0101","0902"}:new String[]{"0902","0800","010D","010C","0902","0800","010D","010C","0801"};
        int[] stages=clear?new int[]{1,2,2,2,2,2,2,4,4,4,6,7,7,7,7,7}:new int[]{1,2,2,2,4,4,4,4,6};
        int baseline=clear?7:4,intent=clear?10:8,count=failure==0?plan.length:intent+1;
        byte[][] rows=new byte[count][];
        for(int i=0;i<count;i++){
            String cmd=plan[i];byte[] reply;
            int status=1;
            switch(cmd){
                case "0902":reply=concat(hex("490201"),VIN.getBytes(StandardCharsets.US_ASCII));break;
                case "03":reply=hex(i<intent?"43010123":"4300");break;
                case "07":reply=hex("4700");break;
                case "0A":reply=hex("4A00");break;
                case "0101":reply=hex("410100000000");break;
                case "010D":reply=hex("410D00");break;
                case "010C":reply=hex("410C0000");break;
                case "0800":reply=hex("480080000000");break;
                case "04":reply=hex("44");break;
                case "0801":reply=hex("4801");break;
                default:throw new AssertionError(cmd);
            }
            if(i==intent&&failure!=0){
                if(failure==1){status=2;reply=new byte[]{0x7f,(byte)(clear?4:8),0x22};}
                else{status=3;reply=new byte[0];}
            }
            rows[i]=row(stages[i],status,cmd,reply,i);
        }
        int a=failure==0?(clear?255:63):(clear?183:55);
        int b=failure==0?(clear?31:16):failure==1?16:48;
        return new byte[][]{
            record(profile,0,1,null,rows,0),
            record(profile,1,2,summary(0,0,baseline,0,clear?144:16,0),rows,baseline),
            record(profile,2,3,summary(0,0,intent,0,clear?177:49,0),rows,intent),
            record(profile,3,4,summary(failure==0?(clear?5:6):failure==1?2:3,
                failure==0?0:failure==1?11:10,count,failure==1?0x22:0,a,b),rows,count)
        };
    }
    private static void reject(byte[] bytes,boolean prefix,String vin)throws IOException{
        try{
            if(prefix)J1979MaintenanceJournal.inspectPrefix(bytes,vin);
            else J1979MaintenanceJournal.inspect(bytes,vin);
            throw new AssertionError("Invalid journal accepted");
        }catch(IOException expected){/* expected */}
    }
    private static final class Sim implements J1979UsbCapture.Link {
        final byte[] challenge,result,expectedHello,expectedProof;
        byte[] available=new byte[0];int offset,writes;
        boolean closed,failProof,revoked;
        Sim(byte[] challenge,byte[] result,byte[] hello,byte[] proof){
            this.challenge=challenge;this.result=result;expectedHello=hello;expectedProof=proof;
        }
        @Override public int read(byte[] out,int pos,int n,int timeout){
            int take=Math.min(7,Math.min(n,available.length-offset));
            System.arraycopy(available,offset,out,pos,take);offset+=take;return take;
        }
        @Override public void write(byte[] bytes,int timeout)throws IOException{
            writes++;
            if(writes==1){assert Arrays.equals(bytes,expectedHello);available=challenge;offset=0;}
            else if(writes==2){
                assert Arrays.equals(bytes,expectedProof);
                if(failProof)throw new IOException("Uncertain proof write");
                available=result;offset=0;
            }else throw new AssertionError("CU/4 retry");
        }
        @Override public void checkNoBufferedInput()throws IOException{
            if(offset!=available.length)throw new IOException("Unexpected trailing bytes");
        }
        @Override public void close(){closed=true;}
    }
    private static byte[] envelope(byte[] raw,boolean recovery)throws IOException{
        byte[] head=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(new byte[]{'C','U',4,4,(byte)(recovery?3:2),0,0,0}).putInt(raw.length).array();
        return concat(head,raw);
    }
    private static void runCapture(J1979MaintenanceUsb.Profile profile,byte[] raw,boolean recovery)throws Exception{
        byte[] hello=J1979MaintenanceUsb.hello(profile,NONCE);
        byte[] transcript=concat(new byte[]{'M'},Arrays.copyOfRange(hello,4,hello.length),DEVICE);
        byte[] challenge=concat(new byte[]{'C','U',4,2},DEVICE,mac(transcript));
        transcript[0]='m';
        byte[] proof=concat(new byte[]{'C','U',4,3},mac(transcript));
        assert Arrays.equals(J1979MaintenanceUsb.proof(hello,challenge,KEY),proof);
        for(int which=0;which<5;which++){
            byte[] ch=challenge.clone(),response=envelope(raw,recovery);
            if(which==1)ch[36]^=1;
            if(which==2)response=Arrays.copyOf(response,response.length-1);
            if(which==4)response[4]=(byte)(recovery?2:3);
            Sim sim=new Sim(ch,response,hello,proof);sim.failProof=which==3;
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            J1979MaintenanceUsb capture=new J1979MaintenanceUsb(sim,()->0);
            Map<String,Object> report=capture.run(profile,KEY,output,()->true,NONCE);
            assert sim.closed;
            assert Boolean.FALSE.equals(report.get("permits_automatic_retry"));
            if(which==0){
                assert Boolean.TRUE.equals(report.get("journal_transfer_verified"));
                assert Boolean.valueOf(recovery).equals(report.get("recovery_required"));
                assert Arrays.equals(output.toByteArray(),raw);
            }else{
                assert Boolean.FALSE.equals(report.get("journal_transfer_verified"));
                assert Boolean.valueOf(which!=1).equals(report.get("recovery_required"));
                assert sim.writes==(which==1?1:2);
            }
            try{capture.run(profile,KEY,output,()->true,NONCE);throw new AssertionError("Retry allowed");}
            catch(IOException expected){/* expected */}
        }
    }
    private static byte[] archiveEnvelope(byte[] raw,int records,int flags)throws IOException{
        byte[] head=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(new byte[]{'C','U',5,4,(byte)flags,(byte)records,0,0}).putInt(raw.length).array();
        CRC32 crc=new CRC32();crc.update(raw);
        byte[] tail=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(new byte[]{'C','U',5,5}).putInt(raw.length).putInt((int)crc.getValue()).array();
        return concat(head,raw,tail);
    }
    private static void archive(J1979MaintenanceUsb.Profile profile,byte[][] records,boolean recovery)throws Exception{
        byte[] hello=concat(new byte[]{'C','U',5,1},profile.encode(),NONCE);
        byte[] transcript=concat(new byte[]{'R'},Arrays.copyOfRange(hello,4,hello.length),DEVICE);
        byte[] challenge=concat(new byte[]{'C','U',5,2},DEVICE,mac(transcript));
        transcript[0]='r';
        byte[] proof=concat(new byte[]{'C','U',5,3},mac(transcript));
        for(int count=1;count<=records.length;count++){
            byte[] raw=concat(Arrays.copyOf(records,count));
            boolean terminal=count==4;
            boolean required=!terminal||recovery;
            int flags=(terminal?1:0)|(required?4:0);
            for(int scenario=0;scenario<12;scenario++){
                byte[] ch=challenge.clone(),response=archiveEnvelope(raw,count,flags);
                switch(scenario){
                    case 1:ch[36]^=1;break;
                    case 2:response=Arrays.copyOf(response,response.length-12);break;
                    case 3:response[response.length-1]^=1;break;
                    case 4:response=Arrays.copyOf(response,response.length+1);break;
                    case 5:response[5]=(byte)(count==4?3:4);break;
                    case 6:response[2]=4;break;
                    case 7:response[4]=(byte)(flags^1);break;
                    case 8:response[12+10]^=1;break;
                    case 9:response=Arrays.copyOf(response,12+raw.length/2);break;
                    default:break;
                }
                Sim sim=new Sim(ch,response,hello,proof);sim.failProof=scenario==10;
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                J1979MaintenanceUsb capture=new J1979MaintenanceUsb(sim,()->0);
                final boolean revoke=scenario==11;
                Map<String,Object> report=capture.runArchive(profile,KEY,output,
                    ()->!revoke||sim.writes<2,NONCE);
                assert sim.closed;
                assert Boolean.FALSE.equals(report.get("maintenance_command_sent"));
                assert Boolean.FALSE.equals(report.get("physical_vehicle_verified"));
                assert Boolean.FALSE.equals(report.get("permits_automatic_retry"));
                assert Boolean.valueOf(scenario==0).equals(report.get("archive_transfer_verified"));
                assert Boolean.valueOf(scenario==0).equals(report.get("archive_close_receipt_verified"));
                assert Boolean.valueOf(scenario!=0||required).equals(report.get("recovery_required"));
                if(scenario==0){
                    assert Arrays.equals(output.toByteArray(),raw);
                    assert J1979MaintenanceJournal.inspect(raw,VIN).equals(report.get("inspection"));
                }else assert report.get("inspection")==null;
                assert sim.writes==(scenario==1?1:2);
                try{capture.runArchive(profile,KEY,output,()->true,NONCE);throw new AssertionError("CU/5 reuse");}
                catch(IOException expected){/* expected */}
            }
        }
    }
    public static void main(String[] args)throws Exception{
        for(int operation=1;operation<=2;operation++){
            J1979MaintenanceUsb.Profile profile=new J1979MaintenanceUsb.Profile(operation,0x7e0,0x7e8,false,VIN,60000);
            for(int failure=0;failure<=2;failure++){
                byte[][] records=fixture(profile,failure);
                byte[] full=concat(records);
                Map<String,Object> report=J1979MaintenanceJournal.inspect(full,VIN);
                Map<?,?> terminal=(Map<?,?>)report.get("terminal");
                String outcome=failure==0?(operation==1?"ACKNOWLEDGED_READBACK":"ACKNOWLEDGED_EVAP"):
                    failure==1?"REFUSED":"UNCERTAIN";
                assert outcome.equals(terminal.get("outcome"));
                assert Boolean.valueOf(failure==2).equals(report.get("recovery_required"));
                byte[] prefix=concat(records[0],records[1],records[2]);
                for(int length:new int[]{0,1,5,12,records[3].length-1}){
                    Map<String,Object> partial=J1979MaintenanceJournal.inspectPrefix(
                        concat(prefix,Arrays.copyOf(records[3],length)),VIN);
                    assert Boolean.TRUE.equals(partial.get("prefix_integrity_verified"));
                    assert Boolean.FALSE.equals(partial.get("record_consistency_verified"));
                    assert Boolean.FALSE.equals(partial.get("terminal_present"));
                    assert Boolean.TRUE.equals(partial.get("recovery_required"));
                    assert Integer.valueOf(prefix.length).equals(partial.get("verified_prefix_bytes"));
                    assert Integer.valueOf(length).equals(partial.get("unverified_trailing_bytes"));
                }
                reject(full,true,VIN);reject(prefix,true,"1FA6P8TH0L5100000");
                byte[] corrupt=prefix.clone();corrupt[corrupt.length-1]^=1;
                reject(corrupt,true,VIN);reject(concat(prefix,new byte[]{'X'}),true,VIN);
                runCapture(profile,full,failure==2);
                archive(profile,records,failure==2);
            }
        }
    }
}
