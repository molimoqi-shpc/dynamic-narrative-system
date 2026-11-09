package com.bs.narrative.llm;
/** Per-operation totals include objective and NPC calls, even if parsing later falls back. */
public final class LLMUsage {
    private LLMUsage() {}
    public record Totals(int calls,int input,int output,double cost) {}
    private static final ThreadLocal<Totals> CURRENT=new ThreadLocal<>();
    public static void begin() {CURRENT.set(new Totals(0,0,0,0));}
    public static void record(LLMResponse response) {
        Totals old=CURRENT.get(); if(old==null) return;
        CURRENT.set(new Totals(old.calls()+1,old.input()+response.tokenInput(),old.output()+response.tokenOutput(),old.cost()+response.costCny()));
    }
    public static void merge(Totals extra) {
        Totals old=CURRENT.get();if(old==null || extra==null)return;
        CURRENT.set(new Totals(old.calls()+extra.calls(),old.input()+extra.input(),old.output()+extra.output(),old.cost()+extra.cost()));
    }
    public static Totals totals() {Totals value=CURRENT.get();return value==null?new Totals(0,0,0,0):value;}
    public record CallEvent(String id,String model,String state,LLMResponse response,boolean known) {}
    private static final ThreadLocal<java.util.function.Consumer<CallEvent>> OBSERVER=new ThreadLocal<>();
    public static java.util.function.Consumer<CallEvent> observer(){return OBSERVER.get();}
    public static AutoCloseable observe(java.util.function.Consumer<CallEvent> sink){
        var previous=OBSERVER.get();if(sink==null)OBSERVER.remove();else OBSERVER.set(sink);
        return ()->{if(previous==null)OBSERVER.remove();else OBSERVER.set(previous);};
    }
    public static final class Call implements AutoCloseable {
        private final String id=java.util.UUID.randomUUID().toString(),model;
        private final java.util.function.Consumer<CallEvent> sink;private boolean completed;
        private Call(String model){this.model=model;sink=OBSERVER.get();emit("STARTED",null,false);}
        private void emit(String state,LLMResponse response,boolean known){
            if(sink!=null)try{sink.accept(new CallEvent(id,model,state,response,known));}
            catch(LLMApiException failure){throw failure;}
            catch(RuntimeException failure){throw new LLMApiException("模型费用记录未完成，停止调用或后续处理",false,failure);}
        }
        public void response(LLMResponse response,boolean known){completed=true;record(response);emit("RESPONSE",response,known);}
        @Override public void close(){if(!completed)emit("UNCONFIRMED",null,false);}
    }
    public static Call startCall(String model){return new Call(model);}
    public static void clear() {CURRENT.remove();OBSERVER.remove();}
}
