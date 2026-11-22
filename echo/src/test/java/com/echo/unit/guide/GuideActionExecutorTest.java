package com.echo.unit.guide;

import com.echo.dto.Result;
import com.echo.service.GuideActionExecutor;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuideActionExecutorTest {
    GuideActionExecutor executor;Map<String,String> saved;ObjectMapper mapper;ValueOperations<String,String> values;
    @SuppressWarnings("unchecked") @BeforeEach void setup(){
        saved=new HashMap<>();mapper=new ObjectMapper();StringRedisTemplate redis=mock(StringRedisTemplate.class);values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenAnswer(call->{String key=call.getArgument(0);if(saved.containsKey(key))return false;saved.put(key,call.getArgument(1));return true;});
        when(values.get(anyString())).thenAnswer(call->saved.get(call.getArgument(0)));
        doAnswer(call->{saved.put(call.getArgument(0),call.getArgument(1));return null;}).when(values).set(anyString(),anyString(),any(Duration.class));
        executor=new GuideActionExecutor(redis,mapper);
    }
    @Test void repeatedClickReturnsSameOrderWithoutCallingWriteAgain(){AtomicInteger writes=new AtomicInteger();Supplier<Result<?>> operation=()->{writes.incrementAndGet();return Result.ok(Map.of("tempId","TEMP_SAME"));};Object first=executor.execute(1L,"orders","request-1234567890",Map.of("count",1),operation);Object second=executor.execute(1L,"orders","request-1234567890",Map.of("count",1),operation);assertEquals(1,writes.get());assertEquals(mapper.valueToTree(first),second);}
    @Test void changedBodyCannotReuseRequestId(){executor.execute(1L,"orders","request-1234567890",Map.of("count",1),()->Result.ok());Object result=executor.execute(1L,"orders","request-1234567890",Map.of("count",2),()->{fail("changed body was executed");return Result.ok();});assertFalse(((Result<?>)result).getSuccess());}
    @Test void uncertainWriteStaysPendingAndIsNeverBlindlyRepeated(){AtomicInteger calls=new AtomicInteger();Supplier<Result<?>> operation=()->{calls.incrementAndGet();throw new IllegalStateException("outcome unknown");};executor.execute(1L,"orders","request-1234567890",Map.of(),operation);Object second=executor.execute(1L,"orders","request-1234567890",Map.of(),operation);assertEquals(1,calls.get());assertEquals("PENDING",((Map<?,?>)((Result<?>)second).getData()).get("status"));}
    @Test void userAndActionScopePreventsCrossAccountReplay(){AtomicInteger writes=new AtomicInteger();Supplier<Result<?>> op=()->{writes.incrementAndGet();return Result.ok();};executor.execute(1L,"orders","same-request-12345",Map.of(),op);executor.execute(2L,"orders","same-request-12345",Map.of(),op);executor.execute(1L,"reviews","same-request-12345",Map.of(),op);assertEquals(3,writes.get());}
    @Test void statusCanRecoverSuccessAndUnknownRequest(){executor.execute(1L,"reviews","request-1234567890",Map.of(),()->Result.ok(99L));JsonNode result=(JsonNode)executor.status(1L,"reviews","request-1234567890");assertEquals(99,result.get("data").asInt());Result<?> unknown=(Result<?>)executor.status(2L,"reviews","request-1234567890");assertEquals("NEW",((Map<?,?>)unknown.getData()).get("status"));}
    @Test void redisFailureNeverCallsBusinessWrite(){when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenThrow(new IllegalStateException());Object result=executor.execute(1L,"orders","request-1234567890",Map.of(),()->{fail("write without deduplication");return Result.ok();});assertFalse(((Result<?>)result).getSuccess());}
    @Test void reorderedNestedFieldsReplaySameRequestAcrossSerializationOrder(){
        Map<String,Object> first=new LinkedHashMap<>();first.put("count",2);first.put("dimensions",new LinkedHashMap<>(Map.of("view",2,"sound",3)));
        Map<String,Object> second=new LinkedHashMap<>();second.put("dimensions",new TreeMap<>(Map.of("sound",3,"view",2)));second.put("count",2);
        executor.execute(1L,"reviews","request-1234567890",first,()->Result.ok(123L));Object replay=executor.execute(1L,"reviews","request-1234567890",second,()->{fail("identical reordered content wrote twice");return Result.ok();});
        assertEquals(123,((JsonNode)replay).path("data").asInt());
    }
}
