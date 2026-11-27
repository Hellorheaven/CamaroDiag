package com.camaradiag.connectors;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

import static com.camaradiag.connectors.J1979LiveUsbAuth.require;
import static com.camaradiag.connectors.J1979LiveUsbAuth.u;

/** Independent CL/1 application evidence replay. No device, credential or
 * physical provenance can be established from a saved stream. */
public final class J1979LiveNativeReplay {
    public static final int MAX_SAMPLES=10000,MAX_STEPS=11010,MAX_RAW=16*1024*1024;
    public static final int OBSERVATION_SIZE=26,FINAL_SIZE=128,MAX_BODY=OBSERVATION_SIZE+4095;
    public static final int MAX_CAPTURE=MAX_RAW+(MAX_STEPS+2)*38+1024;
    private static final String[] PHASES={"","INITIAL_VIN","INVENTORY","PERIODIC_VIN","SAMPLE","FINAL_VIN"};
    private static final String[] STATES={"","PASSED","RAW","NEGATIVE","TIMEOUT","FAILED"};
    private static final String[] STOPS={null,"INVALID_SCOPE","BUDGET_EXHAUSTED","CONTEXT_CHANGED",
        "TRANSPORT_ERROR","VIN_UNVERIFIED","VIN_MISMATCH","SELECTED_PID_NOT_VERIFIED",
        "SELECTED_RAW_PID_NO_RESPONSE","SINK_STOPPED","INVALID_SEQUENCE","EVIDENCE_LIMIT"};
    private static final char[] HEX="0123456789ABCDEF".toCharArray();
    private J1979LiveNativeReplay(){}

