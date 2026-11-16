package com.travel.agent;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 乐观锁冲突必须回 409（并带统一 JSON），不能落到 500 兜底（B2 / P0-4）。
 *
 * 背景：Trip 加了 @Version 之后，并发修改同一行程时 Hibernate/Spring 会抛
 * ObjectOptimisticLockingFailureException（或 JPA 原生 OptimisticLockException）。
 * 若没有专门的处理器，它会掉进 {@code @ExceptionHandler(Exception.class)} →
 * 客户端看到「服务端异常，请稍后重试」500，无法区分「该刷新重试」与「服务端坏了」。
 *
 * 这里直接对处理方法做断言（不必制造真实并发）：命中优先级由 Spring 的
 * ExceptionHandlerExceptionResolver 按异常类型精确度决定，子类处理器优先于兜底。
 */
class OptimisticLockConflictTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("ObjectOptimisticLockingFailureException → 409「数据已被其他操作修改，请刷新后重试」")
    void objectOptimisticLockMapsTo409() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleOptimisticLock(new ObjectOptimisticLockingFailureException(Trip.class, 1L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(409);
        assertThat(response.getBody().getMessage()).contains("请刷新后重试");
    }

    @Test
    @DisplayName("父类 OptimisticLockingFailureException 与 JPA 原生 OptimisticLockException 同样映射 409")
    void supertypesMapTo409() {
        assertThat(handler.handleOptimisticLock(new OptimisticLockingFailureException("conflict"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        jakarta.persistence.OptimisticLockException jpaException =
                new jakarta.persistence.OptimisticLockException("conflict");
        assertThat(handler.handleOptimisticLock(jpaException).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}