package cn.graduation.cabinet;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** 固定 QR 版本 3-L（单个 Reed–Solomon 数据块），只编码短柜体标识；不向外部二维码站点传数据。 */
@RestController
class CabinetQrController {
    private final DataStore s;
    CabinetQrController(DataStore s){this.s=s;}
    @GetMapping(value="/api/cabinets/{id}/qr",produces="image/svg+xml")
    String qr(@PathVariable String id){
        if(!id.matches("[A-Za-z0-9_-]{1,40}")) throw new BusinessException("无效柜号");
        s.one("SELECT id FROM cabinets WHERE id=?",id);
        return svg("cabinetId="+id);
    }
    static String svg(String payload){
        byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>53) throw new IllegalArgumentException("QR data too long");
        List<Integer> bits=new ArrayList<>();
        push(bits,4,4);push(bits,bytes.length,8);
        for(byte b:bytes) push(bits,b&255,8);
        push(bits,0,Math.min(4,440-bits.size()));
        while(bits.size()%8!=0) bits.add(0);
        int[] data=new int[70];
        int size=bits.size()/8;
        for(int i=0;i<bits.size();i++) data[i/8]=data[i/8]*2+bits.get(i);
        for(int i=size;i<55;i++) data[i]=(i-size)%2==0?236:17;
        int[] gen={1};
        for(int i=0,root=1;i<15;i++,root=multiply(root,2)){
            int[] next=new int[gen.length+1];
            for(int k=0;k<gen.length;k++){next[k]^=gen[k];next[k+1]^=multiply(gen[k],root);}
            gen=next;
        }
        int[] rem=data.clone();
        for(int i=0;i<55;i++){int lead=rem[i];for(int k=0;k<gen.length;k++) rem[i+k]^=multiply(gen[k],lead);}
        System.arraycopy(rem,55,data,55,15);
        final int n=29;
        boolean[][] pixels=new boolean[n][n], fixed=new boolean[n][n];
        for(int i=0;i<n;i++){put(pixels,fixed,i,6,i%2==0);put(pixels,fixed,6,i,i%2==0);}
        for(int[] center:new int[][]{{3,3},{25,3},{3,25},{22,22}}){
            int radius=center[0]==22?2:4;
            for(int dx=-radius;dx<=radius;dx++) for(int dy=-radius;dy<=radius;dy++){
                int ring=Math.max(Math.abs(dx),Math.abs(dy));
                put(pixels,fixed,center[0]+dx,center[1]+dy,radius==2?ring!=1:ring!=2&&ring!=4);
            }
        }
        int format=0x77c4; // 纠错 L / 掩码 0 的 BCH 格式位
        for(int i=0;i<=5;i++) put(pixels,fixed,8,i,bit(format,i));
        put(pixels,fixed,8,7,bit(format,6));put(pixels,fixed,8,8,bit(format,7));put(pixels,fixed,7,8,bit(format,8));
        for(int i=9;i<15;i++) put(pixels,fixed,14-i,8,bit(format,i));
        for(int i=0;i<8;i++) put(pixels,fixed,n-1-i,8,bit(format,i));
        for(int i=8;i<15;i++) put(pixels,fixed,8,n-15+i,bit(format,i));
        put(pixels,fixed,8,n-8,true);
        int cursor=0;
        for(int right=n-1;right>=1;right-=2){
            if(right==6) right=5;
            for(int v=0;v<n;v++) for(int offset=0;offset<2;offset++){
                int x=right-offset,y=((right+1)&2)==0?n-1-v:v;
                if(fixed[y][x]) continue;
                pixels[y][x]=(cursor<560&&bit(data[cursor/8],7-cursor%8))^((x+y)%2==0);
                cursor++;
            }
        }
        var out=new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' width='222' height='222' viewBox='0 0 37 37' shape-rendering='crispEdges'><rect width='37' height='37' fill='white'/><path fill='black' d='");
        for(int y=0;y<n;y++) for(int x=0;x<n;x++) if(pixels[y][x]) out.append("M").append(x+4).append(" ").append(y+4).append("h1v1h-1z");
        return out.append("'/></svg>").toString();
    }
    private static boolean bit(int v,int i){return ((v>>>i)&1)!=0;}
    private static void push(List<Integer> out,int val,int count){for(int i=count-1;i>=0;i--)out.add((val>>>i)&1);}
    private static void put(boolean[][] p,boolean[][] f,int x,int y,boolean dark){
        if(x>=0&&y>=0&&x<p.length&&y<p.length){p[y][x]=dark;f[y][x]=true;}
    }
    private static int multiply(int a,int b){
        int v=0;
        while(b!=0){if((b&1)!=0)v^=a;a<<=1;if((a&256)!=0)a^=285;b>>>=1;}
        return v;
    }
}
