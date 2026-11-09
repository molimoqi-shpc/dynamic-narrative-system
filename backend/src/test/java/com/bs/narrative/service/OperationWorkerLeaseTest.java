package com.bs.narrative.service;

import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.llm.LLMProvider;
import com.bs.narrative.repository.GameSceneRepository;
import com.bs.narrative.repository.GodProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No database or model calls: simulate old durable timestamps while holding real queue slots. */
class OperationWorkerLeaseTest {
    private static final String SID="lease-world",PID="lease-player",ID="lease-action";
    private static final String OP_QUERY="SELECT * FROM game_operation WHERE session_id=? AND branch_epoch=? AND operation_id=?";
    private final ObjectMapper json=new ObjectMapper();
    private JdbcTemplate jdbc;
    private ConversationOperationService service;
    private ThreadPoolExecutor drivers;
    private LLMProvider model;
    private final CountDownLatch release=new CountDownLatch(1);
    private Map<String,Object> runtime,operation;

    @BeforeEach void setup(){
        jdbc=mock(JdbcTemplate.class);model=mock(LLMProvider.class);
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service=new ConversationOperationService(jdbc,manager,mock(SessionStateService.class),mock(GameSceneRepository.class),
            mock(GodProfileRepository.class),mock(OpenSceneService.class),mock(TemporalContextService.class),mock(WorldTurnService.class),
            mock(ItemStateService.class),model,mock(ContinuousStoryService.class),mock(GameActionService.class),mock(OperationEffectService.class));
        var usage=mock(OperationUsageService.class);when(usage.forOperation(SID,0L,ID)).thenReturn(Map.of("calls",0));when(usage.nextCallCeiling(SID,0L,ID)).thenReturn(24L);
        ReflectionTestUtils.setField(service,"usage",usage);ReflectionTestUtils.setField(service,"journal",mock(OperationJournalService.class));
        var mainModels=mock(MainModelService.class);when(mainModels.selected(SID)).thenReturn("deepseek-v4-flash");ReflectionTestUtils.setField(service,"mainModels",mainModels);
        runtime=new HashMap<>(Map.of("branch_epoch",0L,"fence",7L,"revision",1L,"active_operation",ID));
        operation=new HashMap<>(Map.of("player_id",PID,"fence",7L,"status","RUNNING","payload",
            "{\"operationId\":\""+ID+"\",\"storyEngine\":\"SCENE_V1\",\"logId\":1,\"events\":[],\"queue\":[],\"endSeconds\":0}"));
        when(jdbc.queryForObject("SELECT player_id FROM game_session WHERE session_id=?",String.class,SID)).thenReturn(PID);
        when(jdbc.queryForObject("SELECT active_session_id FROM player_profile WHERE player_id=?",String.class,PID)).thenReturn(SID);
        when(jdbc.queryForMap(startsWith("SELECT * FROM game_runtime WHERE session_id=?"),eq(SID))).thenAnswer(call->runtime);
        when(jdbc.queryForList(eq(OP_QUERY),eq(SID),anyLong(),eq(ID))).thenAnswer(call->List.of(operation));
        when(jdbc.queryForObject(startsWith("SELECT TIMESTAMPDIFF(SECOND,updated_at,CURRENT_TIMESTAMP)"),eq(Long.class),eq(SID),anyLong(),eq(ID))).thenReturn(120L);
        doAnswer(call->{runtime.put("fence",call.getArgument(1));return 1;}).when(jdbc).update(eq("UPDATE game_runtime SET fence=? WHERE session_id=?"),anyLong(),eq(SID));
        doAnswer(call->{operation.put("fence",call.getArgument(1));operation.put("status","RUNNING");return 1;}).when(jdbc).update(
            eq("UPDATE game_operation SET status='RUNNING',fence=?,updated_at=CURRENT_TIMESTAMP(6) WHERE session_id=? AND branch_epoch=? AND operation_id=?"),anyLong(),eq(SID),anyLong(),eq(ID));
        doAnswer(call->{operation.put("payload",call.getArgument(1));operation.put("status",call.getArgument(2));return 1;}).when(jdbc).update(
            eq("UPDATE game_operation SET payload=?,status=?,updated_at=CURRENT_TIMESTAMP(6) WHERE session_id=? AND branch_epoch=? AND operation_id=? AND fence=?"),anyString(),anyString(),eq(SID),anyLong(),eq(ID),anyLong());
        drivers=(ThreadPoolExecutor)ReflectionTestUtils.getField(service,"drivers");
    }
    @AfterEach void cleanup()throws Exception{
        service.destroy();release.countDown();assertTrue(drivers.awaitTermination(5,TimeUnit.SECONDS));verifyNoInteractions(model);
    }
    private void holdPool()throws Exception{
        var entered=new CountDownLatch(2);
        for(int n=0;n<2;n++)drivers.execute(()->{entered.countDown();awaitRelease();});
        assertTrue(entered.await(5,TimeUnit.SECONDS));
    }
    private void awaitRelease(){try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    private void dispatch(long epoch,long fence){ReflectionTestUtils.invokeMethod(service,"dispatch",SID,epoch,ID,fence);}
    private Map<?,?> localWorkers(){return (Map<?,?>)ReflectionTestUtils.getField(service,"localWorkers");}

    @Test void queuedTaskOlderThanLeaseIsStillRunningAndCannotBeRecoveredTwice()throws Exception{
        holdPool();dispatch(0,7);dispatch(0,7);
        assertEquals(1,drivers.getQueue().size(),"One durable operation must have one local task");
        assertEquals("RUNNING",service.read(SID,PID,ID,0L).get("status"));
        var conflict=assertThrows(BusinessException.class,()->service.recoverAsync(SID,PID,ID,0));
        assertTrue(conflict.getMessage().contains("仍在执行"));assertEquals(7L,runtime.get("fence"));assertEquals(1,localWorkers().size());
    }
    @Test void aRealInFlightWorkerAlsoOutlivesItsOldDurableTimestamp()throws Exception{
        var entered=new CountDownLatch(1);var first=new AtomicInteger();
        when(jdbc.queryForList(eq(OP_QUERY),eq(SID),anyLong(),eq(ID))).thenAnswer(call->{
            if(Thread.currentThread().getName().equals("story-operation") && first.getAndIncrement()==0){entered.countDown();awaitRelease();}
            return List.of(operation);
        });
        dispatch(0,7);assertTrue(entered.await(5,TimeUnit.SECONDS));
        assertEquals("RUNNING",service.read(SID,PID,ID,0L).get("status"));assertThrows(BusinessException.class,()->service.recoverAsync(SID,PID,ID,0));
        operation.put("status","CANCELLED");release.countDown();drivers.shutdown();assertTrue(drivers.awaitTermination(5,TimeUnit.SECONDS));assertTrue(localWorkers().isEmpty());
    }
    @Test void synchronousCallerAlsoOwnsItsOperationUntilItsWorkerFinishes()throws Exception{
        var entered=new CountDownLatch(1);var first=new AtomicInteger();var caller=java.util.concurrent.Executors.newSingleThreadExecutor(task->new Thread(task,"sync-story-test"));
        when(jdbc.queryForList(eq(OP_QUERY),eq(SID),anyLong(),eq(ID))).thenAnswer(call->{
            if(Thread.currentThread().getName().equals("sync-story-test") && first.getAndIncrement()==0){entered.countDown();awaitRelease();}
            return List.of(operation);
        });
        try{
            var completed=caller.submit(()->{ReflectionTestUtils.invokeMethod(service,"run",SID,0L,ID,7L);});assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals("RUNNING",service.read(SID,PID,ID,0L).get("status"));assertThrows(BusinessException.class,()->service.recoverAsync(SID,PID,ID,0));
            operation.put("status","CANCELLED");release.countDown();completed.get(5,TimeUnit.SECONDS);assertTrue(localWorkers().isEmpty());
        }finally{release.countDown();caller.shutdownNow();assertTrue(caller.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void theCurrentWorkerCanRequestExactlyOneNextSliceWithoutLosingOwnership()throws Exception{
        var requested=new CountDownLatch(1);var continued=new CountDownLatch(1);var reads=new AtomicInteger();
        when(jdbc.queryForList(eq(OP_QUERY),eq(SID),anyLong(),eq(ID))).thenAnswer(call->{
            int read=reads.getAndIncrement();
            if(read==0){
                // These calls execute inside the real LocalWorker with its real worker guard.
                dispatch(0,7);dispatch(0,7);requested.countDown();awaitRelease();
            }
            // Stop each body before story generation. Handoff validation still sees the durable RUNNING row.
            if(read==1 || read==4){var stopped=new HashMap<>(operation);stopped.put("status","CANCELLED");return List.of(stopped);}
            if(read==3)continued.countDown();
            assertTrue(read<5,"Duplicate requests must not create extra continuation workers");return List.of(operation);
        });
        dispatch(0,7);assertTrue(requested.await(5,TimeUnit.SECONDS));
        dispatch(0,7);assertEquals(1,localWorkers().size(),"An external retry must not add another owner");
        release.countDown();assertTrue(continued.await(5,TimeUnit.SECONDS));drivers.shutdown();assertTrue(drivers.awaitTermination(5,TimeUnit.SECONDS));
        assertEquals(5,reads.get());assertTrue(localWorkers().isEmpty());assertEquals("RUNNING",operation.get("status"));
    }
    @Test void cancellationAfterAContinuationRequestDoesNotRescheduleTheOldWorker()throws Exception{
        var requested=new CountDownLatch(1);var reads=new AtomicInteger();
        when(jdbc.queryForList(eq(OP_QUERY),eq(SID),anyLong(),eq(ID))).thenAnswer(call->{
            if(Thread.currentThread().getName().equals("story-operation") && reads.getAndIncrement()==0){dispatch(0,7);requested.countDown();awaitRelease();}
            return List.of(operation);
        });
        dispatch(0,7);assertTrue(requested.await(5,TimeUnit.SECONDS));
        operation.put("status","CANCELLED");runtime.put("active_operation",null);runtime.put("fence",8L);
        release.countDown();drivers.shutdown();assertTrue(drivers.awaitTermination(5,TimeUnit.SECONDS));
        assertTrue(localWorkers().isEmpty());assertTrue(drivers.getQueue().isEmpty());assertEquals("CANCELLED",operation.get("status"));
    }
    @Test void unrelatedBusyWorkersDoNotHideAGenuinelyAbandonedOperation()throws Exception{
        holdPool();var expired=service.read(SID,PID,ID,0L);
        assertEquals("PARTIAL",expired.get("status"));assertEquals("LEASE_EXPIRED",expired.get("pendingKind"));
        assertEquals("RUNNING",service.recoverAsync(SID,PID,ID,0).get("status"));assertEquals(8L,runtime.get("fence"));
        assertThrows(BusinessException.class,()->service.recoverAsync(SID,PID,ID,0));assertEquals(1,drivers.getQueue().size());
    }
    @Test void cancellationRemovesQueuedWorkBeforeItCanMakeAModelCall()throws Exception{
        holdPool();dispatch(0,7);
        doAnswer(call->{operation.put("status","CANCELLED");return 1;}).when(jdbc).update(
            eq("UPDATE game_operation SET status='CANCELLED' WHERE session_id=? AND branch_epoch=? AND operation_id=?"),eq(SID),eq(0L),eq(ID));
        doAnswer(call->{runtime.put("active_operation",null);runtime.put("fence",8L);return 1;}).when(jdbc).update(
            eq("UPDATE game_runtime SET active_operation=NULL,fence=fence+1,revision=revision+1 WHERE session_id=?"),eq(SID));
        assertEquals("CANCELLED",service.cancel(SID,PID,ID,0).get("status"));assertTrue(localWorkers().isEmpty());assertTrue(drivers.getQueue().isEmpty());
    }
    @Test void replacingABranchKeepsOnlyItsExactCurrentOperationAndFence()throws Exception{
        holdPool();dispatch(0,7);runtime.put("branch_epoch",1L);runtime.put("fence",8L);operation.put("fence",8L);dispatch(1,8);
        assertEquals(2,drivers.getQueue().size());assertEquals("RUNNING",service.read(SID,PID,ID,1L).get("status"));
        assertEquals(1,drivers.getQueue().size());assertEquals(1,localWorkers().size());assertThrows(BusinessException.class,()->service.recoverAsync(SID,PID,ID,1));
    }
    @Test void queueRejectionReleasesOwnershipAndLeavesARecoverablePartialRecord()throws Exception{
        holdPool();for(int n=0;n<16;n++)drivers.execute(()->{});dispatch(0,7);
        assertTrue(localWorkers().isEmpty());assertEquals("PARTIAL",operation.get("status"));
        assertEquals("PROCESSING_UNAVAILABLE",json.readTree(operation.get("payload").toString()).path("pendingKind").asText());
    }
    @Test void startupStorageFailureReleasesOwnershipAndThreadLocalGuards()throws Exception{
        drivers.setCorePoolSize(1);drivers.setMaximumPoolSize(1);var reads=new AtomicInteger();
        when(jdbc.queryForList(eq(OP_QUERY),eq(SID),anyLong(),eq(ID))).thenAnswer(call->{if(reads.getAndIncrement()==0){dispatch(0,7);throw new IllegalStateException("simulated storage failure");}return List.of(operation);});
        dispatch(0,7);
        // The submitted probe runs after the failed worker on the same single executor thread.
        assertTrue(drivers.submit(()->localWorkers().isEmpty()
            && ((ThreadLocal<?>)ReflectionTestUtils.getField(service,"workerGuard")).get()==null
            && ((ThreadLocal<?>)ReflectionTestUtils.getField(service,"workingPayload")).get()==null
            && ((ThreadLocal<?>)ReflectionTestUtils.getField(service,"failedJob")).get()==null).get(5,TimeUnit.SECONDS));
        assertEquals("PARTIAL",operation.get("status"));
    }
}
