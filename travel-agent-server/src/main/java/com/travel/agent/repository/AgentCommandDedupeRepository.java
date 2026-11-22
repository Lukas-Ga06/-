package com.travel.agent.repository;

import com.travel.agent.entity.AgentCommandDedupe;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentCommandDedupeRepository extends JpaRepository<AgentCommandDedupe, Long> {

    /** 幂等主键：同一用户的同一条写入命令只允许一行 */
    Optional<AgentCommandDedupe> findByUserIdAndRequestId(Long userId, String requestId);
}