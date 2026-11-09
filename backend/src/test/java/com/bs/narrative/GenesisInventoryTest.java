package com.bs.narrative;
import com.bs.narrative.service.GenesisInventory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class GenesisInventoryTest {
 final ObjectMapper json=new ObjectMapper();
 @Test void acceptsExplicitPlayerAndNpcResources()throws Exception{
  var packet=json.readTree("""
  [{"owner":"PLAYER","items":[{"name":"刻刀","basis":"你手中握着刻刀。"}]},
   {"owner":"甲","items":[{"name":"铜镜","basis":"甲举着铜镜。"}]}]
  """);
  var actual=GenesisInventory.parse(packet,"你手中握着刻刀。甲举着铜镜。",List.of("甲"));
  assertEquals(List.of("刻刀"),actual.get("PLAYER"));assertEquals(List.of("铜镜"),actual.get("甲"));
 }
 @Test void rejectsMissingOwnerAndMalformedItemNames()throws Exception{
  var packet=json.readTree("""
  [{"owner":"PLAYER","items":[{"name":"刻刀","basis":"你手中握着刻刀。"}]}]
  """);
  assertThrows(IllegalArgumentException.class,()->GenesisInventory.parse(packet,"你手中握着刻刀。",List.of("甲")));
  assertThrows(IllegalArgumentException.class,()->GenesisInventory.parse(json.readTree("[{\"owner\":\"PLAYER\",\"items\":[{\"name\":\"\"}]}]"),"",List.of()));
 }
 @Test void acceptsAuthoredHoldingsWithoutRequiringVerbatimSceneQuotes()throws Exception{
  var packet=json.readTree("""
  [{"owner":"PLAYER","items":[{"name":"沈家老算盘","basis":"父亲传下的算盘，开局已经携带"}]}]
  """);
  assertEquals(List.of("沈家老算盘"),GenesisInventory.parse(packet,"沈砚解开账箱，把算盘摆在桌角。",List.of()).get("PLAYER"));
  assertEquals(List.of("沈家老算盘"),GenesisInventory.parse(json.readTree("[{\"owner\":\"PLAYER\",\"items\":[{\"name\":\"沈家老算盘\"}]}]"),"开场只描述人物站在门边",List.of()).get("PLAYER"));
 }
 @Test void explicitPlayerNamesBindToThePlayerWithoutTakingAnotherPersonsItems()throws Exception{
  var packet=json.readTree("""
   [{"owner":"PLAYER","items":[]},{"owner":"阿德里安","items":[{"name":"旧银徽"}]},{"owner":"阿德里安·维斯","items":[{"name":"旧银徽"}]},{"owner":"米拉","items":[{"name":"铜钥匙"}]}]
   """);var before=packet.deepCopy();var bound=GenesisInventory.bindPlayer(packet,Set.of("阿德里安","阿德里安·维斯"));
  var actual=GenesisInventory.parse(bound,"这里只描述雨后现场",List.of("米拉"));assertEquals(List.of("旧银徽"),actual.get("PLAYER"));assertEquals(List.of("铜钥匙"),actual.get("米拉"));assertEquals(before,packet);
 }
}
