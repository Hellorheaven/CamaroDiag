using System.Text;

namespace CamaroDiagConnectors.Windows;

internal static class J1979CampaignSemantics
{
    private static readonly string[] States=["","PASSED","RAW","NEGATIVE","TIMEOUT","FAILED"];
    private static readonly string[] Ends=["","COMPLETE","INITIAL_UNVERIFIED","BUDGET","LINK_FAULT","SINK_STOPPED"];
    private static readonly HashSet<int> One=[4,5,6,7,8,9,10,11,13,14,15,17,19,29,44,45,46,47,48,51,69,70,71,72,73,74,75,76,82,90,91,92,97,98];
    private static readonly HashSet<int> Two=[12,16,31,33,34,35,49,50,60,61,62,63,66,67,68,77,78,83,84,89,93,94,99];
    internal sealed class Step {
        public int Sequence{get;}
        public string RequestHex{get;}
        public string? ResponseHex{get;}
        public string Status{get;}
        public long DurationMs{get;}
        internal int State{get;}
        internal byte[] Request{get;}
        internal byte[] Response{get;}
        internal Step(int seq,int state,long duration,byte[] req,byte[] res){
            if(!ReadOnly(req)||state is <1 or >5||(state<=3)!=(res.Length>0)||
               (state<=2&&Positive(req,res)!=state)||
               (state==3&&(res.Length!=3||res[0]!=0x7f||res[1]!=req[0]||res[2] is 0 or 0x78)))
                throw new IOException("CJ/1 application evidence inconsistent");
            Sequence=seq;State=state;DurationMs=duration;Request=req.ToArray();Response=res.ToArray();
            RequestHex=Convert.ToHexString(req);ResponseHex=res.Length==0?null:Convert.ToHexString(res);Status=States[state];
        }
        internal string? ObservedVin=>State==1&&Request.AsSpan().SequenceEqual(new byte[]{9,2})?Encoding.ASCII.GetString(Response,3,17):null;
    }
    internal sealed record Summary(string End,int Flags,int Steps);
    private static bool ReadOnly(byte[] r){
        if(r.Length==0)return false;int m=r[0];
        return r.Length==1&&(m is 3 or 7 or 10)||r.Length==2&&((m is 1 or 6 or 9)||m==8&&r[1]%32==0)||r.Length==3&&m==2&&r[2]==0;
    }
    private static int Positive(byte[] req,byte[] r){
        int m=req[0];if(r.Length==0||r[0]!=m+64)return 0;
        if(m is 3 or 7 or 10){
            if(r.Length<2||r.Length!=2+2*r[1])return 0;
            for(int i=2;i<r.Length;i+=2)if(r[i]==0&&r[i+1]==0)return 0;
            return 1;
        }
        if(r.Length<2||r[1]!=req[1])return 0;
        int pid=req[1],off=m==2?3:2;
        if(m==2&&(r.Length<3||r[2]!=req[2]))return 0;
        if(pid%32==0)return r.Length==off+4&&!(pid==224&&(r[^1]&1)!=0)?1:0;
        if(m is 1 or 2){
            int width=m==2&&pid==2?2:pid==1?4:One.Contains(pid)?1:Two.Contains(pid)?2:0;
            return width>0?(r.Length==off+width?1:0):(r.Length>off?2:0);
        }
        if(m==6){
            if(r.Length<10||(r.Length-1)%9!=0)return 0;
            for(int i=1;i<r.Length;i+=9)if(r[i]!=req[1])return 0;
            return 1;
        }
        if(m==9){
            if(pid==2){
                if(r.Length!=20||r[2]!=1)return 0;
                for(int i=3;i<20;i++){int c=r[i];if(!((c>='0'&&c<='9')||(c>='A'&&c<='Z'))||c=='I'||c=='O'||c=='Q')return 0;}
                return 1;
            }
            int width=pid==4?16:pid==6?4:pid==10?20:0;
            return width>0?(r.Length>=3&&r[2]>0&&r.Length==3+r[2]*width?1:0):(r.Length>2?2:0);
        }
        return 0;
    }
    internal static Summary Verify(IReadOnlyList<Step> steps,int end,int flags){
        if(end is <1 or >5||flags is <0 or >255)throw new IOException("Invalid CJ/1 summary");
        return new Replay(steps).Verify(end,flags);
    }
    private sealed class Replay(IReadOnlyList<Step> steps){
        private int index,stop,computed;private byte[]? initial;
        private byte[]? Read(params byte[] req){
            if(stop!=0)return null;
            if(index==steps.Count){stop=1;return null;}
            Step s=steps[index++];
            if(!s.Request.AsSpan().SequenceEqual(req))throw new IOException("Unexpected CJ/1 request order");
            if(s.State==5)stop=2;return s.State==1?s.Response:null;
        }
        private byte[]? Page(int m,int p)=>m==2?Read((byte)m,(byte)p,0):Read((byte)m,(byte)p);
        private bool Inventory(int m,bool cached,bool members){
            int off=m==2?3:2;
            for(int b=0;b<=224&&stop==0;b+=32){
                byte[]? bitmap=cached&&b==0?initial:Page(m,b);if(bitmap is null)return false;
                if(members)for(int bit=0;bit<31&&stop==0;bit++){
                    if((bitmap[off+bit/8]&(128>>(bit%8)))==0)continue;
                    int p=b+bit+1;if((m is 2 or 9)&&p==2)continue;Page(m,p);
                }
                if((bitmap[off+3]&1)==0)return stop==0;
            }
            return false;
        }
        internal Summary Verify(int end,int flags){
            initial=Read(1,0);
            if(initial is not null){
                computed|=1;Read(9,2);Read(3);Read(7);Read(10);
                if(Inventory(1,true,true))computed|=2;
                byte[]? trigger=Read(2,2,0);
                if(trigger is not null){computed|=4;if(trigger[3]!=0||trigger[4]!=0){computed|=8;if(Inventory(2,false,true))computed|=16;}}
                if(Inventory(6,false,true))computed|=32;
                if(Inventory(8,false,false))computed|=64;
                if(Inventory(9,false,true))computed|=128;
            }
            int expected=stop==2?4:stop==1?(steps.Count==0&&end==4?4:3):initial is null?2:1;
            if(index!=steps.Count||expected!=end||computed!=flags)throw new IOException("CJ/1 summary contradicts exact campaign plan");
            return new Summary(Ends[end],flags,steps.Count);
        }
    }
}
