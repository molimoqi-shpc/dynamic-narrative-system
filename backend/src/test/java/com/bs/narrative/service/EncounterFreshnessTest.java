package com.bs.narrative.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EncounterFreshnessTest {
 @Test void whitespaceAndEndingPunctuationDoNotCreateNewInformation(){assertEquals(EncounterService.normalized("我仍在等候。"),EncounterService.normalized(" 我仍在等候！ "));}
 @Test void quantitiesNegationAndNewConditionsRemainDifferent(){assertNotEquals(EncounterService.normalized("尚需走3.0里。"),EncounterService.normalized("尚需走30里。"));assertNotEquals(EncounterService.normalized("我愿意留下。"),EncounterService.normalized("我不愿意留下。"));assertNotEquals(EncounterService.normalized("我等船到。"),EncounterService.normalized("我等船到，但须在子时之前。"));}
}
