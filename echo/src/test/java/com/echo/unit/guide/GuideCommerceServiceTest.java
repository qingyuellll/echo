package com.echo.unit.guide;

import com.echo.dto.*;
import com.echo.entity.*;
import com.echo.service.*;
import com.echo.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuideCommerceServiceTest {
    ITicketService tickets; IShowService shows; ITicketOrderService orders; IShowReviewService reviews;
    GuideCommerceService service;GuideReviewInsightsService insights;
    @BeforeEach void setup() {
        tickets=mock(ITicketService.class);shows=mock(IShowService.class);orders=mock(ITicketOrderService.class);reviews=mock(IShowReviewService.class);
        GuideActionExecutor executor=mock(GuideActionExecutor.class);
        when(executor.execute(anyLong(),anyString(),anyString(),any(),any())).thenAnswer(call->((Supplier<?>)call.getArgument(4)).get());
        insights=mock(GuideReviewInsightsService.class);service=new GuideCommerceService(tickets,shows,orders,reviews,executor,insights);
        UserDTO user=new UserDTO();user.setId(7L);UserHolder.saveUser(user);
        Ticket ticket=new Ticket().setId(11L).setShowId(1L).setStatus(1).setPayValue(28000L).setHasSeats(false).setType(0);
        Show show=new Show();show.setId(1L);show.setStatus(1);show.setEndTime(LocalDateTime.now().plusDays(1));
        when(tickets.getById(11L)).thenReturn(ticket);when(shows.getById(1L)).thenReturn(show);when(tickets.isTicketActiveForPurchase(11L)).thenReturn(true);
        doReturn(Result.ok(Map.of("tempId","TEMP_TEST_ORDER"))).when(orders).createTicketOrder(11L,2);
    }
    @AfterEach void clear(){UserHolder.removeUser();}
    GuideOrderDTO input(){GuideOrderDTO v=new GuideOrderDTO();v.setRequestId("12345678-1234-1234");v.setCount(2);v.setConfirmed(true);v.setExpectedUnitPrice(28000L);return v;}
    @Test void confirmedNormalOrderUsesServerPriceAndExistingService(){
        Result<?> result=(Result<?>)service.order(11L,input());assertTrue(result.getSuccess());verify(orders).createTicketOrder(11L,2);
    }
    @Test void priceChangeRequiresAnotherConfirmation(){GuideOrderDTO v=input();v.setExpectedUnitPrice(1L);assertFalse(((Result<?>)service.order(11L,v)).getSuccess());verifyNoInteractions(orders);}
    @Test void endedShowCannotCreateOrder(){shows.getById(1L).setEndTime(LocalDateTime.now().minusDays(1));assertFalse(((Result<?>)service.order(11L,input())).getSuccess());verifyNoInteractions(orders);}
    @Test void duplicateSeatsAreRejected(){tickets.getById(11L).setHasSeats(true);GuideOrderDTO v=input();v.setSeatIds(List.of(3L,3L));assertFalse(((Result<?>)service.order(11L,v)).getSuccess());verifyNoInteractions(orders);}
    @Test void seatedOrderUsesSeatServicePath(){tickets.getById(11L).setHasSeats(true);GuideOrderDTO v=input();v.setSeatIds(List.of(3L,4L));when(orders.createSeatOrder(11L,List.of(3L,4L),2)).thenReturn(Result.ok());assertTrue(((Result<?>)service.order(11L,v)).getSuccess());verify(orders).createSeatOrder(11L,List.of(3L,4L),2);verify(orders,never()).createTicketOrder(anyLong(),anyInt());}
    @Test void seckillUsesQueueAndRejectsMultipleTickets(){tickets.getById(11L).setType(1);assertFalse(((Result<?>)service.order(11L,input())).getSuccess());GuideOrderDTO v=input();v.setCount(1);doReturn(Result.ok(Map.of("status","QUEUED"))).when(orders).seckillTicket(11L,List.of());assertTrue(((Result<?>)service.order(11L,v)).getSuccess());verify(orders).seckillTicket(11L,List.of());}
    @Test void unconfirmedAndAnonymousActionsDoNotWrite(){GuideOrderDTO v=input();v.setConfirmed(false);assertFalse(((Result<?>)service.order(11L,v)).getSuccess());UserHolder.removeUser();assertFalse(((Result<?>)service.order(11L,input())).getSuccess());verifyNoInteractions(orders);}
    @Test void reviewedShowReturnsExistingLinkAndOwnedOrdersOnly(){when(orders.list(any())).thenReturn(List.of());ShowReview previous=new ShowReview();previous.setId(99L);when(reviews.list(any())).thenReturn(List.of(previous));Result<?> context=service.reviewContext(1L);Map<?,?> data=(Map<?,?>)context.getData();assertEquals("99",data.get("existingReviewId"));assertEquals(false,data.get("purchased"));}
    @Test void confirmedPublicationDelegatesToTransactionalInsightsService(){
        GuideReviewDTO v=new GuideReviewDTO();v.setRequestId("12345678-1234-1234");v.setConfirmed(true);v.setShowId(1L);v.setScore(2);v.setDimensions(Map.of("sound",1));doReturn(Result.ok(123L)).when(insights).publish(v);
        assertTrue(((Result<?>)service.publish(v)).getSuccess());verify(insights).publish(v);verify(reviews,never()).saveReview(any());
    }
    @Test void calendarRequiresOwnedPaidOrder(){
        TicketOrder order=new TicketOrder().setId(1L).setUserId(99L).setShowId(1L).setStatus(2);when(orders.getById(1L)).thenReturn(order);
        assertFalse(service.calendar(1L).getSuccess());order.setUserId(7L).setStatus(0);assertFalse(service.calendar(1L).getSuccess());
        order.setStatus(2);shows.getById(1L).setOpenTime(LocalDateTime.now().plusDays(1));shows.getById(1L).setName("现场\nBEGIN:UNTRUSTED");
        Result<?> result=service.calendar(1L);assertTrue(result.getSuccess());String content=(String)((Map<?,?>)result.getData()).get("content");assertTrue(content.contains("TRIGGER:-PT2H"));assertFalse(content.contains("\r\nBEGIN:UNTRUSTED"));assertTrue(content.contains("\\nBEGIN:UNTRUSTED"));
    }
}
