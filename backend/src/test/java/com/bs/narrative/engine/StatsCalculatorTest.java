package com.bs.narrative.engine;
import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.engine.enums.StatKey;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class StatsCalculatorTest {
    static GodProfile profile(int z,int h,int d,int w,int b,int g) {
        var p=new GodProfile();p.setBaseZhi(z);p.setBaseHui(h);p.setBaseDou(d);p.setBaseWen(w);p.setBaseBen(b);p.setBaseGui(g);return p;
    }
    @Test void independentlyCalculatedV2Example() {
        var actual=StatsCalculator.calcActualStats(profile(35,26,22,30,50,20));
        assertEquals(Map.of("zhi",49,"hui",36,"dou",30,"wen",42,"ben",53,"gui",21),actual.actualsAsMap());
    }
    @Test void noPrimarySkillCannotBeManufacturedBySupportOrMastery() {
        for(var key:StatKey.values()) {
            var p=profile(100,100,100,100,100,100);
            switch(key) {case ZHI->p.setBaseZhi(0);case HUI->p.setBaseHui(0);case DOU->p.setBaseDou(0);case WEN->p.setBaseWen(0);case BEN->p.setBaseBen(0);case GUI->p.setBaseGui(0);}
            assertEquals(0,StatsCalculator.calcActualStats(p).actualOf(key));
        }
    }
    @Test void nullNegativeAndOversizedValuesHaveSafeBounds() {
        assertTrue(StatsCalculator.calcActualStats(null).actualsAsMap().values().stream().allMatch(v->v==0));
        assertEquals(0,StatsCalculator.calcActualStats(profile(-1,-1,-1,-1,-1,-1)).actualDou());
        var max=StatsCalculator.calcActualStats(profile(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));
        assertEquals(100,max.baseBen());assertEquals(10000,max.baseDou());
        assertTrue(max.actualsAsMap().values().stream().allMatch(v->v>=0 && v<650));
    }
    @Test void growthIsContinuousAndDiminishesAboveKnee() {
        assertEquals(100,StatsCalculator.growth(100));
        assertTrue(StatsCalculator.growth(101)>100 && StatsCalculator.growth(101)<101);
        assertTrue(StatsCalculator.growth(102)-StatsCalculator.growth(101)<StatsCalculator.growth(101)-100);
        assertTrue(StatsCalculator.growth(10000)<400);
        double previous=-1;for(int i=0;i<=10000;i++){double current=StatsCalculator.growth(i);assertTrue(current>=previous);previous=current;}
    }
    @Test void allDimensionsAreMonotoneInTheirOwnBase() {
        for(var key:StatKey.values()) {
            int previous=-1;
            for(int x=0;x<=(key==StatKey.BEN?100:10000);x++) {
                var p=profile(30,40,50,60,50,75);
                switch(key){case ZHI->p.setBaseZhi(x);case HUI->p.setBaseHui(x);case DOU->p.setBaseDou(x);case WEN->p.setBaseWen(x);case BEN->p.setBaseBen(x);case GUI->p.setBaseGui(x);}
                int current=StatsCalculator.calcActualStats(p).actualOf(key);assertTrue(current>=previous,key+" decreased at "+x);previous=current;
            }
        }
    }
    @Test void supportNeedsBothSkillsAndMasteryDoesNotAmplifyLoyalty() {
        assertEquals(0,StatsCalculator.support(0,100));assertEquals(StatsCalculator.support(30,40),StatsCalculator.support(40,30));
        assertEquals(1,StatsCalculator.mastery(0));assertEquals(1.4,StatsCalculator.mastery(100),1e-12);
        assertEquals(StatsCalculator.calcActualStats(profile(30,40,50,60,0,75)).actualGui(),StatsCalculator.calcActualStats(profile(30,40,50,60,100,75)).actualGui());
    }
    @Test void formulaBookMatchesExactUnroundedAndRoundedEngineValues() {
        var p=profile(35,26,22,30,50,20);var book=StatFormulaBook.describe(p);
        var rows=(List<Map<String,Object>>)book.get("stats");var actual=StatsCalculator.calcActualStats(p);
        assertEquals(6,rows.size());assertEquals("six-stats-v2",book.get("version"));
        for(var row:rows){var key=StatKey.fromPass1Key((String)row.get("key"));assertEquals(actual.actualOf(key),row.get("actual"));assertEquals(actual.actualOf(key),(int)Math.round((Double)row.get("unrounded")));}
    }
    @Test void opposedAndInformationChecksHaveStableBoundaries() {
        assertEquals(10,ConflictResolver.clearWinThreshold(30,40));assertEquals(60,ConflictResolver.clearWinThreshold(310,290));
        assertEquals(45,StatsCalculator.inquiryAccuracy(0,0));assertTrue(StatsCalculator.inquiryAccuracy(10000,10000)<=95);
        assertEquals(2,StatsCalculator.inquiryImportance(0,0));assertTrue(StatsCalculator.inquiryImportance(10000,10000)<=10);
        assertFalse(StatsCalculator.canSeeSecrets(10000,0));assertTrue(StatsCalculator.canSeeSecrets(60,40));
        assertTrue(StatsCalculator.normalizedAbility(Integer.MAX_VALUE)<=100);
    }
}
