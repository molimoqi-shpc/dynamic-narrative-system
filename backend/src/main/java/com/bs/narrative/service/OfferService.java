package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import com.bs.narrative.dto.ActionRequest;
import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.util.*;

/** Voluntary inventory transfers: proposals are not deliveries, and trades commit both sides together. */
@Service
public class OfferService {
 @org.springframework.beans.factory.annotation.Autowired private OpenSceneService physical;
    @org.springframework.beans.factory.annotation.Autowired private ItemStateService items;
    public record Offer(String kind,String sender,String recipient,String offered,String requested){}
    public record Check(Offer offer,String problem){}
    public record Result(String status,String text){}
    private List<String> inventory(GodProfile g){return g.getInventory()==null?List.of():g.getInventory();}
    public Check prepare(ActionRequest.StructuredIntent intent,LLMResponse model,String raw,String sid,Map<String,GodProfile> gods){
        var sender=gods.get(intent.actorGodId());var target=gods.get(intent.targetGodId());
        if(sender==null || target==null || !sid.equals(sender.getSessionId()) || !sid.equals(target.getSessionId()))return new Check(null,"对方不属于当前世界，物品往来没有成立。");
        String offered=intent.consumedItemOrTalisman();String requested="";
        if("TRADE".equals(intent.intent())){
            try{JsonNode n=ModelOutput.object(model.parsedJson()!=null?model.parsedJson():model.content()).path("exchange");requested=ModelOutput.text(n,"requestedItem","",64);}catch(Exception ignored){}
            if(requested.isBlank() || !inventory(target).contains(requested))return new Check(null,"对方没有可用于这项交换的指定物品，交换未成立。");
        }
        if(offered==null || !inventory(sender).contains(offered) || raw==null || !raw.contains(offered) || !requested.isBlank() && !raw.contains(requested)
                || raw.matches("(?s).*(不要|不想|不能|不愿|别|勿)[^，。；]{0,12}(给|赠|送|交|换).*"))return new Check(null,"你尚未明确提出这件实际持有的物品，物品没有转移。");
        if(raw.matches("(?s).*(不(给|送|赠|交|换)|不(把|将)[^，。；]{0,24}(给|送|赠|交|换)|未决定[^，。；]{0,12}(给|送|换)).*")
                || raw.matches("(?s).*(等|待)[^，。；]{0,30}(离开|走开|结束|安全)[^。；]{0,30}(再|才)[^。；]{0,15}(给|赠|送|换).*"))
            return new Check(null,"这是尚未授权立即执行的安排，物品没有转移。");
        if(offered.equals(requested) || inventory(target).contains(offered) || !requested.isBlank() && inventory(sender).contains(requested))return new Check(null,"双方已有同名物品，当前交换无法明确区分，物品没有转移。");
        return new Check(new Offer(intent.intent(),intent.actorGodId(),intent.targetGodId(),offered,requested),"");
    }
    public String privateProposal(Offer o,String giver){
        return "【只向你提出的物品往来】"+giver+(o.kind().equals("GIVE")?"希望赠予你「"+o.offered()+"」。":"提议用「"+o.offered()+"」交换你实际持有的「"+o.requested()+"」。")+
                "依据自己的需要、目标和资源决定。接受须输出accept_offer=true，拒绝输出false；不回答视为未接受。不得宣布物品已入手，服务器稍后验证执行。";
    }
    public Result settle(Offer o,List<NpcAgentService.NpcDecision> decisions,GameScene scene,Map<String,GodProfile> gods){
        var giver=gods.get(o.sender());var target=gods.get(o.recipient());
        if(giver==null || target==null || physical!=null && (!physical.awake(giver.getSessionId(),giver.getGodId(),scene) || !physical.awake(giver.getSessionId(),target.getGodId(),scene)) || !scene.getPresentGods().contains(o.recipient()))return new Result("BLOCKED","对方已离场或无法回应，物品仍留在原持有者手中。");
        var reply=decisions.stream().filter(d->d.godId().equals(o.recipient()) && !d.usedFallback()).findFirst();
        if(reply.isEmpty() || reply.get().acceptOffer()==null)return new Result("NO_RESPONSE",target.getGodName()+"尚未接受这项提议，物品没有转移。");
        if(!reply.get().acceptOffer())return new Result("DECLINED",target.getGodName()+"拒绝了这项提议，物品没有转移。");
        var a=new ArrayList<>(inventory(giver));var b=new ArrayList<>(inventory(target));
        if(!a.contains(o.offered()) || b.contains(o.offered()) || !o.requested().isBlank() && (!b.contains(o.requested()) || a.contains(o.requested())))return new Result("BLOCKED","物品持有情况已经改变，这项交换没有执行。");
        if(items!=null)items.exchange(giver.getSessionId(),giver,target,o.offered(),o.requested());
        a.remove(o.offered());b.add(o.offered());
        if(!o.requested().isBlank()){b.remove(o.requested());a.add(o.requested());}
        giver.setInventory(a);target.setInventory(b);
        return new Result("COMPLETED",target.getGodName()+(o.kind().equals("GIVE")?"接受了你赠予的「"+o.offered()+"」。":"与你交换了「"+o.offered()+"」和「"+o.requested()+"」，双方背包已改变。"));
    }
}
