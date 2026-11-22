package com.echo.unit.guide;

import com.echo.dto.*;
import com.echo.entity.*;
import com.echo.service.*;
import com.echo.utils.UserHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.*;
import java.sql.ResultSet;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuideReviewInsightsServiceTest {
 JdbcTemplate jdbc;IShowReviewService reviews;ITicketOrderService orders;IShowService shows;GuideReviewInsightsService service;
 @BeforeEach void setup(){jdbc=mock(JdbcTemplate.class);reviews=mock(IShowReviewService.class);orders=mock(ITicketOrderService.class);shows=mock(IShowService.class);service=new GuideReviewInsightsService(jdbc,new ObjectMapper(),reviews,orders,shows);UserDTO user=new UserDTO();user.setId(7L);UserHolder.saveUser(user);}
 @AfterEach void clear(){UserHolder.removeUser();}
 GuideReviewDTO input(){GuideReviewDTO v=new GuideReviewDTO();v.setShowId(1L);v.setScore(2);v.setTitle("实际感受");v.setContent("声音太响");v.setDimensions(Map.of("sound",1));return v;}
 @Test void unsupportedDimensionsCannotWriteReview(){GuideReviewDTO v=input();v.setDimensions(Map.of("verified",5));assertFalse(service.publish(v).getSuccess());verifyNoInteractions(reviews,jdbc);}
 @Test void invalidDimensionDoesNotWrite(){GuideReviewDTO v=input();v.setDimensions(Map.of("view",0));assertFalse(service.publish(v).getSuccess());verifyNoInteractions(reviews,jdbc);}
 @Test void anotherUsersOrderCannotGivePurchaseEvidence(){GuideReviewDTO v=input();v.setOrderId(99L);when(orders.getById(99L)).thenReturn(new TicketOrder().setUserId(8L).setShowId(1L).setStatus(3));assertFalse(service.publish(v).getSuccess());verifyNoInteractions(reviews,jdbc);}
 @Test void refundedOrDifferentShowOrdersCannotSupplySeatArea(){GuideReviewDTO v=input();v.setOrderId(99L);TicketOrder order=new TicketOrder().setUserId(7L).setShowId(1L).setStatus(5);when(orders.getById(99L)).thenReturn(order);assertFalse(service.publish(v).getSuccess());order.setStatus(2).setShowId(2L);assertFalse(service.publish(v).getSuccess());verifyNoInteractions(reviews,jdbc);}
 @Test void successfulReviewStoresOnlyEditableScoresAndAuthenticatedAuthor(){
  GuideReviewDTO v=input();doReturn(Result.ok(123L)).when(reviews).saveReview(any());assertTrue(service.publish(v).getSuccess());
  ArgumentCaptor<ShowReview> body=ArgumentCaptor.forClass(ShowReview.class);verify(reviews).saveReview(body.capture());assertEquals(2,body.getValue().getScore());assertEquals("声音太响",body.getValue().getContent());assertNull(body.getValue().getUserId());
  verify(jdbc).update(anyString(),eq(123L),eq(1L),eq(7L),isNull(),eq("{\"sound\":1}"),eq(""));
 }
 @Test void failedOriginalPublicationDoesNotStoreMetadata(){doReturn(Result.fail("已评测过")).when(reviews).saveReview(any());assertFalse(service.publish(input()).getSuccess());verifyNoInteractions(jdbc);}
 @Test void metadataFailurePropagatesSoTransactionRollsBack(){doReturn(Result.ok(123L)).when(reviews).saveReview(any());when(jdbc.update(anyString(),eq(123L),eq(1L),eq(7L),isNull(),eq("{\"sound\":1}"),eq(""))).thenThrow(new IllegalStateException("db unavailable"));assertThrows(IllegalStateException.class,()->service.publish(input()));}
 @Test void privateShowDoesNotLeakReviewMetadata(){Show show=new Show();show.setStatus(0);when(shows.getById(1L)).thenReturn(show);assertFalse(service.publicInsights(1L).getSuccess());verifyNoInteractions(jdbc);}
 @SuppressWarnings("unchecked") @Test void publicEvidenceSeparatesBadgesAndNeverReturnsOrderOrAuthorIds() throws Exception {
  Show show=new Show();show.setStatus(1);when(shows.getById(1L)).thenReturn(show);
  when(jdbc.query(anyString(),any(RowMapper.class),eq(1L))).thenAnswer(call->{RowMapper<Map<String,Object>> row=call.getArgument(1);ResultSet rs=mock(ResultSet.class);when(rs.getString("id")).thenReturn("9007199254740993");when(rs.getBoolean("purchased")).thenReturn(true);when(rs.getBoolean("verified")).thenReturn(false);when(rs.getBoolean("area_valid")).thenReturn(false);when(rs.getString("seat_area")).thenReturn("不应公开");return List.of(row.mapRow(rs,0));});
  Map<?,?> data=(Map<?,?>)service.publicInsights(1L).getData();Map<?,?> item=(Map<?,?>)((List<?>)data.get("items")).get(0);
  assertEquals("9007199254740993",item.get("reviewId"));assertEquals(true,item.get("purchased"));assertEquals(false,item.get("verified"));assertEquals("",item.get("seatArea"));assertFalse(item.containsKey("orderId"));assertFalse(item.containsKey("userId"));
 }
}
