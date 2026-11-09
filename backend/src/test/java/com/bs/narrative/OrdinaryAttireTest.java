package com.bs.narrative;
import com.bs.narrative.domain.GameScene;import com.bs.narrative.service.ItemStateService;
import com.fasterxml.jackson.databind.ObjectMapper;import org.junit.jupiter.api.Test;import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class OrdinaryAttireTest{
 @Test void ordinaryWornClothesDoNotNeedOrCreateInventoryButConcealedToolsStillDo(){
  var jdbc=mock(JdbcTemplate.class);var service=new ItemStateService(jdbc);var scene=new GameScene();scene.setSceneSummary("苏婉穿普通衣裳站在队列旁。");var plan=new ItemStateService.Plan(Map.of(),java.util.List.of());var a=new ObjectMapper().createArrayNode();
  for(String text:new String[]{"苏婉用袖口替他压平衣角褶皱。","苏婉用衣袖擦去手背灰尘。","苏婉用靴尖碰了碰地面。"})assertDoesNotThrow(()->service.checkPortableClaims(text,scene,plan,"su",a,a));
  for(String text:new String[]{"苏婉用袖口里的铜针划开绳子。","苏婉用袖中小刀切开绳子。","苏婉用衣襟护甲抵住机关。","苏婉用法衣抵住机关。"})assertThrows(IllegalArgumentException.class,()->service.checkPortableClaims(text,scene,plan,"su",a,a),text);
  verifyNoInteractions(jdbc);
 }
}
