package com.echo.service.strategy;

import com.echo.service.strategy.impl.WithSeatStrategy;
import com.echo.service.strategy.impl.WithoutSeatStrategy;
import org.springframework.stereotype.Component;

/**
 * 座位策略工厂
 *
 * @author tulip
 */
@Component
public class SeatStrategyFactory {

    private final WithSeatStrategy withSeatStrategy;
    private final WithoutSeatStrategy withoutSeatStrategy;

    public SeatStrategyFactory(WithSeatStrategy withSeatStrategy, WithoutSeatStrategy withoutSeatStrategy) {
        this.withSeatStrategy = withSeatStrategy;
        this.withoutSeatStrategy = withoutSeatStrategy;
    }

    /**
     * 根据票种是否有座位选择策略
     *
     * @param hasSeats 票种是否有座位（Ticket.getHasSeats()）
     * @return 对应的座位处理策略
     */
    public SeatStrategy getStrategy(boolean hasSeats) {
        return hasSeats ? withSeatStrategy : withoutSeatStrategy;
    }
}
