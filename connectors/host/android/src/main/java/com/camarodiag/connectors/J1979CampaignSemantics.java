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
    public static final class Step {
        public final int sequence;
        public final String requestHex,responseHex,status;
        public final long durationMs;
        private final int state;
        private final byte[] request,response;
        Step(int seq,int state,long duration,byte[] req,byte[] res)throws IOException{
            if(!readOnly(req)||state<1||state>5||(state<=3)!=(res.length>0)||
               (state<=2&&positive(req,res)!=state)||
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
        public String observedVin(){return state==1&&Arrays.equals(request,new byte[]{9,2})?
            new String(response,3,17,StandardCharsets.US_ASCII):null;}
    }
    public static final class Summary {
        public final String end;
        public final int flags,steps;
        Summary(int end,int flags,int steps){this.end=ENDS[end];this.flags=flags;this.steps=steps;}
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
            int width=m==2&&pid==2?2:pid==1?4:Arrays.binarySearch(ONE,pid)>=0?1:Arrays.binarySearch(TWO,pid)>=0?2:0;
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
