package com.camarodiag.connectors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

/** Independent offline CM/1 replay. Never connects to a device or authorizes
 * a retry. CRC and sequence establish consistency, not provenance. */
public final class J1979MaintenanceJournal {
    public static final int HEADER=12,SCOPE=31,SUMMARY=20,ROW=18,MAX_ROWS=16,MAX_REPLY=512;
    public static final int MAX_RECORD=HEADER+SCOPE+SUMMARY+MAX_ROWS*(ROW+MAX_REPLY)+4,MAX_JOURNAL=4*MAX_RECORD;
    private static final String[] KINDS={"","OPEN","BASELINE","INTENT","TERMINAL"};
    private static final String[] OUTCOMES={"RUNNING","NOT_TESTED","REFUSED","UNCERTAIN",
        "ACKNOWLEDGED_PARTIAL","ACKNOWLEDGED_READBACK","ACKNOWLEDGED_EVAP"};
    private static final String[] REASONS={"NONE","INVALID_SCOPE","CONTEXT","DEADLINE","SEQUENCE","IDENTITY",
        "STATIONARY","UNSUPPORTED","BASELINE","JOURNAL","TRANSPORT","NEGATIVE","READBACK","DTC_REMAINING"};
    private static final String[] FLAGS_A={"baseline_persisted","intent_persisted","attempted","acknowledged",
        "initial_vin","rechecked_vin","final_vin","before_confirmed"};
    private static final String[] FLAGS_B={"after_confirmed","after_pending","after_confirmed_empty","after_pending_empty",
        "physical_stop_declared","recovery_required"};
    private static final String[] CLEAR={"0902","03","07","0A","0101","010D","010C","0902","010D","010C","04",
        "03","07","0A","0101","0902"};
    private static final int[] CLEAR_STAGES={1,2,2,2,2,2,2,4,4,4,6,7,7,7,7,7};
    private static final String[] EVAP={"0902","0800","010D","010C","0902","0800","010D","010C","0801"};
    private static final int[] EVAP_STAGES={1,2,2,2,4,4,4,4,6};
    private J1979MaintenanceJournal(){}
    private static void require(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
    private static int u(byte b){return b&255;}
    private static int u16(byte[] b,int at){return u(b[at])|(u(b[at+1])<<8);}
    private static long u32(byte[] b,int at){
        return (long)u(b[at])|((long)u(b[at+1])<<8)|((long)u(b[at+2])<<16)|((long)u(b[at+3])<<24);
    }
    private static boolean zero(byte[] b,int begin,int end){
        for(int i=begin;i<end;i++)if(b[i]!=0)return false;
        return true;
    }
    private static boolean validVin(String vin){return vin!=null&&vin.matches("[A-HJ-NPR-Z0-9]{17}");}
    private static String hex(byte[] b){
        final char[] alphabet="0123456789ABCDEF".toCharArray(),out=new char[b.length*2];
        for(int i=0;i<b.length;i++){out[2*i]=alphabet[u(b[i])>>4];out[2*i+1]=alphabet[u(b[i])&15];}
        return new String(out);
    }
    private static String stage(int n)throws IOException{
        switch(n){case 1:return "INITIAL";case 2:return "BASELINE";case 4:return "RECHECK";
            case 6:return "COMMAND";case 7:return "READBACK";default:throw new IOException("Invalid stage");}
    }
    private static String status(int n)throws IOException{
        switch(n){case 1:return "POSITIVE";case 2:return "NEGATIVE";case 3:return "TIMEOUT";
            case 4:return "FAULT";default:throw new IOException("Invalid status");}
    }
    private static Map<String,Object> map(Object... values){
        Map<String,Object> d=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2)d.put((String)values[i],values[i+1]);
        return Collections.unmodifiableMap(d);
    }
    private static final class Scope {
        final int operation;
        final boolean extended;
        final long tx,rx,budget;
        final String vin;
        final byte[] wire;
        Scope(byte[] data)throws IOException{
            require(data.length==SCOPE&&(data[0]==1||data[0]==2)&&u(data[1])<=1,"Invalid CM/1 scope");
            operation=data[0];extended=data[1]==1;tx=u32(data,2);rx=u32(data,6);budget=u32(data,10);
            vin=new String(data,14,17,StandardCharsets.US_ASCII);
            require((extended?tx==0x18da10f1L&&rx==0x18daf110L:tx==0x7e0&&rx==0x7e8)&&
                budget>=1000&&budget<=60000&&validVin(vin),"Invalid maintenance target");
            wire=data.clone();
        }
        String[] plan(){return operation==1?CLEAR:EVAP;}
        int[] stages(){return operation==1?CLEAR_STAGES:EVAP_STAGES;}
        int baseline(){return operation==1?7:4;}
        int intent(){return operation==1?10:8;}
        Map<String,Object> view(){
            return map("operation",operation==1?"CLEAR":"EVAP","extended",extended,
                "request_can_id",Long.toHexString(tx).toUpperCase(Locale.ROOT),
                "response_can_id",Long.toHexString(rx).toUpperCase(Locale.ROOT),
                "budget_ms",budget,"expected_vin",vin);
        }
    }
    private static final class Event {
        final int stage,status,timeout;
        final byte[] request,response,wire;
        final String cmd;
        final long start,end;
        Event(byte[] data,int pos,int size)throws IOException{
            stage=u(data[pos]);status=u(data[pos+1]);int n=u(data[pos+2]);
            J1979MaintenanceJournal.stage(stage);J1979MaintenanceJournal.status(status);
            start=u32(data,pos+8);end=u32(data,pos+12);timeout=u16(data,pos+16);
            require((n==1||n==2)&&data[pos+5]==0&&(n==2||data[pos+4]==0)&&
                size<=MAX_REPLY&&start<=end&&timeout>=1&&timeout<=1800,"Malformed CM/1 observation");
            request=Arrays.copyOfRange(data,pos+3,pos+3+n);cmd=hex(request);
            response=Arrays.copyOfRange(data,pos+ROW,pos+ROW+size);
            wire=Arrays.copyOfRange(data,pos,pos+ROW+size);
        }
        Map<String,Object> view()throws IOException{
            return map("stage",J1979MaintenanceJournal.stage(stage),"status",J1979MaintenanceJournal.status(status),
                "request_hex",cmd,"response_hex",response.length==0?null:hex(response),
                "started_ms",start,"ended_ms",end,"timeout_ms",timeout);
        }
        int shape(){
            byte[] r=response;
            if(r.length==0)return status==3||status==4?status:0;
            if(u(r[0])==0x7f)return r.length==3&&r[1]==request[0]&&r[2]!=0&&u(r[2])!=0x78?2:0;
            if(cmd.equals("04"))return r.length==1&&u(r[0])==0x44?1:0;
            if(cmd.equals("0801"))return r.length==2&&u(r[0])==0x48&&r[1]==1?1:0;
            if(request[0]==3||request[0]==7||request[0]==10){
                if(r.length<2||u(r[0])!=u(request[0])+64||r.length!=2+2*u(r[1]))return 0;
                for(int i=2;i<r.length;i+=2)if(r[i]==0&&r[i+1]==0)return 0;
                return 1;
            }
            if(cmd.equals("0902"))return r.length==20&&u(r[0])==0x49&&r[1]==2&&r[2]==1&&
                validVin(new String(r,3,17,StandardCharsets.US_ASCII))?1:0;
            int size=cmd.equals("0101")||cmd.equals("0800")?6:cmd.equals("010D")?3:cmd.equals("010C")?4:0;
            return size>0&&r.length==size&&u(r[0])==u(request[0])+64&&r[1]==request[1]?1:0;
        }
    }
    private static final class Summary {
        int outcome,reason,count,nrc,a,b;
        long duration;
        Summary(){}
        Summary(byte[] data,int at,int count)throws IOException{
            outcome=u(data[at]);reason=u(data[at+1]);this.count=u(data[at+2]);nrc=u(data[at+3]);
            a=u(data[at+4]);b=u(data[at+5]);duration=u32(data,at+8);
            require(outcome<OUTCOMES.length&&reason<REASONS.length&&this.count==count&&(b&0xc0)==0&&
                zero(data,at+6,at+8)&&zero(data,at+12,at+SUMMARY),"Invalid CM/1 summary");
        }
        void abort(int why){reason=why;outcome=(a&8)!=0?4:(a&6)!=0?3:1;}
        boolean same(Summary x){
            return x!=null&&outcome==x.outcome&&reason==x.reason&&count==x.count&&nrc==x.nrc&&
                a==x.a&&b==x.b&&duration==x.duration;
        }
        Map<String,Object> view(){
            Map<String,Object> d=new LinkedHashMap<>(map("outcome",OUTCOMES[outcome],"reason",REASONS[reason],
                "observations",count,"nrc",nrc,"duration_ms",duration));
            for(int i=0;i<FLAGS_A.length;i++)d.put(FLAGS_A[i],(a&(1<<i))!=0);
            for(int i=0;i<FLAGS_B.length;i++)d.put(FLAGS_B[i],(b&(1<<i))!=0);
            return Collections.unmodifiableMap(d);
        }
    }
    private static final class Entry {
        final int kind;
        final Scope scope;
        final Summary summary;
        final List<Event> rows;
        Entry(int kind,Scope scope,Summary summary,List<Event> rows){
            this.kind=kind;this.scope=scope;this.summary=summary;this.rows=rows;
        }
    }
    private static List<Entry> parse(byte[] data)throws IOException{
        require(data!=null&&data.length>0&&data.length<=MAX_JOURNAL,"Invalid CM/1 input size");
        int pos=0,prior=0;
        List<Entry> result=new ArrayList<>();
        while(pos<data.length){
            require(result.size()<4&&data.length-pos>=HEADER,"Truncated or excessive CM/1 records");
            int kind=u(data[pos+3]),count=u(data[pos+5]);long len=u32(data,pos+8);
            require(data[pos]=='C'&&data[pos+1]=='M'&&data[pos+2]==1&&u(data[pos+4])==result.size()&&
                u16(data,pos+6)==0&&kind>=1&&kind<=4&&count<=MAX_ROWS&&prior!=4&&
                (prior==0?kind==1:kind==prior+1||kind==4),"Unexpected CM/1 header");
            require(len>=SCOPE&&len<=MAX_RECORD-HEADER-4&&len+HEADER+4<=data.length-pos,"CM/1 body size");
            int end=pos+HEADER+(int)len,cursor=pos+HEADER;
            CRC32 crc=new CRC32();crc.update(data,pos,end-pos);
            require(crc.getValue()==u32(data,end),"CM/1 CRC mismatch");
            Scope scope=new Scope(Arrays.copyOfRange(data,cursor,cursor+SCOPE));cursor+=SCOPE;
            Summary summary=null;List<Event> rows=new ArrayList<>();
            if(kind==1)require(count==0&&cursor==end,"Unexpected open payload");
            else{
                require(cursor+SUMMARY<=end,"Truncated summary");
                summary=new Summary(data,cursor,count);cursor+=SUMMARY;
                for(int i=0;i<count;i++){
                    require(cursor+ROW<=end,"Truncated observation header");
                    int size=u16(data,cursor+6);
                    require(size<=MAX_REPLY&&cursor+ROW+size<=end,"Truncated observation");
                    rows.add(new Event(data,cursor,size));cursor+=ROW+size;
                }
                require(cursor==end,"Trailing CM/1 record bytes");
            }
            result.add(new Entry(kind,scope,summary,rows));prior=kind;pos=end+4;
        }
        return result;
    }
    private static Summary replay(Scope scope,List<Event> rows,boolean baseline,boolean intent)throws IOException{
        Summary s=new Summary();
        long now=0,identity=-1,speed=-1,rpm=-1;
        String[] plan=scope.plan();int[] stages=scope.stages();
        require(rows.size()<=plan.length,"Too many observations");
        for(int i=0;i<rows.size();i++){
            Event e=rows.get(i);
            require(s.outcome==0&&e.stage==stages[i]&&e.cmd.equals(plan[i]),"Unexpected maintenance request");
            require(e.start>=now&&e.start<scope.budget&&e.timeout<=scope.budget-e.start,"Invalid deadline");
            if(i==scope.baseline()){require(baseline,"Missing baseline");s.a|=1;}
            if(i==scope.intent()){
                require(intent&&baseline&&identity>=0&&speed>=0&&rpm>=0&&e.start-identity+e.timeout<=5000&&
                    e.start-speed+e.timeout<=5000&&e.start-rpm+e.timeout<=5000,"Command without fresh checks and intent");
                s.a|=6;
            }
            now=e.end;s.count++;s.duration=now;
            if(now>=scope.budget||now-e.start>e.timeout){s.abort(3);continue;}
            if(e.status==4){require(e.response.length==0,"Fault carries data");s.abort(10);continue;}
            if(e.shape()!=e.status){s.abort(10);continue;}
            boolean positive=e.status==1;
            if(e.stage==6){
                if(e.status==2){s.outcome=2;s.reason=11;s.nrc=u(e.response[2]);}
                else if(!positive)s.abort(10);
                else{s.a|=8;if(scope.operation==2)s.outcome=6;}
            }else if(e.cmd.equals("0902")){
                if(!positive||!new String(e.response,3,17,StandardCharsets.US_ASCII).equals(scope.vin))s.abort(5);
                else if(e.stage==1)s.a|=16;
                else if(e.stage==4){s.a|=32;identity=now;}
                else s.a|=64;
            }else if(e.cmd.equals("010D")||e.cmd.equals("010C")){
                if(!positive||!zero(e.response,2,e.response.length))s.abort(6);
                else if(e.stage==4){if(e.cmd.equals("010D"))speed=now;else rpm=now;}
            }else if(e.cmd.equals("0800")){
                if(!positive||(e.response[2]&0x80)==0)s.abort(7);
            }else if(e.stage==2&&e.cmd.equals("03")){
                if(!positive)s.abort(8);else s.a|=128;
            }else if(e.stage==7&&(e.cmd.equals("03")||e.cmd.equals("07"))){
                if(positive){int bit=e.cmd.equals("03")?1:2;s.b|=bit;if(e.response[1]==0)s.b|=bit<<2;}
            }
            if(i==plan.length-1&&scope.operation==1&&s.outcome==0){
                if((s.b&3)==3&&(s.a&64)!=0){s.outcome=5;s.reason=(s.b&12)==12?0:13;}
                else s.abort(12);
            }
        }
        return s;
    }
    /** Verify complete records only, without repairing or trusting a torn tail. */
    public static Map<String,Object> inspectPrefix(byte[] data,String expectedVin)throws IOException{
        require(data!=null&&data.length>0&&data.length<=MAX_JOURNAL&&validVin(expectedVin),
            "Invalid interrupted CM/1 input");
        int pos=0,number=0,prior=0;
        String declaredKind=null;
        Scope firstScope=null;
        byte[] magic={'C','M',1};
        while(pos<data.length){
            require(number<4&&prior!=4,"Trailing bytes after CM/1 terminal");
            int available=Math.min(HEADER,data.length-pos);
            for(int i=0;i<Math.min(3,available);i++)
                require(data[pos+i]==magic[i],"Invalid CM/1 prefix magic/version");
            if(available>=4){
                int k=u(data[pos+3]);
                require(k>=1&&k<=4&&(number==0?k==1:k==prior+1||k==4),"Unexpected prefix record");
                declaredKind=KINDS[k];
            }
            if(available>=5)require(u(data[pos+4])==number,"Invalid prefix sequence");
            if(available>=6)require(u(data[pos+5])<=MAX_ROWS&&(number!=0||data[pos+5]==0),"Invalid prefix count");
            for(int i=6;i<Math.min(8,available);i++)require(data[pos+i]==0,"Invalid prefix reserved field");
            if(available<HEADER)break;
            int kind=u(data[pos+3]),count=u(data[pos+5]);long length=u32(data,pos+8);
            require(length>=SCOPE&&length<=MAX_RECORD-HEADER-4&&
                (kind==1?length==SCOPE&&count==0:length>=SCOPE+SUMMARY+count*ROW),"Invalid prefix body size");
            int end=pos+HEADER+(int)length+4;
            if(data.length-pos>=HEADER+SCOPE){
                Scope scope=new Scope(Arrays.copyOfRange(data,pos+HEADER,pos+HEADER+SCOPE));
                require(scope.vin.equals(expectedVin)&&(firstScope==null||Arrays.equals(firstScope.wire,scope.wire)),
                    "CM/1 prefix scope changed");
                if(firstScope==null)firstScope=scope;
            }
            if(end>data.length)break;
            CRC32 crc=new CRC32();crc.update(data,pos,end-pos-4);
            require(crc.getValue()==u32(data,end-4),"Complete prefix record has bad CRC");
            pos=end;number++;prior=kind;declaredKind=null;
        }
        require(number>0&&prior!=4,"Interrupted CM/1 requires verified open and no terminal");
        Map<String,Object> verified=inspect(Arrays.copyOf(data,pos),expectedVin);
        require(Boolean.FALSE.equals(verified.get("terminal_present"))&&Boolean.TRUE.equals(verified.get("recovery_required")),
            "Invalid prefix state");
        return map("schema","camarodiag-j1979-native-maintenance-prefix-v1","protocol","SAE-J1979",
            "scope",verified.get("scope"),"prefix_integrity_verified",true,"record_consistency_verified",false,
            "terminal_present",false,"verified_prefix_bytes",pos,"unverified_trailing_bytes",data.length-pos,
            "journal_bytes_received",data.length,"next_record_kind_declared",declaredKind,"verified_records",verified.get("records"),
            "baseline_record_present",verified.get("baseline_record_present"),"intent_record_present",verified.get("intent_record_present"),
            "observations",verified.get("observations"),"recovery_required",true,"command_outcome_unknown",true,
            "physical_vehicle_verified",false,"physical_stop_independently_verified",false,"durable_before_tx_independently_verified",false,
            "operator_consent_verified",false,"monitor_completion_verified",false,"evidence_authenticated",false,
            "permits_automatic_retry",false,"permits_programming",false);
    }
    public static Map<String,Object> inspect(byte[] raw,String expectedVin)throws IOException{
        require(validVin(expectedVin),"Independent VIN required");
        List<Entry> entries=parse(raw);Scope scope=entries.get(0).scope;
        require(scope.vin.equals(expectedVin),"CM/1 differs from independent VIN");
        List<Event> previous=Collections.emptyList();
        List<String> kinds=new ArrayList<>();kinds.add("OPEN");
        boolean baseline=false,intent=false;Summary terminal=null;
        for(int i=1;i<entries.size();i++){
            Entry e=entries.get(i);kinds.add(KINDS[e.kind]);
            require(Arrays.equals(scope.wire,e.scope.wire)&&e.rows.size()>=previous.size(),"CM/1 prior evidence changed");
            for(int n=0;n<previous.size();n++)require(Arrays.equals(previous.get(n).wire,e.rows.get(n).wire),"CM/1 prior evidence changed");
            Summary state=replay(scope,e.rows,baseline,intent),claimed=e.summary;
            if(e.kind==2){
                require(e.rows.size()==scope.baseline()&&state.outcome==0&&state.same(claimed),"Invalid baseline");
                baseline=true;
            }else if(e.kind==3){
                require(baseline&&e.rows.size()==scope.intent()&&state.outcome==0&&state.same(claimed),"Invalid intent");
                intent=true;
            }else{
                require((claimed.b&16)!=0,"Terminal missing shutdown declaration");
                if(state.outcome==0){
                    int why=claimed.reason;
                    require((why==2||why==3||why==4||why==6||why==9||why==10)&&
                        claimed.duration>=state.duration&&(why!=3||claimed.duration>=scope.budget),"Invalid external terminal declaration");
                    if(e.rows.size()==scope.baseline()&&baseline)state.a=(state.a&~1)|(claimed.a&1);
                    if(e.rows.size()==scope.intent()&&intent)state.a=(state.a&~2)|(claimed.a&2);
                    state.abort(why);state.duration=claimed.duration;
                }
                state.b|=16|(claimed.b&32);
                require((state.outcome!=3||(state.b&32)!=0)&&state.same(claimed),"Terminal summary differs from replay");
                terminal=state;
            }
            previous=e.rows;
        }
        List<Map<String,Object>> rows=new ArrayList<>();
        for(Event e:previous)rows.add(e.view());
        return map("schema","camarodiag-j1979-native-maintenance-inspection-v1","protocol","SAE-J1979",
            "scope",scope.view(),"record_consistency_verified",true,"records",Collections.unmodifiableList(kinds),
            "baseline_record_present",baseline,"intent_record_present",intent,"terminal_present",terminal!=null,
            "terminal",terminal==null?null:terminal.view(),"observations",Collections.unmodifiableList(rows),
            "recovery_required",terminal==null||(terminal.b&32)!=0,"physical_vehicle_verified",false,
            "physical_stop_independently_verified",false,"durable_before_tx_independently_verified",false,
            "operator_consent_verified",false,"monitor_completion_verified",false,"evidence_authenticated",false,
            "permits_automatic_retry",false,"permits_programming",false);
    }
}
