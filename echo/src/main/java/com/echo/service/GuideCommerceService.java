package com.echo.service;

import com.echo.dto.*;
import com.echo.entity.*;
import com.echo.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class GuideCommerceService {
    private final ITicketService tickets;
    private final IShowService shows;
    private final ITicketOrderService orders;
    private final IShowReviewService reviews;
    private final GuideActionExecutor actions;
    private final GuideReviewInsightsService insights;

    public GuideCommerceService(ITicketService tickets,IShowService shows,ITicketOrderService orders,IShowReviewService reviews,GuideActionExecutor actions,GuideReviewInsightsService insights) {
        this.tickets=tickets;this.shows=shows;this.orders=orders;this.reviews=reviews;this.actions=actions;this.insights=insights;
    }

    public Result<?> preview(Long ticketId) {
        Ticket ticket=tickets.getById(ticketId);
        if(ticket==null) return Result.fail("票档不存在");
        Show show=shows.getById(ticket.getShowId());
        if(show==null || !Integer.valueOf(1).equals(show.getStatus())) return Result.fail("演出当前不可购票");
        if(show.getEndTime()!=null && show.getEndTime().isBefore(LocalDateTime.now())) return Result.fail("演出已结束，无法下单");
        Result<?> detail=tickets.getTicketDetail(ticketId);
        if(!Boolean.TRUE.equals(detail.getSuccess())) return detail;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("ticket",detail.getData());result.put("show",show);
        result.put("updatedAt",java.time.OffsetDateTime.now().toString());
        result.put("unitPriceCents",ticket.getPayValue());result.put("paymentMode","simulation");
        result.put("purchasable",Integer.valueOf(1).equals(ticket.getStatus()) && tickets.isTicketActiveForPurchase(ticketId));
        return Result.ok(result);
    }

    public Object order(Long ticketId,GuideOrderDTO input) {
        if(UserHolder.getUser()==null) return Result.fail(401,"LOGIN_REQUIRED","请先登录 Echo");
        if(!Boolean.TRUE.equals(input.getConfirmed())) return Result.fail("请先确认订单");
        return actions.execute(UserHolder.getUser().getId(),"orders",input.getRequestId(),Map.of("ticketId",ticketId,"input",input),()->createOrder(ticketId,input));
    }

    private Result<?> createOrder(Long ticketId,GuideOrderDTO input) {
        Ticket ticket=tickets.getById(ticketId);
        if(ticket==null || !Integer.valueOf(1).equals(ticket.getStatus())) return Result.fail("票档已下架，请重新选择");
        Show show=shows.getById(ticket.getShowId());
        if(show==null || !Integer.valueOf(1).equals(show.getStatus()) || show.getEndTime()!=null && show.getEndTime().isBefore(LocalDateTime.now())) return Result.fail("演出已结束或不可购票");
        if(!Objects.equals(ticket.getPayValue(),input.getExpectedUnitPrice())) return Result.fail(409,"PRICE_CHANGED","票价已变化，请刷新订单信息并重新确认");
        if(input.getCount()==null || input.getCount()<1 || input.getCount()>10) return Result.fail("单次购票数量须为 1–10 张");
        List<Long> seats=input.getSeatIds()==null?List.of():input.getSeatIds();
        if(Boolean.TRUE.equals(ticket.getHasSeats())) {
            if(seats.size()!=input.getCount() || new HashSet<>(seats).size()!=seats.size() || seats.stream().anyMatch(x->x==null || x<=0)) return Result.fail("选座数量须与票数一致，且不能重复");
        } else if(!seats.isEmpty()) return Result.fail("此票档不需要选座");
        if(!tickets.isTicketActiveForPurchase(ticketId)) return Result.fail("票档暂不可购买，请刷新库存与开售时间");
        if(Integer.valueOf(1).equals(ticket.getType())) {
            if(input.getCount()!=1) return Result.fail("秒杀票每次限购 1 张");
            return orders.seckillTicket(ticketId,seats);
        }
        return Boolean.TRUE.equals(ticket.getHasSeats()) ? orders.createSeatOrder(ticketId,seats,input.getCount()) : orders.createTicketOrder(ticketId,input.getCount());
    }

    public Result<?> reviewContext(Long showId) {
        if(UserHolder.getUser()==null) return Result.fail(401,"LOGIN_REQUIRED","请先登录 Echo");
        Show show=shows.getById(showId);if(show==null) return Result.fail("演出不存在");
        Long userId=UserHolder.getUser().getId();
        List<TicketOrder> purchases=orders.list(new QueryWrapper<TicketOrder>().eq("user_id",userId).eq("show_id",showId).in("status",2,3));
        List<ShowReview> previous=reviews.list(new QueryWrapper<ShowReview>().eq("user_id",userId).eq("show_id",showId));
        Map<String,Object> result=new LinkedHashMap<>();result.put("show",show);
        result.put("purchased",!purchases.isEmpty());result.put("verified",purchases.stream().anyMatch(x->Integer.valueOf(3).equals(x.getStatus())));
        result.put("orders",insights.ownReviewOrders(showId,userId));
        result.put("existingReviewId",previous.isEmpty()?null:String.valueOf(previous.get(0).getId()));
        return Result.ok(result);
    }

    public Object publish(GuideReviewDTO input) {
        if(UserHolder.getUser()==null) return Result.fail(401,"LOGIN_REQUIRED","请先登录 Echo");
        if(!Boolean.TRUE.equals(input.getConfirmed())) return Result.fail("请确认评分与正文后发布");
        return actions.execute(UserHolder.getUser().getId(),"reviews",input.getRequestId(),input,()->insights.publish(input));
    }

    public Result<?> calendar(Long orderId) {
        if(UserHolder.getUser()==null)return Result.fail(401,"LOGIN_REQUIRED","请先登录 Echo");
        TicketOrder order=orders.getById(orderId);
        if(order==null || !Objects.equals(order.getUserId(),UserHolder.getUser().getId()))return Result.fail("订单不存在或无权查看");
        if(!Integer.valueOf(2).equals(order.getStatus())&&!Integer.valueOf(3).equals(order.getStatus()))return Result.fail("仅已付款或已核验订单可添加开演提醒");
        Show show=shows.getById(order.getShowId());
        if(show==null||show.getOpenTime()==null)return Result.fail("演出时间尚未公布");
        if(show.getOpenTime().isBefore(LocalDateTime.now()))return Result.fail("演出已开场，无需添加开演提醒");
        var format=java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC);
        String start=format.format(show.getOpenTime().atOffset(java.time.ZoneOffset.ofHours(8)));
        String end=show.getEndTime()!=null&&show.getEndTime().isAfter(show.getOpenTime())?"DTEND:"+format.format(show.getEndTime().atOffset(java.time.ZoneOffset.ofHours(8)))+"\r\n":"";
        String content="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Echo//Shopping Guide//ZH\r\nBEGIN:VEVENT\r\nUID:echo-"+orderId+"@local\r\nDTSTAMP:"+format.format(java.time.Instant.now())+"\r\nDTSTART:"+start+"\r\n"+end+"SUMMARY:"+calendarText(show.getName())+"\r\nLOCATION:"+calendarText(show.getAddress())+"\r\nDESCRIPTION:请提前核对演出时间、入场要求及电子票。时间变动请重新添加提醒。\r\nBEGIN:VALARM\r\nTRIGGER:-PT2H\r\nACTION:DISPLAY\r\nDESCRIPTION:距离演出开始还有两小时\r\nEND:VALARM\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        return Result.ok(Map.of("filename","echo-show-"+orderId+".ics","content",content));
    }
    private String calendarText(String value){return Objects.toString(value,"").replace("\\","\\\\").replace("\r","").replace("\n","\\n").replace(",","\\,").replace(";","\\;");}

    public Result<?> followUp(String orderId) {
        if(UserHolder.getUser()==null)return Result.fail(401,"LOGIN_REQUIRED","请先登录 Echo");
        Result<?> response=orders.getOrderById(orderId);
        if(!Boolean.TRUE.equals(response.getSuccess())||!(response.getData() instanceof Map<?,?> original))return response;
        Map<String,Object> data=new LinkedHashMap<>();original.forEach((key,value)->data.put(String.valueOf(key),value));
        if(orderId.matches("[1-9][0-9]{0,18}")) {
            try {
                TicketOrder order=orders.getById(Long.valueOf(orderId));
                if(order!=null&&Objects.equals(order.getUserId(),UserHolder.getUser().getId())) {
                    data.put("useTime",order.getUseTime());data.put("refundTime",order.getRefundTime());data.put("updateTime",order.getUpdateTime());
                }
            }catch(NumberFormatException ignored){}
        }
        data.put("updatedAt",java.time.OffsetDateTime.now().toString());
        return Result.ok(data);
    }
}
