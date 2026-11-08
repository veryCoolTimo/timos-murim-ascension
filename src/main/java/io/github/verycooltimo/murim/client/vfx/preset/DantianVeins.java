package io.github.verycooltimo.murim.client.vfx.preset;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static java.lang.Math.*;
import static io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.*;

/** Pure, body-local vein recipe. Offsets are perpendicular to each measured bone segment.
 * Source: docs/design/reference/dantian-1.png; round-7 key points with round-5 line recipe.
 */
public final class DantianVeins {
    public record Point(double along, double offset) { }
    public record Twig(int root, double length, double angle, double delay, boolean fork) { }
    public record Segment(List<Point> points, List<Twig> twigs) { }
    public enum Region { CENTRAL, CHEST, COLLAR, SHOULDER, UPPER_ARM, FOREARM, HAND, LAP, THIGH, SHIN, NECK }
    /** Coordinates across the visible bone face, in blocks at unit scale; never on its edge. */
    public record Route(Region region, int channel, int segment, double from, double to,
                        double startOffset, double endOffset, double curve, double width,
                        double delay, int level) { }
    public enum Junction { THROAT, SHOULDER, ELBOW, WRIST, KNEE, FOREHEAD }
    public record Bead(Junction region,int channel,double offset,double radius,double arrival,int order) { }
    private static final List<Bead> BEADS = List.of(
            new Bead(Junction.THROAT,0,0,.026,MERIDIAN_START+1.05,2),
            new Bead(Junction.SHOULDER,1,0,.026,MERIDIAN_START+1.75,4),
            new Bead(Junction.SHOULDER,2,0,.026,MERIDIAN_START+1.75,4),
            new Bead(Junction.ELBOW,1,0,.023,MERIDIAN_START+2.1,5),
            new Bead(Junction.ELBOW,2,0,.023,MERIDIAN_START+2.1,5),
            new Bead(Junction.WRIST,1,0,.022,MERIDIAN_START+2.45,6),
            new Bead(Junction.WRIST,2,0,.022,MERIDIAN_START+2.45,6),
            new Bead(Junction.KNEE,3,0,.026,MERIDIAN_START+2.8,7),
            new Bead(Junction.KNEE,4,0,.026,MERIDIAN_START+2.8,7),
            new Bead(Junction.FOREHEAD,0,0,.024,MERIDIAN_START+3.5,9));
    public static List<Bead> beads() { return BEADS; }
    private static final List<Route> ROUTES = createRoutes();
    public static List<Route> routes() { return ROUTES; }
    static List<Route> regenerate() { return createRoutes(); }
    private static List<Route> createRoutes() {
        List<Route> routes = new ArrayList<>();
        for (int j=0;j<3;j++) routes.add(new Route(Region.CENTRAL,0,j,0,1,0,0,0,3,0,0));
        for (int side=1;side<=2;side++) {
            routes.add(route(Region.CHEST,side,0));
            routes.add(route(Region.COLLAR,side,4));
            routes.add(route(Region.SHOULDER,side,5));
            routes.add(route(Region.UPPER_ARM,side,1));
            routes.add(route(Region.FOREARM,side,2));
            routes.add(route(Region.HAND,side,3));
            routes.add(route(Region.LAP,side+2,0));
            routes.add(route(Region.THIGH,side+2,1));
            routes.add(route(Region.SHIN,side+2,2));
        }
        routes.add(route(Region.NECK,0,4));
        return List.copyOf(routes);
    }
    private static Route route(Region region,int channel,int segment) {
        return new Route(region,channel,segment,0,1,0,0,0,1,0,0);
    }
    /** One short twig on chest, upper arm, forearm and thigh only; no secondary forks. */
    public static Segment generate(Route route,double scale) {
        Segment recipe=generate(route.channel(),route.segment(),scale);
        boolean twig=switch(route.region()) {
            case CHEST, UPPER_ARM, FOREARM, THIGH -> true;
            default -> false;
        };
        return new Segment(recipe.points(),twig?recipe.twigs().stream().limit(1).toList():List.of());
    }
    public static double alpha(double seconds) { return sharedFade(seconds); }
    public static int order(Route r) {
        return switch(r.region()) {
            case CENTRAL -> r.segment();
            case CHEST -> 3;
            case COLLAR -> 3;
            case SHOULDER -> 4;
            case UPPER_ARM -> 5;
            case FOREARM -> 6;
            case HAND -> 7;
            case LAP -> 6;
            case THIGH -> 7;
            case SHIN -> 8;
            case NECK -> 9;
        };
    }
    public static double reach(double seconds, Route r) {
        double duration=SEGMENT_SECONDS;
        double start=MERIDIAN_START+order(r)*SEGMENT_SECONDS+r.delay();
        return clamp((seconds-start)/duration);
    }
    public static double remaining(double seconds, Route r) {
        return 1; // Geometry stays extended throughout the shared alpha fade.
    }
    public static double routeWidth(Route r,double along,double scale) {
        // Keep the central trunk narrower than the white-hot reservoir.
        return r.region()==Region.CENTRAL ? (.045-.0225*clamp((r.segment()+along)/3))*scale
                : width(along,scale)*r.width();
    }
    public static double insetX(double x,double centre,double halfWidth,double scale) {
        double extent=max(0,halfWidth-.055*scale);
        return max(centre-extent,min(centre+extent,x));
    }
    public static double offset(Route r,double along) {
        return r.startOffset()*(1-along)+r.endOffset()*along+r.curve()*sin(PI*along);
    }
    public static Segment generate(int channel, int segment, double scale) {
        Random random = new Random(0xDA471AAL + channel * 1009L + segment * 97L);
        int count = 5 + random.nextInt(4);
        List<Point> points = new ArrayList<>();
        List<Twig> twigs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(new Point(i / (count - 1.0), i == 0 || i == count - 1 ? 0
                    : (random.nextDouble() * 2 - 1) * .025 * scale));
            if (i > 0 && i < count - 1 && i % 3 == 1)
                twigs.add(new Twig(i, (.05 + .10 * random.nextDouble()) * scale,
                        toRadians(25 + 35 * random.nextDouble()) * (random.nextBoolean() ? 1 : -1),
                        .1 + .1 * random.nextDouble(), false));
        }
        return new Segment(List.copyOf(points), List.copyOf(twigs));
    }
    public static double width(double along, double scale) { return (.045 - .027 * clamp(along)) * scale; }
    public static double twigReach(double seconds, int channel, int segment, double root, double delay) {
        return clamp((meridian(seconds - delay, channel, segment) - root) / .25);
    }
    public static double shimmer(double seconds, double along) {
        return .72 + .28 * pow(.5 + .5 * sin(2 * PI * (seconds / 1.2 - along)), 4);
    }
    /** Same 1.2-second phase as the shimmer at the root of a vein. */
    public static double coreSize(double seconds) {
        return 1 + .06 * (shimmer(seconds,0)-.72)/.28;
    }
    public static final double CORE_HOLD_RADIUS = .12;
    // Twice round 8's .096 formation-radius envelope at the end of the swell.
    public static double coreRadius(double seconds, double scale) {
        double radius;
        if(seconds<BURST_START) {
            double u=clamp((seconds-CORE_START)/SWELL_SECONDS);
            radius=.06+.132*u*u;
        } else {
            double settle=clamp((seconds-BURST_START)/EXPLOSION_SECONDS);
            radius=.192*(1-settle)+CORE_HOLD_RADIUS*coreSize(seconds)*settle;
        }
        return (radius*(1-release(seconds))+.025*release(seconds))*scale;
    }
    public static double coreAlpha(double seconds) { return seconds < CORE_START ? 0 : 1; }
    private DantianVeins() { }
}
