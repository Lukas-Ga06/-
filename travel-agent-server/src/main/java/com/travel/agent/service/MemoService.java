package com.travel.agent.service;

import com.travel.agent.entity.Memo;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.MemoRepository;
import com.travel.agent.util.Paging;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MemoService {

    private final MemoRepository memoRepository;

    public List<Memo> list(Long userId) {
        return memoRepository.findByUserIdOrderByDoneAscCreatedAtDesc(userId);
    }

    /** 备忘列表分页：done asc, created_at desc（与旧列表顺序一致） */
    public Page<Memo> page(Long userId, int offset, int limit) {
        Pageable pageable = Paging.pageable(offset, limit,
                Sort.by(Sort.Order.asc("done"), Sort.Order.desc("createdAt")));
        return memoRepository.findByUserId(userId, pageable);
    }

    /** 未完成备忘数量：COUNT 下推，不再先取全量再 filter */
    public long openCount(Long userId) {
        return memoRepository.countByUserIdAndDoneFalse(userId);
    }

    @Transactional
    public Memo add(Long userId, String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException("备忘内容不能为空");
        }
        return memoRepository.save(Memo.builder()
                .userId(userId)
                .content(content.trim())
                .build());
    }

    @Transactional
    public Memo toggle(Long userId, Long id) {
        Memo memo = memoRepository.findById(id)
                .filter(m -> m.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(404, "备忘不存在"));
        memo.setDone(!memo.isDone());
        return memoRepository.save(memo);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Memo memo = memoRepository.findById(id)
                .filter(m -> m.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(404, "备忘不存在"));
        memoRepository.delete(memo);
    }
}
