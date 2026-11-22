package com.echo.controller;

import com.echo.dto.Result;
import com.echo.service.GuideReviewInsightsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.validation.constraints.Min;

@RestController
@Validated
public class GuideReviewInsightsController {
    private final GuideReviewInsightsService insights;
    public GuideReviewInsightsController(GuideReviewInsightsService insights){this.insights=insights;}
    @GetMapping("/show-review/guide-insights")
    public Result<?> insights(@RequestParam @Min(1) Long showId){return insights.publicInsights(showId);}
}
