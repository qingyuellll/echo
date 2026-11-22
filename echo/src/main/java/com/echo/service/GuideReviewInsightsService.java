package com.echo.service;

import com.echo.dto.*;
import com.echo.entity.*;
import com.echo.utils.UserHolder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.*;

/** Durable optional review details; evidence always comes from owned Echo orders. */
@Service
public class GuideReviewInsightsService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final IShowReviewService reviews;
    private final ITicketOrderService orders;
    private final IShowService shows;
    private static final Set<String> KEYS=Set.of("story","performance","sound","view","value");

    public GuideReviewInsightsService(JdbcTemplate jdbc,ObjectMapper mapper,IShowReviewService reviews,ITicketOrderService orders,IShowService shows) {
        this.jdbc=jdbc;this.mapper=mapper;this.reviews=reviews;this.orders=orders;this.shows=shows;
    }

    @PostConstruct public void initialize() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tb_guide_review_detail (
              review_id BIGINT NOT NULL PRIMARY KEY, show_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
              order_id BIGINT NULL, dimensions TEXT NOT NULL, seat_area VARCHAR(255) NOT NULL DEFAULT '',
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, INDEX idx_guide_review_show(show_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
    }

    @Transactional public Result<?> publish(GuideReviewDTO input) {
        Long userId=UserHolder.getUser().getId();
        Map<String,Integer> dimensions=input.getDimensions()==null?Map.of():input.getDimensions();
        if(dimensions.size()>5 || dimensions.entrySet().stream().anyMatch(e->!KEYS.contains(e.getKey())||e.getValue()==null||e.getValue()<1||e.getValue()>5)) return Result.fail("分项评分须为 1–5 星，可留空不评");
        String area="";
        if(input.getOrderId()!=null) {
            TicketOrder order=orders.getById(input.getOrderId());
            if(order==null||!Objects.equals(order.getUserId(),userId)||!Objects.equals(order.getShowId(),input.getShowId())||!Integer.valueOf(2).equals(order.getStatus())&&!Integer.valueOf(3).equals(order.getStatus())) return Result.fail("请选择自己在本场演出中已付款或已核验的订单");
            List<String> areas=jdbc.query("SELECT DISTINCT area FROM tb_seat WHERE order_id=? AND area IS NOT NULL AND area<>'' ORDER BY area",(rs,row)->rs.getString(1),order.getId());
            area=String.join(" / ",areas);
            if(area.length()>255)area=area.substring(0,255);
        }
        ShowReview review=new ShowReview();review.setShowId(input.getShowId());review.setScore(input.getScore());
        review.setTitle(input.getTitle());review.setContent(input.getContent());review.setImages(input.getImages());
        Result<?> result=reviews.saveReview(review);
        if(!Boolean.TRUE.equals(result.getSuccess()))return result;
        Long reviewId=review.getId()!=null?review.getId():Long.valueOf(String.valueOf(result.getData()));
        try {
            jdbc.update("INSERT INTO tb_guide_review_detail(review_id,show_id,user_id,order_id,dimensions,seat_area) VALUES(?,?,?,?,?,?)",reviewId,input.getShowId(),userId,input.getOrderId(),mapper.writeValueAsString(dimensions),area);
        } catch(Exception failure) {throw new IllegalStateException("评测详情保存失败，请查询发布结果",failure);}
        return result;
    }

    public Result<?> publicInsights(Long showId) {
        Show show=shows.getById(showId);
        if(show==null || !Integer.valueOf(1).equals(show.getStatus()))return Result.fail("演出当前不公开");
        List<Map<String,Object>> items=jdbc.query("""
            SELECT r.id,r.title,r.score, d.dimensions, d.seat_area,
              EXISTS(SELECT 1 FROM tb_ticket_order o WHERE o.user_id=r.user_id AND o.show_id=r.show_id AND o.status IN (2,3)) AS purchased,
              EXISTS(SELECT 1 FROM tb_ticket_order o WHERE o.user_id=r.user_id AND o.show_id=r.show_id AND o.status=3) AS verified,
              EXISTS(SELECT 1 FROM tb_ticket_order o WHERE o.id=d.order_id AND o.user_id=r.user_id AND o.show_id=r.show_id AND o.status IN (2,3)) AS area_valid
            FROM tb_show_review r LEFT JOIN tb_guide_review_detail d ON d.review_id=r.id WHERE r.show_id=? ORDER BY r.id
            """,(rs,row)->{
                Map<String,Object> item=new LinkedHashMap<>();item.put("reviewId",rs.getString("id"));item.put("title",Objects.toString(rs.getString("title"),""));item.put("score",rs.getInt("score"));
                item.put("purchased",rs.getBoolean("purchased"));item.put("verified",rs.getBoolean("verified"));
                item.put("seatArea",rs.getBoolean("area_valid")?Objects.toString(rs.getString("seat_area"),""):"");
                Map<String,Integer> scores=Map.of();
                try {if(rs.getString("dimensions")!=null)scores=mapper.readValue(rs.getString("dimensions"),new TypeReference<Map<String,Integer>>(){});}catch(Exception ignored){}
                item.put("dimensions",scores);return item;
            },showId);
        return Result.ok(Map.of("items",items,"updatedAt",java.time.OffsetDateTime.now().toString()));
    }

    public List<Map<String,Object>> ownReviewOrders(Long showId,Long userId) {
        return jdbc.query("""
            SELECT o.id,o.status,t.title,
              (SELECT GROUP_CONCAT(DISTINCT s.area ORDER BY s.area SEPARATOR ' / ') FROM tb_seat s WHERE s.order_id=o.id) AS areas
            FROM tb_ticket_order o LEFT JOIN tb_ticket t ON t.id=o.ticket_id
            WHERE o.show_id=? AND o.user_id=? AND o.status IN (2,3) ORDER BY o.id DESC
            """,(rs,row)->{Map<String,Object> item=new LinkedHashMap<>();item.put("id",rs.getString("id"));item.put("status",rs.getInt("status"));item.put("title",Objects.toString(rs.getString("title"),"票档"));item.put("seatArea",Objects.toString(rs.getString("areas"),""));return item;},showId,userId);
    }
}
