package com.echo.controller;

import com.echo.dto.*;
import com.echo.service.GuideActionExecutor;
import com.echo.service.GuideCommerceService;
import com.echo.utils.UserHolder;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.validation.Valid;
import javax.validation.constraints.*;
import java.time.Duration;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/guide")
public class GuideCommerceController {
    private final GuideCommerceService commerce;
    private final GuideActionExecutor actions;
    private final RestTemplate draftClient;
    @Value("${echo.guide-base-url:http://127.0.0.1:8082}") private String guideBase;

    public GuideCommerceController(GuideCommerceService commerce,GuideActionExecutor actions,RestTemplateBuilder builder) {
        this.commerce=commerce;this.actions=actions;
        this.draftClient=builder.setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofSeconds(35)).build();
    }

    @GetMapping("/checkout/{ticketId}") public Result<?> preview(@PathVariable @Min(1) Long ticketId) {return commerce.preview(ticketId);}
    @PostMapping("/orders/{ticketId}") public Object order(@PathVariable @Min(1) Long ticketId,@Valid @RequestBody GuideOrderDTO input) {return commerce.order(ticketId,input);}
    @GetMapping("/reviews/context/{showId}") public Result<?> reviewContext(@PathVariable @Min(1) Long showId) {return commerce.reviewContext(showId);}
    @GetMapping("/orders/{orderId}/calendar") public Result<?> calendar(@PathVariable @Min(1) Long orderId) {return commerce.calendar(orderId);}
    @GetMapping("/order-follow-up/{orderId}") public Result<?> followUp(@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}|TEMP_[A-Za-z0-9_-]{1,64}") String orderId) {return commerce.followUp(orderId);}
    @PostMapping("/reviews") public Object publish(@Valid @RequestBody GuideReviewDTO input) {return commerce.publish(input);}
    @GetMapping("/requests/{action}/{requestId}") public Object status(@PathVariable @Pattern(regexp="orders|reviews") String action,@PathVariable @Pattern(regexp="[A-Za-z0-9-]{16,64}") String requestId) {
        if(UserHolder.getUser()==null) return Result.fail(401,"LOGIN_REQUIRED","请先登录 Echo");
        return actions.status(UserHolder.getUser().getId(),action,requestId);
    }

    public record DraftInput(@NotNull @Min(1) Long showId,@NotNull @Min(1) @Max(5) Integer score,@NotBlank @Size(max=4000) String notes) {}
    @PostMapping("/reviews/draft") public Result<?> draft(@Valid @RequestBody DraftInput input) {
        if(UserHolder.getUser()==null) return Result.fail(401,"LOGIN_REQUIRED","请先登录 Echo");
        try {
            JsonNode result=draftClient.postForObject(guideBase.replaceAll("/+$","")+"/api/v1/ticketing/review-draft",Map.of("show_id",String.valueOf(input.showId()),"score",input.score(),"notes",input.notes()),JsonNode.class);
            if(result==null || !result.hasNonNull("content")) return Result.fail("草稿暂不可用，你可以直接填写并发布评测");
            return Result.ok(result);
        } catch(Exception failure) {return Result.fail("草稿助手暂时不可用，你可以直接填写自己的感受并发布");}
    }
}
