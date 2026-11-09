package com.bs.narrative.engine;

import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.engine.enums.StatKey;
import com.bs.narrative.engine.model.ActualStats;

/** V2: bounded mastery, diminishing high-stat growth and limited complementary skills. */
public final class StatsCalculator {
    private StatsCalculator() {}
    public static final String FORMULA_VERSION = "six-stats-v2";
    public static final int BASE_MAX = 10000;
    public static final int BEN_MAX = 100;
    public static final double GROWTH_KNEE = 100.0;
    public static final double GROWTH_SCALE = 50.0;
    public static final double SUPPORT_SCALE = 50.0;
    public static final double MASTERY_WEIGHT = 0.60;
    public static final double KNOWLEDGE_WEIGHT = 0.20;
    public static final double COMBAT_WEIGHT = 0.12;
    public static final double WILL_WEIGHT = 0.15;
    public static final double INTERVENE_THRESHOLD = 30.0;

    public static int sanitize(Integer value, StatKey key) {
        return Math.max(0,Math.min(key==StatKey.BEN ? BEN_MAX : BASE_MAX,value==null ? 0 : value));
    }
    /** Continuous at 100; derivative is 1 at the join, then decreases. */
    public static double growth(int base) {
        double x=Math.max(0,Math.min(BASE_MAX,base));
        return x<=GROWTH_KNEE ? x : GROWTH_KNEE+GROWTH_SCALE*StrictMath.log1p((x-GROWTH_KNEE)/GROWTH_SCALE);
    }
    /** 1..1.4: mastery does not double every attribute or multiply itself. */
    public static double mastery(int ben) {
        double b=Math.max(0,Math.min(BEN_MAX,ben));
        return 1+MASTERY_WEIGHT*b/(b+SUPPORT_SCALE);
    }
    /** Harmonic mean requires both supporting skills; one absent skill gives no synergy. */
    public static double support(int first,int second) {
        double a=growth(first),b=growth(second);
        double harmonic=a+b==0 ? 0 : 2*a*b/(a+b);
        return harmonic/(harmonic+SUPPORT_SCALE);
    }
    public static StatKey[] supportingKeys(StatKey key) {
        return switch(key) {
            case ZHI -> new StatKey[]{StatKey.HUI,StatKey.WEN};
            case HUI,WEN,BEN,GUI -> new StatKey[]{StatKey.ZHI,key==StatKey.HUI ? StatKey.WEN : StatKey.HUI};
            case DOU -> new StatKey[]{StatKey.HUI,StatKey.GUI};
        };
    }
    public static double supportWeight(StatKey key) {
        return switch(key) {case DOU -> COMBAT_WEIGHT;case BEN,GUI -> WILL_WEIGHT;default -> KNOWLEDGE_WEIGHT;};
    }
    public static boolean usesMastery(StatKey key) {return key!=StatKey.BEN && key!=StatKey.GUI;}
    public static ActualStats calcActualStats(GodProfile p) {
        int z=sanitize(p==null?null:p.getBaseZhi(),StatKey.ZHI),h=sanitize(p==null?null:p.getBaseHui(),StatKey.HUI);
        int d=sanitize(p==null?null:p.getBaseDou(),StatKey.DOU),w=sanitize(p==null?null:p.getBaseWen(),StatKey.WEN);
        int b=sanitize(p==null?null:p.getBaseBen(),StatKey.BEN),g=sanitize(p==null?null:p.getBaseGui(),StatKey.GUI);
        var base=new ActualStats(z,h,d,w,b,g,0,0,0,0,0,0);
        int[] actual=new int[6];
        for(StatKey key:StatKey.values()) actual[key.ordinal()]=(int)Math.round(unrounded(base,key));
        return new ActualStats(z,h,d,w,b,g,actual[0],actual[1],actual[2],actual[3],actual[4],actual[5]);
    }
    public static double unrounded(ActualStats base,StatKey key) {
        StatKey[] pair=supportingKeys(key);
        double modifier=1+supportWeight(key)*support(base.baseOf(pair[0]),base.baseOf(pair[1]));
        return growth(base.baseOf(key))*(usesMastery(key)?mastery(base.baseBen()):1)*modifier;
    }
    /** Normalization keeps one intervention component in its intended 0..40 budget. */
    public static double normalizedAbility(int actual) {
        double a=Math.max(0,actual);return 100*a/(a+SUPPORT_SCALE);
    }
    public static int inquiryAccuracy(int actualZhi,int actualHui) {
        double z=Math.max(0,actualZhi),h=Math.max(0,actualHui);
        return (int)Math.round(45+45*h/(h+40)+5*z/(z+40));
    }
    public static double perception(int actualWen,int actualHui) {
        return 0.65*Math.max(0,actualWen)+0.35*Math.max(0,actualHui);
    }
    public static int inquiryImportance(int actualWen,int actualHui) {
        double p=perception(actualWen,actualHui);return Math.max(2,Math.min(10,(int)StrictMath.floor(2+8*p/(p+60))));
    }
    public static boolean canSeeSecrets(int actualWen,int actualHui) {
        return perception(actualWen,actualHui)>=45 && actualHui>=20;
    }
    public static int statDifference(ActualStats actor,ActualStats defender,StatKey key) {
        return actor==null || defender==null || key==null ? 0 : actor.actualOf(key)-defender.actualOf(key);
    }
}
