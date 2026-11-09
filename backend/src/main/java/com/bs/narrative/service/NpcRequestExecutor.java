package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.DisposableBean;
import java.util.*;
import java.util.concurrent.*;

/** Workers only perform HTTP with immutable messages. No repository/entity/transaction crosses threads. */
@Component
public class NpcRequestExecutor implements DisposableBean {
    private final ThreadPoolExecutor pool=new ThreadPoolExecutor(3,3,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(48),r->{Thread t=new Thread(r,"npc-model-request");t.setDaemon(true);return t;},
            new ThreadPoolExecutor.AbortPolicy());
    public record Result(LLMResponse response, LLMApiException error, LLMUsage.Totals usage) {}
    private Result invoke(LLMProvider provider,List<Map<String,String>> messages,java.util.function.Consumer<LLMUsage.CallEvent> observer){
        LLMUsage.begin();
        try(var scope=LLMUsage.observe(observer)){return new Result(LLMRequestPolicy.NPC.call(()->provider.chatSubForJson(messages)),null,LLMUsage.totals());}
        catch(Exception e){return new Result(null,e instanceof LLMApiException api?api:new LLMApiException("模型请求未完成",false,e),LLMUsage.totals());}
        finally{LLMUsage.clear();}
    }
    public List<Result> request(LLMProvider provider,List<List<Map<String,String>>> messages){
        var observer=LLMUsage.observer();
        List<CompletableFuture<Result>> futures=new ArrayList<>();
        for(var message:messages){
            // Copy every map: later caller mutation cannot change another actor's request.
            var snapshot=message.stream().map(Map::copyOf).toList();
            try{futures.add(CompletableFuture.supplyAsync(()->invoke(provider,snapshot,observer),pool));}
            catch(RejectedExecutionException e){
                futures.add(CompletableFuture.completedFuture(new Result(null,
                        new LLMApiException("NPC请求繁忙，本次维持现状",false),new LLMUsage.Totals(0,0,0,0))));
            }
        }
        List<Result> results=new ArrayList<>();
        RuntimeException failure=null;
        for(var future:futures){
            try{var result=future.join();LLMUsage.merge(result.usage());results.add(result);}
            catch(CompletionException e){if(failure==null)failure=e;}
        }
        // Drain all requests before throwing; no model workers left writing after operation ends.
        if(failure!=null)throw failure;
        return results;
    }
    /** Shared operation deadline is explicitly propagated to workers; thread-local state is never assumed to migrate. */
    public List<Result> request(LLMProvider provider,List<List<Map<String,String>>> messages,long deadline,LLMRequestPolicy policy,boolean subModel){
        var observer=LLMUsage.observer();
        var futures=new ArrayList<Future<Result>>();
        for(var message:messages){var snapshot=policy==LLMRequestPolicy.CHARACTER?ModelProtocol.messages(ModelProtocol.Stage.ROLE,message):message.stream().map(Map::copyOf).toList();
            try{futures.add(pool.submit(()->{
                LLMUsage.begin();
                try(var scope=LLMUsage.observe(observer)){var response=LLMRequestPolicy.within(deadline,()->policy.call(()->subModel?provider.chatSubForJson(snapshot):provider.chatForJson(snapshot)));return new Result(response,null,LLMUsage.totals());}
                catch(Exception e){return new Result(null,e instanceof LLMApiException api?api:new LLMApiException("模型请求未完成",false,e),LLMUsage.totals());}
                finally{LLMUsage.clear();}
            }));}catch(RejectedExecutionException busy){futures.add(CompletableFuture.completedFuture(new Result(null,new LLMApiException("计算队列繁忙，未生成决定",false),new LLMUsage.Totals(0,0,0,0))));}
        }
        var results=new ArrayList<Result>();
        for(var future:futures){Result result;
            try{result=future.get(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();future.cancel(true);result=new Result(null,new LLMApiException("请求处理被中断",false),new LLMUsage.Totals(0,0,0,0));}
            catch(ExecutionException | TimeoutException failure){future.cancel(true);result=new Result(null,new LLMApiException("计算预算内未获得结果",false),new LLMUsage.Totals(0,0,0,0));}
            LLMUsage.merge(result.usage());results.add(result);
        }
        return List.copyOf(results);
    }
    @Override public void destroy(){pool.shutdownNow();}
}
