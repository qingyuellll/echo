package com.echo.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.entity.ShowType;
import com.echo.mapper.ShowTypeMapper;
import com.echo.service.IShowTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.echo.utils.RedisConstants.CACHE_SHOW_TYPE_KEY;


/**
 * 演出类型服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class ShowTypeServiceImpl extends ServiceImpl<ShowTypeMapper, ShowType> implements IShowTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询演出类型列表（含Redis缓存+空值缓存防穿透+异常降级）
     */
    @Override
    public Result<?> queryTypeList() {
        String key = CACHE_SHOW_TYPE_KEY + "list";

        try {
            // 1. 尝试从Redis读取缓存
            log.debug("[查询演出类型] 尝试从Redis读取缓存，key:{}", key);
            String typeListJson = stringRedisTemplate.opsForValue().get(key);

            if (StrUtil.isNotBlank(typeListJson)) {
                // 2. 判断是否为空值缓存
                if ("[]".equals(typeListJson)) {
                    log.debug("[查询演出类型] 命中空值缓存（防穿透），返回空列表");
                    return Result.ok(Collections.emptyList());
                }

                // 3. 正常缓存命中
                List<ShowType> cachedList = JSONUtil.toList(typeListJson, ShowType.class);
                log.info("[查询演出类型] 缓存命中，返回{}条演出类型", cachedList.size());
                return Result.ok(cachedList);
            }

            // 4. 缓存未命中，查DB
            log.info("[查询演出类型] 缓存未命中，回源数据库");
            List<ShowType> typeList = query().orderByAsc("sort").list();

            // 5. 写入缓存（含空值缓存）
            long ttl = getRandomExpireTime();  // 基础30分钟 + 随机0-10分钟

            if (typeList == null || typeList.isEmpty()) {
                // 空值缓存，防止缓存穿透（TTL=10分钟）
                stringRedisTemplate.opsForValue().set(
                        key,
                        "[]",
                        10,
                        TimeUnit.MINUTES
                );
                log.warn("[查询演出类型] 数据库无演出类型数据，已设置空值缓存（防穿透），TTL:10分钟");
                return Result.ok(Collections.emptyList());
            } else {
                // 正常数据缓存（随机TTL防雪崩）
                stringRedisTemplate.opsForValue().set(
                        key,
                        JSONUtil.toJsonStr(typeList),
                        ttl,
                        TimeUnit.SECONDS
                );
                log.info("[查询演出类型] 成功，共{}条，已缓存，TTL:{}分钟", typeList.size(), ttl);
                return Result.ok(typeList);
            }

        } catch (Exception e) {
            log.error("[查询演出类型] 异常，error:{}", e.getMessage(), e);

            // 6. 降级：直接查DB返回（不经过Redis）
            try {
                log.warn("[查询演出类型] Redis异常，降级为直接查DB");
                List<ShowType> fallbackList = query().orderByAsc("sort").list();
                return Result.ok(fallbackList != null ? fallbackList : Collections.emptyList());
            } catch (Exception ex) {
                log.error("[查询演出类型] 降级也失败，error:{}", ex.getMessage());
                return Result.fail(AppMessages.SHOW_TYPE_QUERY_FAIL);
            }
        }
    }

    /**
     * 生成随机过期时间（防止缓存雪崩）
     *
     * @return 最终TTL（分钟）
     */
    private long getRandomExpireTime() {
        return com.echo.utils.RedisConstants.CACHE_SHOW_TYPE_TTL + (long) (Math.random() * 10L);
    }
}