    static int u16(byte[] data,int at){return u(data[at])|(u(data[at+1])<<8);}
    static long u32(byte[] data,int at){
        return (long)u(data[at])|((long)u(data[at+1])<<8)|((long)u(data[at+2])<<16)|((long)u(data[at+3])<<24);
    }
    static String hex(byte[] data){
        char[] output=new char[data.length*2];
        for(int i=0;i<data.length;i++){output[2*i]=HEX[u(data[i])>>>4];output[2*i+1]=HEX[u(data[i])&15];}
        return new String(output);
    }
    private static Map<String,Object> map(Object... values){
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);
        return Collections.unmodifiableMap(result);
    }
    private static List<Integer> integers(int[] values){
        List<Integer> result=new ArrayList<>();
        for(int value:values)result.add(value);
        return Collections.unmodifiableList(result);
    }
    private static Map<String,Object> profileView(J1979LiveUsbAuth.Profile p){
        return map("request_can_id",Integer.toHexString(p.tx).toUpperCase(Locale.ROOT),
            "response_can_id",Integer.toHexString(p.rx).toUpperCase(Locale.ROOT),
            "extended",p.extended,"expected_vin",p.vin,"selected_pids",integers(p.pids()),
            "requested_cycles",p.cycles,"interval_ms",p.intervalMs,"budget_ms",p.budgetMs,"raw_only",true);
    }
    static int header(byte[] head,int seq)throws IOException{
        require(head.length==8&&head[0]=='C'&&head[1]=='L'&&head[2]==1&&
            u(head[3])>=1&&u(head[3])<=3&&u16(head,4)==seq,"Invalid CL/1 header");
        int len=u16(head,6);require(len>=1&&len<=MAX_BODY,"Invalid CL/1 record size");return len;
    }
    static byte[] body(byte[] head,byte[] tail,int length)throws IOException{
        require(tail.length==length+4,"Truncated CL/1 record");
        CRC32 crc=new CRC32();crc.update(head);crc.update(tail,0,length);
        require(crc.getValue()==u32(tail,length),"CL/1 CRC mismatch");
        return Arrays.copyOf(tail,length);
    }
    private static byte[] exact(InputStream stream,int count)throws IOException{
        byte[] data=new byte[count];int offset=0;
        while(offset<count){
            int n=stream.read(data,offset,count-offset);
            if(n<=0||n>count-offset)throw new EOFException("Truncated CL/1 record");
            offset+=n;
        }
        return data;
    }

    private static final class Event {
        final int phase,state,cycle,pid,timeout;
        final boolean accepted;
        final long start,end,exchangeMs;
        final byte[] request,response;
        Event(byte[] body)throws IOException{
            require(body.length>=OBSERVATION_SIZE,"Truncated CL/1 observation");
            phase=u(body[0]);state=u(body[1]);accepted=body[2]==1;cycle=u16(body,4);pid=u(body[6]);
            start=u32(body,8);end=u32(body,12);timeout=u16(body,16);exchangeMs=u32(body,20);
            int size=u16(body,18);
            require(phase>=1&&phase<=5&&state>=1&&state<=5&&u(body[2])<=1&&body[3]==0&&body[7]==0&&
                start<=end&&end<=604000&&timeout>=1&&timeout<=4000&&size<=4095&&
                body.length==OBSERVATION_SIZE+size&&(state<=3)==(size>0)&&(accepted||state==5),
                "Malformed CL/1 observation");
            request=Arrays.copyOfRange(body,24,26);response=Arrays.copyOfRange(body,26,body.length);
        }
        int shape(){
            if(response.length==0)return state==4||state==5?state:0;
            if(u(response[0])==0x7f)return response.length==3&&response[1]==request[0]&&
                response[2]!=0&&u(response[2])!=0x78?3:0;
            if(request[0]==9&&request[1]==2)return response.length==20&&u(response[0])==0x49&&
                response[1]==2&&response[2]==1&&J1979LiveUsbAuth.validVin(
                new String(response,3,17,StandardCharsets.US_ASCII))?1:0;
            if(request[0]!=1||response.length<3||u(response[0])!=0x41||response[1]!=request[1])return 0;
            int pid=u(request[1]);
            if(pid%32==0)return response.length==6&&(pid!=224||(response[5]&1)==0)?1:0;
            return 2;
        }
        Map<String,Object> view(){
            return map("phase",PHASES[phase],"status",STATES[state],"accepted",accepted,
                "cycle",phase==4?cycle:null,"pid",phase==4?pid:null,
                "started_elapsed_ms",start,"elapsed_ms",end,"duration_ms",end-start,
                "exchange_duration_ms",exchangeMs,"timeout_ms",timeout,"request_hex",hex(request),
                "response_hex",response.length==0?null:hex(response));
        }
        Map<String,Object> sample(){
            return map("cycle",cycle,"pid",pid,"elapsed_ms",end,"status",STATES[state],
                "response_hex",response.length==0?null:hex(response));
        }
    }

    private static final class Replay {
        final J1979LiveUsbAuth.Profile profile;
        final int[] selected;
        final boolean[] advertised=new boolean[256];
        final int[] rawPerPid=new int[32];
        final List<Map<String,Object>> rows=new ArrayList<>(),observations=new ArrayList<>();
        int stage=1,stop,steps,samples,raw,negative,timeouts,rawBytes,cycles,base,index;
        long duration,lastIdentity,cycleStart=-1;
        boolean initial,finalIdentity,inventoryComplete,completed,earlyIdentity;
        Replay(J1979LiveUsbAuth.Profile profile){this.profile=profile;selected=profile.pids();}
        void abort(int reason,long now){stop=reason;stage=7;duration=now;}
        int phase(long now){
            if(stage==7||now>=profile.budgetMs)return 7;
            if(stage==4&&index==0){
                if(cycleStart>=0&&now-cycleStart<profile.intervalMs)return 6;
                if(now-lastIdentity>=30000)return 3;
            }
            return stage;
        }
        int maximumReply(int phase){
            switch(phase){
                case 1:case 3:case 5:return 20;
                case 2:return 6;
                case 4:return 4095;
                default:return 0;
            }
        }
        boolean canRetain(int phase){
            int maximum=maximumReply(phase);
            return maximum>0&&maximum<=MAX_RAW-rawBytes;
        }
        boolean capacityStop(long now){
            int p=phase(now);
            if(p==4&&index==0&&!earlyIdentity&&now-lastIdentity>=29000)p=3;
            if(stage==4&&index==0&&cycleStart>=0&&now-cycleStart<profile.intervalMs+25L)return false;
            return p>=1&&p<=5&&!canRetain(p);
        }
        byte[] request(int phase)throws IOException{
            switch(phase){
                case 1:case 3:case 5:return new byte[]{9,2};
                case 2:return new byte[]{1,(byte)base};
                case 4:return new byte[]{1,(byte)selected[index]};
                default:throw new IOException("CL/1 request outside sequence");
            }
        }
        boolean allAdvertised(){
            for(int pid:selected)if(!advertised[pid])return false;
            return true;
        }
        void observe(Event e)throws IOException{
            require(e.start>=duration&&stage!=7&&steps<MAX_STEPS,"Overlapping or post-terminal CL/1 observation");
            int phase=phase(e.start);
            if(e.phase==3&&phase==4&&index==0&&!earlyIdentity)phase=3;
            require(phase==e.phase&&phase>=1&&phase<=5,"Unexpected CL/1 phase");
            require(canRetain(phase),"CL/1 request exceeds remaining evidence capacity");
            byte[] req=request(phase);
            require(Arrays.equals(req,e.request)&&e.timeout<=profile.budgetMs-e.start&&
                e.cycle==(phase==4?cycles+1:0)&&e.pid==(phase==4?u(req[1]):0),
                "CL/1 request outside approved scope");
            if(phase==4&&index==0)cycleStart=e.start;
            steps++;duration=e.end;observations.add(e.view());
            if(e.end>=profile.budgetMs){
                require(!e.accepted,"Late response accepted");abort(2,e.end);return;
            }
            if(e.end-e.start>e.timeout||e.exchangeMs>e.timeout){
                require(!e.accepted,"Exchange exceeded deadline");abort(4,e.end);return;
            }
            if(!e.accepted){
                require(e.state==5&&e.response.length==0,"Rejected event carries data");abort(4,e.end);return;
            }
            require(e.state>=1&&e.state<=4&&e.shape()==e.state,"CL/1 response not correlated");
            rawBytes+=e.response.length;require(rawBytes<=MAX_RAW,"CL/1 raw evidence limit");
            if(phase==1||phase==3||phase==5){
                if(e.state!=1){abort(5,e.end);return;}
                if(!new String(e.response,3,17,StandardCharsets.US_ASCII).equals(profile.vin)){
                    abort(6,e.end);return;
                }
                lastIdentity=e.end;
                if(phase==1){initial=true;stage=2;}
                else if(phase==3){earlyIdentity=true;stage=4;}
                else {
                    finalIdentity=true;stage=7;
                    for(int i=0;i<selected.length;i++)if(rawPerPid[i]==0){stop=8;return;}
                    completed=true;
                }
                return;
            }
            if(phase==2){
                if(e.state!=1){abort(7,e.end);return;}
                for(int bit=0;bit<31;bit++)if((u(e.response[2+bit/8])&(0x80>>(bit%8)))!=0)
                    advertised[base+bit+1]=true;
                boolean more=(e.response[5]&1)!=0;inventoryComplete=!more;
                if(allAdvertised())stage=4;
                else if(!more||base==224)abort(7,e.end);
                else base+=32;
                return;
            }
            require(phase==4&&(e.state==2||e.state==3||e.state==4),"Invalid CL/1 sample state");
            samples++;
            if(e.state==2){raw++;rawPerPid[index]++;}
            else if(e.state==3)negative++;else timeouts++;
            rows.add(e.sample());
            if(++index==selected.length){
                index=0;earlyIdentity=false;cycles++;
                if(cycles==profile.cycles)stage=5;
            }
        }
        byte[] summaryWire(){
            byte[] wire=new byte[FINAL_SIZE];
            ByteBuffer b=ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
            b.put((byte)stop).put((byte)((initial?1:0)|(finalIdentity?2:0)|(inventoryComplete?4:0)|(completed?8:0)));
            b.putShort((short)cycles).putInt(steps).putInt(samples).putInt(raw).putInt(negative).putInt(timeouts)
                .putInt((int)duration).putInt(rawBytes);
            for(int pid=1;pid<256;pid++)if(advertised[pid])wire[32+pid/8]|=(byte)(1<<(pid%8));
            b.position(64);for(int count:rawPerPid)b.putShort((short)count);
            return wire;
        }
        Map<String,Object> finish(byte[] body)throws IOException{
            require(body.length==FINAL_SIZE&&u(body[0])<STOPS.length&&body[0]!=1&&body[0]!=9&&body[0]!=10&&
                (body[1]&~15)==0,"Malformed CL/1 terminal");
            long end=u32(body,24);require(end>=duration&&end<=604000,"Invalid terminal duration");
            if(stage!=7){
                require((body[0]==2||body[0]==3||body[0]==11)&&(body[0]!=2||profile.budgetMs-end<=1)&&
                    (body[0]!=11||capacityStop(end)),"Unsupported terminal state");
                abort(body[0],end);
            }
            require(Arrays.equals(body,summaryWire()),"CL/1 terminal counters differ from replay");
            List<Integer> ids=new ArrayList<>();
            for(int pid=1;pid<256;pid++)if(advertised[pid])ids.add(pid);
            List<Map<String,Object>> counts=new ArrayList<>();
            for(int i=0;i<selected.length;i++)counts.add(map("pid",selected[i],"count",rawPerPid[i]));
            return map("stop_reason",STOPS[stop],"completed",completed,"initial_identity_verified",initial,
                "final_identity_verified",finalIdentity,"inventory_complete",inventoryComplete,"completed_cycles",cycles,
                "steps",steps,"samples",samples,"raw",raw,"negative",negative,"timeout",timeouts,"duration_ms",duration,
                "raw_bytes",rawBytes,"advertised_pids",Collections.unmodifiableList(ids),
                "raw_per_pid",Collections.unmodifiableList(counts));
        }
        Map<String,Object> prefix(){
            return map("initial_identity_verified_in_prefix",initial,"final_identity_observed_in_prefix",finalIdentity,
                "complete_observations",steps,"last_elapsed_ms",duration,"raw_bytes",rawBytes,
                "completed_cycles_in_prefix",cycles,"raw_samples_in_prefix",raw,"negative_samples_in_prefix",negative,
                "timeout_samples_in_prefix",timeouts,"observations",Collections.unmodifiableList(new ArrayList<>(observations)),
                "samples",Collections.unmodifiableList(new ArrayList<>(rows)));
        }
        Map<String,Object> result(){
            return map("schema","camarodiag-j1979-live-inspection-v1","verified",true,"raw_only",true,
                "declared_vin",profile.vin,"channel",map("kind","can","tx",profile.tx,"rx",profile.rx),
                "completed",completed,"final_identity_verified",finalIdentity,
                "samples",Collections.unmodifiableList(new ArrayList<>(rows)),
                "observations",Collections.unmodifiableList(new ArrayList<>(observations)),
                "physical_vehicle_verified",false,"authenticated",false);
        }
    }
    public static final class Result {
        public final J1979LiveUsbAuth.Profile profile;
        public final int observationsReceived;
        public final boolean captureIntegrityVerified,deviceCredentialAuthenticated,clientProofSent,cancelled;
        public final String error,source;
        public final Map<String,Object> summary,result;
        public final Long hostElapsedMs;
        Result(J1979LiveUsbAuth.Profile profile,int observationsReceived,boolean verified,boolean authenticated,
               boolean sent,boolean cancelled,String source,Map<String,Object> summary,Map<String,Object> result,
               String error,Long elapsed){
            this.profile=profile;this.observationsReceived=observationsReceived;captureIntegrityVerified=verified;
            deviceCredentialAuthenticated=authenticated;clientProofSent=sent;this.cancelled=cancelled;
            this.source=source;this.summary=verified?summary:null;this.result=verified?result:null;this.error=error;
            hostElapsedMs=elapsed;
        }
        public boolean completed(){return captureIntegrityVerified&&summary!=null&&Boolean.TRUE.equals(summary.get("completed"));}
        public Map<String,Object> report(){
            Map<String,Object> report=new LinkedHashMap<>(map("schema","camarodiag-functional-j1979-native-live-v1",
                "protocol","SAE-J1979","source",source,"profile",profile==null?null:profileView(profile),
                "capture_integrity_verified",captureIntegrityVerified,"device_credential_authenticated",deviceCredentialAuthenticated,
                "hardware_provenance_verified",false,"evidence_authenticated",false,"physical_vehicle_verified",false,
                "permits_programming",false,"observations_received",observationsReceived,"summary",summary,"result",result,"error",error));
            if(source.equals("LIVE_USB_CU3")){
                report.put("client_proof_sent",clientProofSent);report.put("cancelled",cancelled);
            }
            if(hostElapsedMs!=null)report.put("host_elapsed_ms",hostElapsedMs);
            return Collections.unmodifiableMap(report);
        }
    }
    /** Separate offline schema: complete records of an interrupted capture.
     * No terminal, transport or physical provenance is asserted. */
    public static Map<String,Object> inspectPrefix(byte[] bytes,String expectedVin)throws IOException{
        require(expectedVin!=null&&expectedVin.matches("[A-HJ-NPR-Z0-9]{17}"),"Independently supplied VIN required");
        require(bytes!=null&&bytes.length>0&&bytes.length<=MAX_CAPTURE,"Invalid CL/1 input size");
        int position=0,sequence=0;
        J1979LiveUsbAuth.Profile profile=null;Replay replay=null;
        String interruption="MISSING_TERMINAL";
        while(position<bytes.length){
            require(sequence<MAX_STEPS+2,"CL/1 record count exceeded");
            int remaining=bytes.length-position;
            byte[] head=Arrays.copyOfRange(bytes,position,position+Math.min(8,remaining));
            byte[] magic={'C','L',1};
            for(int i=0;i<Math.min(3,head.length);i++)require(head[i]==magic[i],"Invalid partial CL/1 header");
            if(head.length>=4)require(sequence==0?head[3]==1:head[3]==2||head[3]==3,"Invalid CL/1 record kind");
            if(head.length>=5)require(u(head[4])==(sequence&255),"Invalid partial CL/1 sequence");
            if(head.length>=6)require(u(head[5])==(sequence>>8),"Invalid partial CL/1 sequence");
            if(remaining<8){interruption="TRUNCATED_HEADER";break;}
            int len=header(head,sequence),kind=u(head[3]);
            require((kind!=1||len==67)&&(kind!=2||len>=26)&&(kind!=3||len==FINAL_SIZE),"Invalid CL/1 body length");
            int size=8+len+4;
            if(remaining<size){interruption="TRUNCATED_RECORD";break;}
            byte[] data=body(head,Arrays.copyOfRange(bytes,position+8,position+size),len);
            if(kind==1){
                profile=J1979LiveUsbAuth.Profile.decode(data);
                require(profile.vin.equals(expectedVin),"CL/1 plan differs from independent VIN");
                replay=new Replay(profile);
            }else if(kind==2){
                require(replay!=null,"Missing CL/1 plan");
                replay.observe(new Event(data));
            }else{
                require(replay!=null,"Missing CL/1 plan");
                replay.finish(data);
                require(position+size==bytes.length,"Trailing CL/1 bytes");
                throw new IOException("CL/1 contains a terminal record; use inspect");
            }
            position+=size;sequence++;
        }
        require(profile!=null&&replay!=null,"No complete CL/1 plan");
        require(sequence<=MAX_STEPS+1,"CL/1 record count exceeded");
        Map<String,Object> result=new LinkedHashMap<>(replay.prefix());
        result.putAll(map("schema","camarodiag-j1979-native-live-prefix-v1","protocol","SAE-J1979",
            "source","OFFLINE_CL1_PREFIX","profile",profileView(profile),"prefix_integrity_verified",true,
            "capture_integrity_verified",false,"execution_completed",false,"terminal_present",false,
            "expected_vin_independently_supplied",true,"complete_records",sequence,"last_complete_offset",position,
            "discarded_tail_bytes",bytes.length-position,"interruption",interruption,"physical_vehicle_verified",false,
            "hardware_provenance_verified",false,"evidence_authenticated",false,"permits_programming",false));
        return Collections.unmodifiableMap(result);
    }

    public static Result decode(InputStream stream,J1979LiveUsbAuth.Profile expected){
        J1979LiveUsbAuth.Profile profile=null;Replay replay=null;Map<String,Object> summary=null;
        int received=0,total=0;String error=null;
        try{
            for(int seq=0;seq<MAX_STEPS+2;seq++){
                byte[] head=exact(stream,8);int len=header(head,seq);
                total+=8+len+4;require(total<=MAX_CAPTURE,"CL/1 capture byte limit");
                byte[] body=body(head,exact(stream,len+4),len);int kind=u(head[3]);
                if(profile==null){
                    require(kind==1,"Missing CL/1 plan");profile=J1979LiveUsbAuth.Profile.decode(body);
                    require(expected==null||Arrays.equals(body,expected.encode()),"CL/1 scope differs from CU/3");
                    replay=new Replay(profile);
                }else if(kind==2){received++;replay.observe(new Event(body));}
                else if(kind==3){summary=replay.finish(body);require(stream.read()==-1,"Trailing CL/1 bytes");break;}
                else throw new IOException("Duplicate CL/1 plan");
            }
            require(profile!=null&&summary!=null,"Incomplete CL/1 capture");
        }catch(IOException|IllegalArgumentException e){
            error=e.getMessage()==null?"CL/1 replay failed":e.getMessage();summary=null;
        }
        return new Result(profile,received,error==null,false,false,false,"OFFLINE_CL1",summary,
            error==null?replay.result():null,error,null);
    }
}
