package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.network.BambooPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** ★12 geometry, sharing only material and leaf shards. No gameplay or animation state.
 * Sources: docs/adr/bamboo-looks-2026-11-05.md; styleframe-reference/README.md.
 * API verified read-only: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/Vec3.java
 * constructor/add/subtract/scale/multiply/cross/dot/normalize/lerp; client/Minecraft#getInstance,
 * ClientLevel#getEntity; Entity#position/getBbHeight. All vertices go through VfxDraw.
 * [НЕПРОВЕРЕНО: silhouettes, halo strength and mesh continuity require the tester's night footage.]
 */
final class BambooMasterVfx {
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final VfxColour MINT = new VfxColour(127/255F, 217/255F, 154/255F);
    private static final VfxColour EDGE = new VfxColour(46/255F, 139/255F, 87/255F);
    private static final VfxColour JADE = new VfxColour(.07F, .24F, .18F);
    private static final VfxColour IVORY = new VfxColour(1, .98F, .88F);
    // Reuse the approved filled palm outline, never the old all-forms signature dispatcher.
    private static final double[][] HAND = BambooScale.HAND;
    private static final int[][] PALM = BambooScale.triangulate(HAND);

    static void contactLight(BambooPayload p) {
        if (!BambooScale.blow(p.kind())) return;
        TechniqueLight.add(p.at(), 0x7FD99A, 3.8F, .8F, 1, 0, 8);
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        var caster = level.getEntity(p.caster());
        if (caster != null) TechniqueLight.add(caster.position().add(0, .8, 0), 0x7FD99A, 3.2F, .45F, 1, 0, 8);
        // The contact pool lights the target and the ground through the shared body/surface light renderers.
    }

    static int mark(VertexConsumer v, PoseStack.Pose pose, BambooPayload p, Vec3 at, Vec3 forward,
                    Vec3 camera, float age, double floor, long seed, boolean glow, double quality,
                    double[] touches) {
        if (age < 0 || age >= BambooLook.lifetime(p.kind())) return 0;
        Vec3 f = forward.multiply(1, 0, 1).normalize();
        if (f.lengthSqr() < .01) f = new Vec3(0, 0, 1);
        Vec3 right = UP.cross(f).normalize();
        switch (BambooLook.shape(p.kind())) {
            case PALM -> BambooScale.masteredPalm(v, pose,
                    at.add(0, .25, 0).add(right.add(f).normalize().scale(-.7 * Math.min(1, age / 5))),
                    right.add(f).normalize(), age, glow);
            case WALL -> {
                Vec3 root = new Vec3(at.x, floor, at.z).add(f.scale(.6));
                // Oblique across both fighter and side-camera axes: never an edge-on row.
                Vec3 across = right.add(f).normalize();
                for (int i = 0; i < BambooLook.WALL_STEMS; i++) {
                    double x = (i - 4) * .4 + (n(seed, i, 0) - .5) * .06;
                    Vec3 base = root.add(across.scale(x)).add(f.scale((n(seed, i, 1) - .5) * .18));
                    stem(v, pose, base, f, right, camera, age, seed, i,
                            BambooLook.wallHeight(seed, i), BambooLook.stemThickness(seed, i), false, 0, glow);
                }
                // Only the struck point flashes/cracks; neighbouring stems have no impact pulse.
                accent(v, pose, at, right, f, camera, age, 2.4, glow);
            }
            case BEND -> bend(v, pose, new Vec3(at.x, floor, at.z), f, right, camera, age, seed, glow);
            case BLADE_SHARDS -> {
                accent(v, pose, p.at(), right, f, camera, age, 1, glow);
                bladeBreak(v, pose, at, forward.normalize(), camera, age, seed, glow);
            }
            case HAND_PALM -> {
                palm(v, pose, at, right.add(f).normalize(), f, camera, age, seed, .11, glow);
                accent(v, pose, at, right, f, camera, age, 1.2, glow);
                accent(v, pose, at.add(f.scale(.25)), right, f, camera, age - 2.4F, 1.2, glow);
            }
            case GROVE -> {
                for (int i = 0; i < BambooLook.GROVE_STEMS; i++) {
                    double angle = i * 2.39996 + n(seed, i, 0) * .6;
                    double radius = 1.0 + n(seed, i, 1) * 1.2;
                    Vec3 base = new Vec3(at.x + Math.cos(angle) * radius, floor, at.z + Math.sin(angle) * radius);
                    double shudder = 0;
                    for (int touch = 0; touch < 6; touch++) if ((touch * 5 + 1) % BambooLook.GROVE_STEMS == i && touches[touch] >= 0)
                        shudder += Math.sin(touches[touch] * 3) * Math.exp(-touches[touch] * .8) * .15;
                    stem(v, pose, base, f, right, camera, age, seed, i,
                            BambooLook.groveHeight(seed, i), BambooLook.stemThickness(seed, i), true, shudder, glow);
                }
            }
            case TOUCH -> {
                // Six server facts remain separate. Each adjacent pair is a three-accent (90 ms spacing) group.
                double local = age + (p.duration() % 2) * 2;
                float power = 0;
                for (int i = 0; i < 3; i++) power = Math.max(power, BambooLook.pulse(local - i * 1.8, .7));
                leaf(v, pose, at.add(0, .9, 0), right.add(UP).normalize(), camera, .25, .15, power, glow);
            }
            case LEAVES -> {
                // Generic contact/throw facts must not add a competing palm or area explosion.
                if (p.kind() == BambooPayload.TRAIL)
                    leaf(v, pose, at, f, camera, .35, .15, BambooLook.fade(age, 0, 9), glow);
                else if (p.kind() == BambooPayload.LANDING || p.kind() == BambooPayload.GROUND || p.kind() == BambooPayload.RETURN_HIT)
                    shards(v, pose, at, f, right, camera, age, seed, 6, .4, glow, quality);
            }
        }
        return switch (BambooLook.shape(p.kind())) { case WALL -> 240; case GROVE -> 180; default -> 40; };
    }

    private static double n(long seed, int i, int channel) { return BambooLook.noise(seed, i, channel); }

    private static void palm(VertexConsumer v, PoseStack.Pose pose, Vec3 centre, Vec3 across, Vec3 normal,
                             Vec3 camera, float age, long seed, double scale, boolean glow) {
        // 80 ms normal impulse, then staggered triangle-to-leaf departure. No whole-palm fade.
        centre = centre.add(normal.scale(.32 * BambooLook.smooth(age / 1.6)));
        for (int i = 0; i < PALM.length; i++) {
            double start = 1.6 + n(seed, i, 0) * 3;
            double t = Math.max(0, age - start), morph = BambooLook.smooth(t / 2);
            double end = BambooLook.palmEnd(seed, i);
            float alpha = BambooLook.fade(age, 0, end);
            Vec3 a = palmPoint(centre, across, HAND[PALM[i][0]], scale);
            Vec3 b = palmPoint(centre, across, HAND[PALM[i][1]], scale);
            Vec3 c = palmPoint(centre, across, HAND[PALM[i][2]], scale);
            Vec3 mid = a.add(b).add(c).scale(1.0/3);
            Vec3 drift = normal.scale(t * (.06 + n(seed, i, 2) * .08))
                    .add(across.scale((n(seed, i, 3) - .5) * t * .16)).add(0, .08*t - .006*t*t, 0);
            double spin = t * (n(seed, i, 4) - .5) * .8;
            Vec3 axis = across.scale(Math.sin(spin)).add(UP.scale(Math.cos(spin))).scale(.22 + n(seed, i, 5) * .18);
            Vec3 width = axis.normalize().cross(normal).normalize().scale(.09 + n(seed, i, 6) * .07);
            Vec3 q0 = a.lerp(mid.subtract(axis), morph).add(drift);
            Vec3 q1 = b.lerp(mid.add(width), morph).add(drift);
            Vec3 q2 = c.lerp(mid.add(axis), morph).add(drift);
            Vec3 q3 = c.lerp(mid.subtract(width), morph).add(drift);
            VfxDraw.quad(v, pose, q0, q1, q2, q3, normal, alpha, i % 3 == 0 && !glow ? EDGE : MINT);
            // An inset 0.08-block ivory crest on the shard, rather than white triangle faces.
            Vec3 crestAxis = q2.subtract(q0).normalize();
            Vec3 crestWidth = crestAxis.cross(normal).normalize().scale(.04);
            Vec3 crestCentre = q0.add(q1).add(q2).add(q3).scale(.25);
            Vec3 crestBase = crestCentre.subtract(crestAxis.scale(.10));
            Vec3 crestTip = crestCentre.add(crestAxis.scale(.14));
            VfxDraw.quad(v, pose, crestBase, crestCentre.add(crestWidth), crestTip,
                    crestCentre.subtract(crestWidth), normal, alpha * (float)morph, IVORY);
            if (!glow) {
                Vec3 back = normal.scale(-.14 * (1 - morph));
                VfxDraw.quad(v, pose, q0.add(back), q3.add(back), q2.add(back), q1.add(back), normal, alpha, JADE);
            }
        }
    }
    private static Vec3 palmPoint(Vec3 c, Vec3 right, double[] p, double scale) {
        return c.add(right.scale(p[0] * scale)).add(0, p[1] * scale, 0);
    }

    /** One narrow blade from the two fingertips, 2.5 blocks at full extension. */
    static void sword(VertexConsumer v, PoseStack.Pose pose, Vec3 root, Vec3 forward, Vec3 camera, float age, boolean glow) {
        if (age < 0 || age >= 11) return;
        blade(v, pose, root, forward, camera, BambooLook.fade(age, 0, 11), glow);
    }

    /** Single straight trapezoid, including a finite .02 tip; no segmented spine. */
    private static void blade(VertexConsumer v, PoseStack.Pose pose, Vec3 root, Vec3 forward,
                              Vec3 camera, float alpha, boolean glow) {
        forward = forward.normalize();
        Vec3 side = forward.cross(camera.subtract(root)).normalize();
        if (side.lengthSqr() < .01) side = forward.cross(UP).normalize();
        if (side.lengthSqr() < .01) side = new Vec3(1, 0, 0);
        Vec3 tip = root.add(forward.scale(2.5));
        Vec3 width = side.scale(.11), end = side.scale(.01);
        float power = glow ? alpha * .22F : alpha;
        VfxDraw.quad(v, pose, root.subtract(width), root.add(width), tip.add(end), tip.subtract(end), UP, power, MINT);
        Vec3 lift = camera.subtract(root).normalize().scale(.006);
        Vec3 core = side.scale(.045), coreEnd = side.scale(.004);
        VfxDraw.quad(v, pose, root.subtract(core).add(lift), root.add(core).add(lift),
                tip.add(coreEnd).add(lift), tip.subtract(coreEnd).add(lift), UP, power, IVORY);
    }

    private static void bladeBreak(VertexConsumer v, PoseStack.Pose pose, Vec3 root, Vec3 forward,
                                   Vec3 camera, float age, long seed, boolean glow) {
        // Contact starts with the same unbroken blade, then independent leaves depart.
        // No morphing chain of joined triangles that reads as lightning.
        if (age < 4) {
            blade(v, pose, root, forward, camera, 1, glow);
            return;
        }
        Vec3 side = forward.cross(UP).normalize();
        if (side.lengthSqr() < .01) side = new Vec3(1, 0, 0);
        for (int i = 0; i < 12; i++) {
            double t = Math.max(0, age - 4 - n(seed, i, 0));
            Vec3 centre = root.add(forward.scale((i + .5) * 2.5 / 12))
                    .add(side.scale((n(seed, i, 2) - .5) * t * .12))
                    .add(0, .04 * t - .006 * t * t, 0);
            double spin = t * (n(seed, i, 1) - .5) * .7;
            Vec3 axis = forward.scale(Math.cos(spin)).add(UP.scale(Math.sin(spin)));
            leaf(v, pose, centre, axis, camera, .3, .10,
                    BambooLook.fade(age, 4, BambooLook.shardEnd(seed, i)), glow);
        }
    }

    private static void stem(VertexConsumer v, PoseStack.Pose pose, Vec3 root, Vec3 f, Vec3 right, Vec3 camera,
                             float age, long seed, int index, double height, double breadth,
                             boolean grove, double shudder, boolean glow) {
        double lean = (n(seed,index,4) - .5) * .8 + shudder;
        // Three continuous pieces with diagonal fracture planes, each rotating about its own break.
        double split1 = .24 + n(seed,index,5) * .16, split2 = .6 + n(seed,index,6) * .16;
        double[] cuts = {0, split1, split2, grove && index % 3 == 0 ? .87 : 1};
        for (int part = 0; part < 3; part++) {
            int id = index * 3 + part;
            double start = BambooLook.breakAt(seed,id,grove), end = BambooLook.endAt(seed,id,grove);
            double t = Math.max(0, age - start);
            float alpha = BambooLook.fade(age, 0, end);
            if (alpha <= 0) continue;
            double turn = t * (.03 + n(seed,id,9) * .1) * (n(seed,id,8) < .5 ? -1 : 1);
            Vec3 pivot = root.add(right.scale(lean * cuts[part])).add(0,height * cuts[part],0);
            Vec3 drift = f.scale((n(seed,id,10)-.5)*t*.08).add(0,-.007*t*t,0);
            for (int step = 0; step < 8; step++) {
                double a = cuts[part] + (cuts[part+1]-cuts[part])*step/8;
                double b = cuts[part] + (cuts[part+1]-cuts[part])*(step+1)/8;
                Vec3 p = stemPoint(root,right,height,lean,a,pivot,turn).add(drift);
                Vec3 q = stemPoint(root,right,height,lean,b,pivot,turn).add(drift);
                shaft(v, pose, p, q, right, f, breadth, a, b, alpha, glow);
            }
            if (part > 0) {
                Vec3 crown = stemPoint(root,right,height,lean,cuts[part+1],pivot,turn).add(drift);
                Vec3 axis = right.scale(index % 2 == 0 ? 1 : -1).add(UP.scale(.25 + n(seed,id,11)));
                double fall = Math.max(0, age - start + 1);
                crown = crown.add(right.scale(Math.sin(age*.12+id)*.08 + fall*.03)).add(0,-.008*fall*fall,0);
                leaf(v,pose,crown,axis,camera,.45+n(seed,id,12)*.3,.18+n(seed,id,13)*.1,alpha,glow);
            }
        }
    }
    /** Joined eight-sided shaft; oblique ends share vertices before independent fracture. */
    private static void shaft(VertexConsumer v, PoseStack.Pose pose, Vec3 p, Vec3 q,
                              Vec3 right, Vec3 f, double diameter, double a, double b,
                              float alpha, boolean glow) {
        // Minimum .25 diameter even at the crown; every facet spans at least .08 blocks.
        double ra = Math.max(.25, diameter * (1 - .12 * a)) * .5;
        double rb = Math.max(.25, diameter * (1 - .12 * b)) * .5;
        for (int face = 0; face < 8; face++) {
            double x = face * Math.PI / 4, y = (face + 1) * Math.PI / 4;
            Vec3 u = right.scale(Math.cos(x)).add(f.scale(Math.sin(x)));
            Vec3 w = right.scale(Math.cos(y)).add(f.scale(Math.sin(y)));
            Vec3 cutU = UP.scale(.08 * Math.cos(x)), cutW = UP.scale(.08 * Math.cos(y));
            VfxDraw.quad(v, pose, p.add(u.scale(ra)).add(cutU), q.add(u.scale(rb)).add(cutU),
                    q.add(w.scale(rb)).add(cutW), p.add(w.scale(ra)).add(cutW), u,
                    glow ? alpha * .22F : alpha, glow || face % 3 == 0 ? MINT : face % 3 == 1 ? EDGE : JADE);
        }
    }

    private static Vec3 stemPoint(Vec3 root, Vec3 right, double height, double lean, double u, Vec3 pivot, double turn) {
        Vec3 p = root.add(right.scale(lean * u*u)).add(0,height*u,0);
        Vec3 d = p.subtract(pivot);
        double x = d.dot(right), y = d.y;
        return pivot.add(right.scale(x*Math.cos(turn)-y*Math.sin(turn))).add(0,x*Math.sin(turn)+y*Math.cos(turn),0);
    }

    private static void bend(VertexConsumer v, PoseStack.Pose pose, Vec3 root, Vec3 f, Vec3 right,
                             Vec3 camera, float age, long seed, boolean glow) {
        // 150 ms compression -> 70 ms recoil. Cubic arc is sampled into joined faces, no segment joints.
        double compression = age < 3 ? BambooLook.smooth(age/3) : 1-BambooLook.smooth((age-3)/1.4);
        for (int part = 0; part < 4; part++) {
            double start = BambooLook.bendEnd(seed,part)-12, t = Math.max(0,age-start);
            float alpha = BambooLook.fade(age,0,start+12);
            Vec3 drift = right.scale((n(seed,part,1)-.5)*t*.12).add(0,-.009*t*t,0);
            double spin = t*(n(seed,part,2)-.5)*.15;
            for (int j = 0; j < 12; j++) {
                double a = (part+j/12.0)/4, b = (part+(j+1)/12.0)/4;
                Vec3 p = arc(root,f,right,a,compression,spin).add(drift);
                Vec3 q = arc(root,f,right,b,compression,spin).add(drift);
                shaft(v, pose, p, q, right, f, .32, a, b, alpha, glow);
            }
        }
        shards(v,pose,root.add(f.scale(1.1)).add(0,2,0),f,right,camera,age-3,seed,7,.45,glow,1);
    }
    private static Vec3 arc(Vec3 root,Vec3 f,Vec3 right,double u,double compression,double spin) {
        return root.add(f.scale(compression*2.2*u*u)).add(right.scale(Math.sin(spin)*u*2.8))
                .add(0,3.2*u-compression*1.2*u*u,0);
    }

    /** Retains the scale-era five-lane C spine and its return bend; painted leaves replace its thin blades. */
    static void ribbon(VertexConsumer v, PoseStack.Pose pose, Vec3 at, Vec3 f, Vec3 camera, float age,
                       long seed, boolean glow, Vec3 turnAt, Vec3 turnForward, float turnAge, double quality) {
        if (age < 0 || (turnAt == null ? age >= 34 : turnAge >= 24)) return;
        Vec3 right = UP.cross(f).normalize();
        int count = Math.max(10, (int) Math.ceil(80 * quality));
        for (int i = 0; i < count; i++) {
            double scatter = n(seed,i,0), u = (i / 5.0) / Math.max(1,count / 5.0);
            double angle = -.6 + u*4.2 + age*.07;
            double radius = 2.4 + (i%5)*.08 + .42*(scatter-.5);
            double reach = 8*Math.min(1,age/12)*u*u;
            double taper = 1-.9*u*Math.min(1,age/12);
            Vec3 radial = right.scale(Math.cos(angle)).add(f.scale(Math.sin(angle)));
            Vec3 p = at.add(radial.scale(radius*taper)).add(f.scale(reach))
                    .add(0,(u-.5)*2+.35*(scatter-.5),0);
            Vec3 tangent = right.scale(-Math.sin(angle)).add(f.scale(Math.cos(angle)+u*2)).add(0,.15,0);
            double fadeAge = turnAt == null ? age : turnAge;
            double end = BambooLook.ribbonEnd(seed,i,turnAt != null);
            float alpha = (float)(BambooLook.smooth((age-scatter*3)/3)
                    * (1-BambooLook.smooth((fadeAge-(end-10))/10)));
            if (turnAt != null) {
                Vec3 up = UP.subtract(turnForward.scale(turnForward.y)).normalize();
                Vec3 side = turnForward.cross(up).normalize();
                double travel = (u+turnAge*.045)%1, curve = travel*Math.PI;
                double bendRadius = .55+(i%5)*.12;
                Vec3 bent = turnAt.add(turnForward.scale(-Math.sin(curve)*bendRadius))
                        .add(up.scale((1-Math.cos(curve))*bendRadius)).add(side.scale((scatter-.5)*.45));
                double blend = 1-Math.pow(1-Math.min(1,turnAge/4),3);
                p = p.lerp(bent,blend);
                tangent = tangent.lerp(turnForward.scale(-Math.cos(curve)).add(up.scale(Math.sin(curve))),blend);
                alpha *= (float)(1-blend+blend*Math.min(1,Math.sin(curve)*4));
            }
            double depthScale = Math.clamp(1+(at.distanceTo(camera)-p.distanceTo(camera))*.04,.8,1.4);
            leaf(v,pose,p,tangent,camera,(.28+n(seed,i,1)*.20)*depthScale,.16*depthScale,alpha,glow);
        }
    }
    private static void shards(VertexConsumer v,PoseStack.Pose pose,Vec3 at,Vec3 f,Vec3 right,Vec3 camera,
                               float age,long seed,int count,double spread,boolean glow,double quality) {
        count=Math.max(2,(int)Math.ceil(count*quality));
        for(int i=0;i<count;i++) {
            double start=n(seed,i,0)*1.5,t=age-start;
            if(t<0) continue;
            double spin=t*(n(seed,i,1)-.5)*.7;
            Vec3 axis=right.scale(Math.cos(spin+i)).add(UP.scale(Math.sin(spin+i)));
            Vec3 p=at.add(f.scale(t*(.04+n(seed,i,2)*.08)))
                    .add(right.scale((n(seed,i,3)-.5)*(1-Math.exp(-t/3))*spread))
                    .add(0,(n(seed,i,4)-.3)*t*.07-.007*t*t,0);
            leaf(v,pose,p,axis,camera,.35+n(seed,i,5)*.3,.16+n(seed,i,6)*.1,
                    BambooLook.fade(age,start,BambooLook.shardEnd(seed,i)),glow);
        }
    }
    private static void accent(VertexConsumer v,PoseStack.Pose pose,Vec3 at,Vec3 right,Vec3 f,Vec3 camera,
                               float age,double ticks,boolean glow) {
        float power=BambooLook.pulse(age,ticks);
        if(power<=0) return;
        // One local slash/crack; broad 0.08-block crest, never a radial area burst.
        Vec3 a=at.add(right.scale(-.18)).add(0,-.1,0),b=at.add(right.scale(.16)).add(0,.2,0);
        VfxDraw.segment(v,pose,a,b,camera,.04,power,IVORY.red(),IVORY.green(),IVORY.blue());
    }
    /** Flat painted lanceolate leaf: two filled halves and a broad ivory crest, no tube midrib. */
    private static void leaf(VertexConsumer v,PoseStack.Pose pose,Vec3 at,Vec3 direction,Vec3 camera,
                             double length,double breadth,float alpha,boolean glow) {
        if(alpha<=0) return;
        Vec3 normal=camera.subtract(at).normalize();
        Vec3 axis=direction.subtract(normal.scale(direction.dot(normal))).normalize();
        if(axis.lengthSqr()<.01) axis=normal.cross(new Vec3(0,1,.1)).normalize();
        Vec3 width=axis.cross(normal).normalize();
        Vec3 base=at.subtract(axis.scale(length*.5)),tip=at.add(axis.scale(length*.5));
        Vec3 shoulder=at.subtract(axis.scale(length*.17));
        VfxDraw.quad(v,pose,base,shoulder.add(width.scale(breadth*.5)),tip,at,normal,alpha,MINT);
        VfxDraw.quad(v,pose,base,at,tip,shoulder.subtract(width.scale(breadth*.5)),normal,alpha,glow ? MINT : EDGE);
        Vec3 crest=width.scale(.04);
        VfxDraw.quad(v,pose,base,shoulder.add(crest),tip,shoulder.subtract(crest),normal,alpha*.65F,IVORY);
    }
    private BambooMasterVfx() {}
}
