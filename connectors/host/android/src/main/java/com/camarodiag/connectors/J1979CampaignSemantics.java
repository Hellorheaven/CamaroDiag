package com.camarodiag.connectors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** Validates recorded J1979 application responses and the exact read-only campaign. */
public final class J1979CampaignSemantics {
    private J1979CampaignSemantics() {}
    private static final String[] STATES={"","PASSED","RAW","NEGATIVE","TIMEOUT","FAILED"};
    private static final String[] ENDS={"","COMPLETE","INITIAL_UNVERIFIED","BUDGET","LINK_FAULT","SINK_STOPPED"};
    private static final int[] ONE={4,5,6,7,8,9,10,11,13,14,15,17,19,29,44,45,46,47,48,51,69,70,71,72,73,74,75,76,82,90,91,92,97,98};
    private static final int[] TWO={12,16,31,33,34,35,49,50,60,61,62,63,66,67,68,77,78,83,84,89,93,94,99};
    private static int u(byte b){return b&255;}
    private static String hex(byte[] data){
        char[] out=new char[data.length*2];String digits="0123456789ABCDEF";
        for(int i=0;i<data.length;i++){out[i*2]=digits.charAt(u(data[i])>>>4);out[i*2+1]=digits.charAt(u(data[i])&15);}
        return new String(out);
    }
    public static final class Measurement {
        public final int mode,pid;
        public final String name,unit;
        public final double number;
        Measurement(int mode,int pid,String name,double number,String unit){
            this.mode=mode;this.pid=pid;this.name=name;this.number=number;this.unit=unit;
        }
    }
    // Same explicitly defined formulas as ObdJ1979PidValues in the PC bridge.
    private static Measurement measurement(byte[] req,byte[] res){
        int mode=u(req[0]);
        if(mode!=1&&mode!=2)return null;
        int pid=u(req[1]),offset=mode==2?3:2;
        if(res.length<=offset)return null;
        int a=u(res[offset]),b=res.length>offset+1?u(res[offset+1]):0;
        switch(pid){
            case 0x04:return new Measurement(mode,pid,"calculated-engine-load",a * 100d / 255,"%");
            case 0x05:return new Measurement(mode,pid,"coolant-temperature",(double)a - 40,"degC");
            case 0x06:return new Measurement(mode,pid,"short-term-fuel-trim-bank-1",a * 100d / 128 - 100,"%");
            case 0x07:return new Measurement(mode,pid,"long-term-fuel-trim-bank-1",a * 100d / 128 - 100,"%");
            case 0x08:return new Measurement(mode,pid,"short-term-fuel-trim-bank-2",a * 100d / 128 - 100,"%");
            case 0x09:return new Measurement(mode,pid,"long-term-fuel-trim-bank-2",a * 100d / 128 - 100,"%");
            case 0x0C:return new Measurement(mode,pid,"engine-rpm",(a * 256d + b) / 4,"rpm");
            case 0x0D:return new Measurement(mode,pid,"vehicle-speed",(double)a,"km/h");
            case 0x0E:return new Measurement(mode,pid,"timing-advance",a / 2d - 64,"degBTDC");
            case 0x0F:return new Measurement(mode,pid,"intake-air-temperature",(double)a - 40,"degC");
            case 0x10:return new Measurement(mode,pid,"mass-air-flow",(a * 256d + b) / 100,"g/s");
            case 0x11:return new Measurement(mode,pid,"throttle-position",a * 100d / 255,"%");
            case 0x1F:return new Measurement(mode,pid,"engine-runtime",a * 256d + b,"s");
            case 0x2F:return new Measurement(mode,pid,"fuel-tank-level",a * 100d / 255,"%");
            case 0x33:return new Measurement(mode,pid,"barometric-pressure",(double)a,"kPa");
            case 0x42:return new Measurement(mode,pid,"control-module-voltage",(a * 256d + b) / 1000,"V");
            case 0x46:return new Measurement(mode,pid,"ambient-air-temperature",(double)a - 40,"degC");
            case 0x52:return new Measurement(mode,pid,"ethanol-content",a * 100d / 255,"%");
            case 0x5C:return new Measurement(mode,pid,"engine-oil-temperature",(double)a - 40,"degC");
            case 0x5E:return new Measurement(mode,pid,"engine-fuel-rate",(a * 256d + b) / 20,"L/h");
            case 0x0A:return new Measurement(mode,pid,"fuel-pressure-gauge",a * 3d,"kPa");
            case 0x0B:return new Measurement(mode,pid,"intake-manifold-absolute-pressure",(double)a,"kPa");
            case 0x21:return new Measurement(mode,pid,"distance-with-mil-on",a * 256d + b,"km");
            case 0x22:return new Measurement(mode,pid,"fuel-rail-pressure-relative-vacuum",(a * 256d + b) * 0.079,"kPa");
            case 0x23:return new Measurement(mode,pid,"fuel-rail-pressure-direct-injection",(a * 256d + b) * 10,"kPa");
            case 0x2C:return new Measurement(mode,pid,"commanded-egr",a * 100d / 255,"%");
            case 0x2D:return new Measurement(mode,pid,"egr-error",(a - 128) * 100d / 128,"%");
            case 0x2E:return new Measurement(mode,pid,"commanded-evaporative-purge",a * 100d / 255,"%");
            case 0x30:return new Measurement(mode,pid,"warm-ups-since-dtc-clear",(double)a,"count");
            case 0x31:return new Measurement(mode,pid,"distance-since-dtc-clear",a * 256d + b,"km");
            case 0x32:return new Measurement(mode,pid,"evap-vapor-pressure",((short)(a * 256 + b)) / 4d,"Pa");
            case 0x3C:return new Measurement(mode,pid,"catalyst-temperature-bank-1-sensor-1",(a * 256d + b) / 10 - 40,"degC");
            case 0x3D:return new Measurement(mode,pid,"catalyst-temperature-bank-2-sensor-1",(a * 256d + b) / 10 - 40,"degC");
            case 0x3E:return new Measurement(mode,pid,"catalyst-temperature-bank-1-sensor-2",(a * 256d + b) / 10 - 40,"degC");
            case 0x3F:return new Measurement(mode,pid,"catalyst-temperature-bank-2-sensor-2",(a * 256d + b) / 10 - 40,"degC");
            case 0x43:return new Measurement(mode,pid,"absolute-engine-load",(a * 256d + b) * 100 / 255,"%");
            case 0x44:return new Measurement(mode,pid,"commanded-equivalence-ratio",(a * 256d + b) / 32768,"ratio");
            case 0x45:return new Measurement(mode,pid,"relative-throttle-position",a * 100d / 255,"%");
            case 0x47:return new Measurement(mode,pid,"absolute-throttle-position-b",a * 100d / 255,"%");
            case 0x48:return new Measurement(mode,pid,"absolute-throttle-position-c",a * 100d / 255,"%");
            case 0x49:return new Measurement(mode,pid,"accelerator-pedal-position-d",a * 100d / 255,"%");
            case 0x4A:return new Measurement(mode,pid,"accelerator-pedal-position-e",a * 100d / 255,"%");
            case 0x4B:return new Measurement(mode,pid,"accelerator-pedal-position-f",a * 100d / 255,"%");
            case 0x4C:return new Measurement(mode,pid,"commanded-throttle-actuator",a * 100d / 255,"%");
            case 0x4D:return new Measurement(mode,pid,"time-run-with-mil-on",a * 256d + b,"min");
            case 0x4E:return new Measurement(mode,pid,"time-since-dtc-clear",a * 256d + b,"min");
            case 0x53:return new Measurement(mode,pid,"absolute-evap-vapor-pressure",(a * 256d + b) / 200,"kPa");
            case 0x54:return new Measurement(mode,pid,"evap-vapor-pressure-offset",(a * 256d + b) - 32767,"Pa");
            case 0x59:return new Measurement(mode,pid,"fuel-rail-pressure-absolute",(a * 256d + b) * 10,"kPa");
            case 0x5A:return new Measurement(mode,pid,"relative-accelerator-pedal-position",a * 100d / 255,"%");
            case 0x5B:return new Measurement(mode,pid,"hybrid-battery-pack-remaining-life",a * 100d / 255,"%");
            case 0x5D:return new Measurement(mode,pid,"fuel-injection-timing",((a * 256d + b) - 26880) / 128,"deg");
            case 0x61:return new Measurement(mode,pid,"driver-demand-engine-torque",(double)a - 125,"%");
            case 0x62:return new Measurement(mode,pid,"actual-engine-torque",(double)a - 125,"%");
            case 0x63:return new Measurement(mode,pid,"engine-reference-torque",a * 256d + b,"Nm");
            case 0xA6:return new Measurement(mode,pid,"odometer",
                (a*16777216L+b*65536L+u(res[offset+2])*256L+u(res[offset+3]))/10d,"km");
            default:return null;
        }
    }
    /** Encoded CAN Mode 06 record; no scaling or pass/fail inferred. */
    public static final class MonitorRecord {
        public final int monitorId,testId,unitScalingId,valueEncoded,minimumEncoded,maximumEncoded;
        MonitorRecord(byte[] data,int i){
            monitorId=u(data[i]);testId=u(data[i+1]);unitScalingId=u(data[i+2]);
            valueEncoded=u(data[i+3])*256+u(data[i+4]);
            minimumEncoded=u(data[i+5])*256+u(data[i+6]);
            maximumEncoded=u(data[i+7])*256+u(data[i+8]);
        }
    }
    public static final class VehicleInfo {
        public final int infoType;
        public final List<String> values;
        VehicleInfo(int infoType,List<String> values){
            this.infoType=infoType;this.values=Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
    private static String paddedAscii(byte[] data,int start,int length){
        int end=start+length;
        while(end>start&&data[end-1]==0)end--;
        if(end==start)return null;
        for(int i=start;i<end;i++)if(u(data[i])<32||u(data[i])>126)return null;
        return new String(data,start,end-start,StandardCharsets.US_ASCII);
    }
    public static final class Readiness {
        public final boolean milOn,compressionIgnition;
        public final int confirmedDtcCount,byteB,byteC,byteD;
        public final java.util.Map<String,Boolean> supportedMonitors;
        Readiness(byte[] response){
            int a=u(response[2]);byteB=u(response[3]);byteC=u(response[4]);byteD=u(response[5]);
            milOn=(a&128)!=0;confirmedDtcCount=a&127;compressionIgnition=(byteB&8)!=0;
            java.util.Map<String,Boolean> monitors=new java.util.LinkedHashMap<>();
            addMonitors(monitors,new String[]{"misfire","fuel-system","components"},byteB,byteB>>>4);
            addMonitors(monitors,compressionIgnition?
                new String[]{"nmhc-catalyst","nox-aftertreatment",null,"boost-pressure",null,"exhaust-gas-sensor","particulate-filter","egr-vvt"}:
                new String[]{"catalyst","heated-catalyst","evaporative-system","secondary-air",null,"oxygen-sensor","oxygen-sensor-heater","egr-vvt"},
                byteC,byteD);
            supportedMonitors=Collections.unmodifiableMap(monitors);
        }
        private static void addMonitors(java.util.Map<String,Boolean> out,String[] names,int support,int incomplete){
            for(int bit=0;bit<names.length;bit++)if(names[bit]!=null&&(support&(1<<bit))!=0)
                out.put(names[bit],(incomplete&(1<<bit))==0);
        }
    }
    public static final class SupportedPage {
        public final int mode,base;
        public final boolean hasNextPage;
        public final List<Integer> supportedIds;
        SupportedPage(int mode,int base,byte[] response,int offset){
            this.mode=mode;this.base=base;List<Integer> ids=new ArrayList<>();
            for(int i=0;i<31;i++)if((u(response[offset+i/8])&(128>>>(i%8)))!=0)ids.add(base+i+1);
            supportedIds=Collections.unmodifiableList(ids);hasNextPage=(u(response[offset+3])&1)!=0;
        }
    }
    public static final class FreezeTrigger {
        public final boolean present;
        public final String dtc;
        FreezeTrigger(int a,int b){
            present=a!=0||b!=0;String digits="0123456789ABCDEF";
            dtc=present?new String(new char[]{"PCBU".charAt(a>>>6),digits.charAt((a>>>4)&3),
                digits.charAt(a&15),digits.charAt(b>>>4),digits.charAt(b&15)}):null;
        }
    }
    // Old CU/1 captures recorded this now-known width as RAW. Keep the
    // historical state, do not silently upgrade it into a measurement.
    private static boolean legacyRawOdometer(byte[] req,byte[] res){
        return req.length>=2&&(u(req[0])==1||u(req[0])==2)&&u(req[1])==0xA6&&positive(req,res)==1;
    }
    public static final class Step {
        public final int sequence;
        public final String requestHex,responseHex,status;
        public final long durationMs;
        private final int state;
        private final byte[] request,response;
        Step(int seq,int state,long duration,byte[] req,byte[] res)throws IOException{
            if(!readOnly(req)||state<1||state>5||(state<=3)!=(res.length>0)||
               (state<=2&&positive(req,res)!=state&&!(state==2&&legacyRawOdometer(req,res)))||
               (state==3&&(res.length!=3||u(res[0])!=0x7f||res[1]!=req[0]||u(res[2])==0||u(res[2])==0x78)))
                throw new IOException("CJ/1 application evidence inconsistent");
            sequence=seq;this.state=state;durationMs=duration;request=req.clone();response=res.clone();
            requestHex=hex(req);responseHex=res.length==0?null:hex(res);status=STATES[state];
        }
        /** Null = no verified DTC response, empty = verified zero records. */
        public List<String> dtcCodes(){
            int m=u(request[0]);
            if(state!=1||(m!=3&&m!=7&&m!=10))return null;
            List<String> codes=new ArrayList<>();
            String digits="0123456789ABCDEF";
            for(int i=2;i<response.length;i+=2){
                int a=u(response[i]),b=u(response[i+1]);
                codes.add(new String(new char[]{"PCBU".charAt(a>>>6),
                    digits.charAt((a>>>4)&3),digits.charAt(a&15),
                    digits.charAt(b>>>4),digits.charAt(b&15)}));
            }
            return Collections.unmodifiableList(codes);
        }
        /** NRC is meaningful only on a verified terminal negative response. */
        public Integer negativeResponseCode(){return state==3?u(response[2]):null;}
        public Measurement measurement(){return state==1?J1979CampaignSemantics.measurement(request,response):null;}
        public List<MonitorRecord> monitorRecords(){
            if(state!=1||u(request[0])!=6||u(request[1])%32==0)return null;
            List<MonitorRecord> records=new ArrayList<>();
            for(int i=1;i<response.length;i+=9)records.add(new MonitorRecord(response,i));
            return Collections.unmodifiableList(records);
        }
        /** Only decoded, structurally valid InfoTypes; CVNs are not signatures. */
        public SupportedPage supportedPage(){
            if(state!=1||request.length<2||u(request[1])%32!=0)return null;
            int mode=u(request[0]);
            return new SupportedPage(mode,u(request[1]),response,mode==2?3:2);
        }
        public FreezeTrigger freezeTrigger(){
            return state==1&&Arrays.equals(request,new byte[]{2,2,0})?
                new FreezeTrigger(u(response[3]),u(response[4])):null;
        }
        public Readiness readiness(){
            return state==1&&Arrays.equals(request,new byte[]{1,1})?new Readiness(response):null;
        }
        public VehicleInfo vehicleInfo(){
            if(state!=1||u(request[0])!=9)return null;
            int type=u(request[1]);List<String> values=new ArrayList<>();
            if(type==2)values.add(observedVin());
            else if(type==4){
                for(int i=3;i<response.length;i+=16){
                    String value=paddedAscii(response,i,16);if(value==null)return null;values.add(value);
                }
            }else if(type==6){
                for(int i=3;i<response.length;i+=4)values.add(hex(Arrays.copyOfRange(response,i,i+4)));
            }else if(type==10){
                if(u(response[2])!=1||response[7]!='-')return null;
                String acronym=paddedAscii(response,3,4),description=paddedAscii(response,8,15);
                if(acronym==null||description==null)return null;values.add(acronym+"-"+description);
            }else return null;
            return new VehicleInfo(type,values);
        }
        public String observedVin(){return state==1&&Arrays.equals(request,new byte[]{9,2})?
            new String(response,3,17,StandardCharsets.US_ASCII):null;}
    }
    public static final class Summary {
        public final String end;
        public final int flags,steps;
        Summary(int end,int flags,int steps){this.end=ENDS[end];this.flags=flags;this.steps=steps;}
    }
    /** Re-decode a positive application record from a locally recorded report.
     * No campaign or physical provenance is established by this helper. */
    public static Step decodePositive(String requestHex,String responseHex){
        byte[] request=parseHex(requestHex,3),response=parseHex(responseHex,4095);
        if(request==null||response==null)return null;
        try{return new Step(0,1,0,request,response);}
        catch(IOException malformed){return null;}
    }
    private static byte[] parseHex(String value,int maxBytes){
        if(value==null||value.length()<2||value.length()>maxBytes*2||value.length()%2!=0)return null;
        byte[] result=new byte[value.length()/2];String digits="0123456789ABCDEF";
        for(int i=0;i<result.length;i++){
            int a=digits.indexOf(value.charAt(i*2)),b=digits.indexOf(value.charAt(i*2+1));
            if(a<0||b<0)return null;result[i]=(byte)(a*16+b);
        }
        return result;
    }
    private static boolean readOnly(byte[] r){
        if(r.length==0)return false;
        int m=u(r[0]);return r.length==1&&(m==3||m==7||m==10)||
            r.length==2&&(m==1||m==6||m==9||m==8&&u(r[1])%32==0)||
            r.length==3&&m==2&&r[2]==0;
    }
    private static int positive(byte[] req,byte[] r){
        int m=u(req[0]);if(r.length==0||u(r[0])!=m+64)return 0;
        if(m==3||m==7||m==10){
            if(r.length<2||r.length!=2+2*u(r[1]))return 0;
            for(int i=2;i<r.length;i+=2)if(r[i]==0&&r[i+1]==0)return 0;
            return 1;
        }
        if(r.length<2||r[1]!=req[1])return 0;
        int pid=u(req[1]),off=m==2?3:2;
        if(m==2&&(r.length<3||r[2]!=req[2]))return 0;
        if(pid%32==0)return r.length==off+4&&!(pid==224&&(r[r.length-1]&1)!=0)?1:0;
        if(m==1||m==2){
            int width=m==2&&pid==2?2:(pid==1||pid==0xA6)?4:Arrays.binarySearch(ONE,pid)>=0?1:Arrays.binarySearch(TWO,pid)>=0?2:0;
            return width>0?(r.length==off+width?1:0):(r.length>off?2:0);
        }
        if(m==6){
            if(r.length<10||(r.length-1)%9!=0)return 0;
            for(int i=1;i<r.length;i+=9)if(r[i]!=req[1])return 0;
            return 1;
        }
        if(m==9){
            if(pid==2){
                if(r.length!=20||r[2]!=1)return 0;
                for(int i=3;i<20;i++){int c=u(r[i]);if(!((c>='0'&&c<='9')||(c>='A'&&c<='Z'))||c=='I'||c=='O'||c=='Q')return 0;}
                return 1;
            }
            int width=pid==4?16:pid==6?4:pid==10?20:0;
            return width>0?(r.length>=3&&u(r[2])>0&&r.length==3+u(r[2])*width?1:0):(r.length>2?2:0);
        }
        return 0;
    }
    static Summary verify(List<Step> steps,int end,int flags)throws IOException{
        if(end<1||end>5||flags<0||flags>255)throw new IOException("Invalid CJ/1 summary");
        return new Replay(steps).verify(end,flags);
    }
    private static final class Replay {
        private final List<Step> steps;private int index,stop,computed;private byte[] initial;
        Replay(List<Step> steps){this.steps=steps;}
        byte[] read(int... req)throws IOException{
            if(stop!=0)return null;
            if(index==steps.size()){stop=1;return null;}
            Step s=steps.get(index++);
            if(s.request.length!=req.length)throw new IOException("Unexpected CJ/1 request order");
            for(int i=0;i<req.length;i++)if(u(s.request[i])!=req[i])throw new IOException("Unexpected CJ/1 request order");
            if(s.state==5)stop=2;return s.state==1?s.response:null;
        }
        byte[] page(int m,int p)throws IOException{return m==2?read(m,p,0):read(m,p);}
        boolean inventory(int m,boolean cached,boolean members)throws IOException{
            int off=m==2?3:2;
            for(int base=0;base<=224&&stop==0;base+=32){
                byte[] b=cached&&base==0?initial:page(m,base);if(b==null)return false;
                if(members)for(int bit=0;bit<31&&stop==0;bit++){
                    if((b[off+bit/8]&(128>>(bit%8)))==0)continue;
                    int p=base+bit+1;if((m==2||m==9)&&p==2)continue;page(m,p);
                }
                if((b[off+3]&1)==0)return stop==0;
            }
            return false;
        }
        Summary verify(int end,int flags)throws IOException{
            initial=read(1,0);
            if(initial!=null){
                computed|=1;read(9,2);read(3);read(7);read(10);
                if(inventory(1,true,true))computed|=2;
                byte[] trigger=read(2,2,0);
                if(trigger!=null){computed|=4;if(trigger[3]!=0||trigger[4]!=0){computed|=8;if(inventory(2,false,true))computed|=16;}}
                if(inventory(6,false,true))computed|=32;
                if(inventory(8,false,false))computed|=64;
                if(inventory(9,false,true))computed|=128;
            }
            int expected=stop==2?4:stop==1?(steps.isEmpty()&&end==4?4:3):initial==null?2:1;
            if(index!=steps.size()||expected!=end||computed!=flags)throw new IOException("CJ/1 summary contradicts exact campaign plan");
            return new Summary(end,flags,steps.size());
        }
    }
}
