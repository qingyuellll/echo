package com.echo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.echo.constants.ShowMessages;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;


/**
 * 演出实体类(对应tb_show表)
 *
 * @author tulip
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_show")
public class Show implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "演出ID", example = "1")
    private Long id;

    @NotNull(message = ShowMessages.NAME_NOT_EMPTY)
    @Size(max = 100, message = ShowMessages.NAME_TOO_LONG)
    @Schema(description = "演出名称", required = true, example = "周杰伦2024演唱会")
    private String name;

    @NotNull(message = ShowMessages.TYPE_ID_NOT_EMPTY)
    @Schema(description = "演出类型ID", example = "1")
    private Long typeId;

    @Size(max = 2000, message = ShowMessages.IMAGES_TOO_LONG)
    @Schema(description = "图片URL列表(JSON数组)", example = "[\"https://example.com/img1.jpg\"]")
    private String images;

    @Size(max = 50, message = ShowMessages.AREA_TOO_LONG)
    @Schema(description = "所在区域", example = "天河区")
    private String area;

    @NotNull(message = ShowMessages.ADDRESS_NOT_EMPTY)
    @Size(max = 200, message = ShowMessages.ADDRESS_TOO_LONG)
    @Schema(description = "详细地址", required = true, example = "广州市天河区体育西路123号")
    private String address;

    @Min(value = -180, message = ShowMessages.LONGITUDE_MIN)
    @Max(value = 180, message = ShowMessages.LONGITUDE_MAX)
    @Schema(description = "经度坐标(LBS)", example = "113.324520")
    private Double x;

    @Min(value = -90, message = ShowMessages.LATITUDE_MIN)
    @Max(value = 90, message = ShowMessages.LATITUDE_MAX)
    @Schema(description = "纬度坐标(LBS)", example = "23.106414")
    private Double y;

    @Min(value = 0, message = ShowMessages.AVG_PRICE_NEGATIVE)
    @Schema(description = "平均票价(单位:分)", example = "50000")
    private Long avgPrice;

    @Min(value = 0, message = ShowMessages.SOLD_NEGATIVE)
    @Schema(description = "已售数量", example = "1200")
    private Integer sold;

    @Min(value = 0, message = ShowMessages.COMMENTS_NEGATIVE)
    @Schema(description = "评论数量", example = "356")
    private Integer comments;

    @Min(value = 1, message = ShowMessages.SCORE_MIN)
    @Max(value = 5, message = ShowMessages.SCORE_MAX)
    @Schema(description = "评分(1-5星)", example = "4")
    private Integer score;

    @NotNull(message = ShowMessages.STATUS_NOT_EMPTY)
    @Min(value = 0, message = ShowMessages.STATUS_MIN)
    @Max(value = 2, message = ShowMessages.STATUS_MAX)
    @Schema(description = "状态: 0=下架, 1=上架, 2=已结束", required = true, example = "1")
    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "定时发布时间", example = "2025-06-01T10:00:00")
    private LocalDateTime scheduledPublishTime;

    @Size(max = 50, message = ShowMessages.OPEN_HOURS_TOO_LONG)
    @Schema(description = "演出时间范围", example = "2025-06-01~2025-06-03")
    private String openHours;

    @NotNull(message = ShowMessages.OPEN_TIME_NOT_EMPTY)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "开演时间", required = true, example = "2025-06-01T19:30:00")
    private LocalDateTime openTime;

    @NotNull(message = ShowMessages.END_TIME_NOT_EMPTY)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "结束时间", required = true, example = "2025-06-01T22:00:00")
    private LocalDateTime endTime;

    @Size(max = 5000, message = ShowMessages.DESCRIPTION_TOO_LONG)
    private String description;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableField(exist = false)
    private Double distance;

    public static final int STATUS_OFFLINE = 0;
    public static final int STATUS_ONLINE = 1;
    public static final int STATUS_FINISHED = 2;

    public void setStatus(Integer status) {
        if (status != null && status != STATUS_OFFLINE && status != STATUS_ONLINE && status != STATUS_FINISHED) {
            throw new IllegalArgumentException(
                "Show.status 非法值: " + status + "，合法值: 0=下架, 1=上架, 2=已结束");
        }
        this.status = status;
    }

    public boolean isOnline() {
        return status != null && status == STATUS_ONLINE;
    }

    public boolean isOffline() {
        return status != null && status == STATUS_OFFLINE;
    }

    public boolean isFinished() {
        return status != null && status == STATUS_FINISHED;
    }

    public boolean isActive() {
        return isOnline() && !isExpired();
    }

    public boolean isExpired() {
        return endTime != null && endTime.isBefore(LocalDateTime.now());
    }

    public boolean hasLocation() {
        return x != null && y != null;
    }

    public String getFormattedPrice() {
        if (avgPrice == null) {
            return "暂无报价";
        }
        long yuan = avgPrice / 100;
        long fen = avgPrice % 100;
        if (fen > 0) {
            return String.format("¥%d.%02d起", yuan, fen);
        }
        return String.format("¥%d起", yuan);
    }
}
