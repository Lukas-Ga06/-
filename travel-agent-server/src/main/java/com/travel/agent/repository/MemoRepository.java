package com.travel.agent.repository;

import com.travel.agent.entity.Memo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoRepository extends JpaRepository<Memo, Long> {
    /** 未完成在前，其余按创建时间倒序 */
    List<Memo> findByUserIdOrderByDoneAscCreatedAtDesc(Long userId);
}
