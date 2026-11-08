package io.github.verycooltimo.murim.client.vfx.bt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import io.github.verycooltimo.murim.client.ClientConfig;
import io.github.verycooltimo.murim.client.ClientMeditationState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;

/** Timeline-driven upward fan after the blow (no focus pull: the lab has none). Clocked directly, so pause/replay cannot accumulate bursts. */
public final class BtSpeedLines {
    private BtSpeedLines() {}
    /** Outward radial breakthrough lines, sampled for 0.4 seconds from the creation burst.
     * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/gui/GuiGraphics.java#pose/guiWidth/guiHeight;
     * renderer/GameRenderer.java#getPositionColorShader. Vertex submission follows the existing render path.
     */
    public static void renderCreation(GuiGraphics g,double age) {
        if(age<0||age>=.4||!ClientConfig.DISTORTION_EFFECTS.get())return;
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var b=Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.POSITION_COLOR);
        var m=g.pose().last().pose();float w=g.guiWidth(),h=g.guiHeight();
        for(int i=0;i<64;i++) {
            double a=(i+.6*BtMath.random(i*53+9701))/64*Math.PI*2;
            float dx=(float)Math.cos(a),dy=(float)Math.sin(a);
            float inner=(float)(h*(.18+.2*BtMath.random(i*53+9702)+age*.9));
            float outer=inner+h*.45F,wide=(float)(1+4*BtMath.random(i*53+9703))*h/680F;
            float cx=w*.5F,cy=h*.55F,alpha=(float)(.7*(1-age/.4));
            BtImpactFrame.vertex(b,m,cx+dx*inner,cy+dy*inner,0xf4f8ff,0);
            BtImpactFrame.vertex(b,m,cx+dx*outer-dy*wide,cy+dy*outer+dx*wide,0xf4f8ff,alpha);
            BtImpactFrame.vertex(b,m,cx+dx*outer+dy*wide,cy+dy*outer-dx*wide,0xf4f8ff,alpha);
        }
        var mesh=b.build();if(mesh!=null)BufferUploader.drawWithShader(mesh);
        RenderSystem.enableCull();RenderSystem.disableBlend();RenderSystem.enableDepthTest();
    }

    static void render(GuiGraphics g,float partial) {
        if(!ClientConfig.DISTORTION_EFFECTS.get())return;
        int target=ClientMeditationState.rankUpAge()>=0?ClientMeditationState.rankUpRank():ClientMeditationState.breakthroughTarget();
        if(target!=4)return;
        double t=BtScenes.labSeconds(target,partial),e=t-25.5;
        // lab-bt_peak.js btpSpeed: «none in the squeeze: lines into his middle read as everything going into the centre» (author's ninth
        // note: «почему-то в центр всё это — странно»); the 31.10 film still pulled lines in from 24.1 s. Only the fan after the blow.
        double pull=0,up=e>=.08&&e<.9?1-BtMath.smooth(.35,.85,e):0;
        if(pull+up<=.001)return;
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.disableCull();RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var b=Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.POSITION_COLOR);var m=g.pose().last().pose();
        float w=g.guiWidth(),h=g.guiHeight();
        for(int i=0;i<80;i++) {
            double r=BtMath.random(i*31+8701),ph=BtMath.random(i*31+8702),a=i/80.0*Math.PI*2;
            float x,y,tx,ty,dx,dy,wide;
            double gain;
            if(pull>0){dx=(float)Math.cos(a);dy=(float)Math.sin(a);float edge=Math.max(w,h);x=w*.5F+dx*edge;y=h*.55F+dy*edge;tx=w*.5F+dx*(float)(h*(.2+.2*r));ty=h*.55F+dy*(float)(h*(.2+.2*r));wide=(float)(1+5*ph);gain=pull;}
            else {
                int seed=i*89+6600;boolean first=i<36;
                double born=first?.08:.1+.5*Math.pow(BtMath.random(seed+1),1.3),q=e-born;if(q<0)continue;
                double slope=(i%2==1?1:-1)*(.12+.18*Math.pow(BtMath.random(seed+2),.9));
                double speed=2600+1800*BtMath.random(seed+3),length=380+520*BtMath.random(seed+5);
                double y1=(first?760-1000*BtMath.random(seed+8):760)-speed*q,y0=y1+length;if(y0<-20||y1>700)continue;
                x=(float)((400+(1600-y0)*slope)/800*w);y=(float)(y0/680*h);
                tx=(float)((400+(1600-y1)*slope)/800*w);ty=(float)(y1/680*h);
                boolean lead=BtMath.random(seed+4)<.12;
                wide=(float)((lead?6+4*BtMath.random(seed+6):1.6+3*Math.pow(BtMath.random(seed+6),2))*h/680*.5);
                gain=up*(lead?.9:.35+.3*BtMath.random(seed+7));
                double norm=Math.hypot(tx-x,ty-y);dx=(float)((tx-x)/norm);dy=(float)((ty-y)/norm);
                float mx=x+(tx-x)*.72F,my=y+(ty-y)*.72F;
                BtImpactFrame.vertex(b,m,x,y,0xf4f8ff,0);BtImpactFrame.vertex(b,m,mx-dy*wide,my+dx*wide,0xf4f8ff,(float)gain);BtImpactFrame.vertex(b,m,tx,ty,0xf4f8ff,0);
                BtImpactFrame.vertex(b,m,x,y,0xf4f8ff,0);BtImpactFrame.vertex(b,m,tx,ty,0xf4f8ff,0);BtImpactFrame.vertex(b,m,mx+dy*wide,my-dx*wide,0xf4f8ff,(float)gain);
                continue;
            }
            BtImpactFrame.vertex(b,m,x-dy*wide,y+dx*wide,0xf4f8ff,(float)(.7*gain));BtImpactFrame.vertex(b,m,x+dy*wide,y-dx*wide,0xf4f8ff,(float)(.7*gain));BtImpactFrame.vertex(b,m,tx,ty,0xf4f8ff,0);
        }
        var mesh=b.build();if(mesh!=null)BufferUploader.drawWithShader(mesh);RenderSystem.enableCull();RenderSystem.disableBlend();
    }
}
