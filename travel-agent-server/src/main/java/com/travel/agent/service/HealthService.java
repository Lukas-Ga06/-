package com.travel.agent.service;

import com.travel.agent.entity.HealthRecord;
import com.travel.agent.repository.HealthRecordRepository;
import com.travel.agent.util.Paging;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HealthService {

    /** 「最近 7 天」窗口：含今天在内的 7 个自然日 */
    private static final int WINDOW_DAYS = 7;

    private final HealthRecordRepository repository;

    public List<HealthRecord> list(Long userId) {
        return repository.findByUserIdOrderByDateDesc(userId);
    }

    /** 最近 7 天记录分页：窗口过滤、排序、分页都下推数据库（按日期升序，便于前端画曲线） */
    public Page<HealthRecord> last7Days(Long userId, int offset, int limit) {
        LocalDate since = LocalDate.now().minusDays(WINDOW_DAYS - 1L);
        Pageable pageable = Paging.pageable(offset, limit, Sort.by(Sort.Order.asc("date"), Sort.Order.asc("id")));
        return repository.findByUserIdAndDateGreaterThanEqual(userId, since, pageable);
    }

    public HealthRecord latest(Long userId) {
        return repository.findFirstByUserIdOrderByDateDescIdDesc(userId).orElse(null);
    }

    @Transactional
    public HealthRecord upsert(Long userId, LocalDate date, Integer steps, Double sleepHours,
                               Integer heartRate, Double weight) {
        HealthRecord record = repository.findByUserIdAndDate(userId, date)
                .orElseGet(() -> HealthRecord.builder().userId(userId).date(date).build());
        if (steps != null) record.setSteps(steps);
        if (sleepHours != null) record.setSleepHours(sleepHours);
        if (heartRate != null) record.setRestingHeartRate(heartRate);
        if (weight != null) record.setWeight(weight);
        return repository.save(record);
    }

    /**
     * 最近 7 天均值（不足 7 天按已有数据）。
     * AVG 与 COUNT 全部下推数据库，不再把窗口内记录读进内存再统计。
     *
     * @return {平均步数, 平均睡眠小时, 窗口内记录天数}
     */
    public double[] weeklyAverages(Long userId) {
        LocalDate since = LocalDate.now().minusDays(WINDOW_DAYS - 1L);
        Double steps = repository.averageStepsSince(userId, since);
        Double sleep = repository.averageSleepHoursSince(userId, since);
        long days = repository.countByUserIdAndDateGreaterThanEqual(userId, since);
        return new double[]{
                steps == null ? 0 : steps,
                sleep == null ? 0 : sleep,
                days
        };
    }

    /** 模拟小米手环导入：生成最近 7 天演示数据（已有数据不覆盖） */
    @Transactional
    public int seedDemo(Long userId) {
        int created = 0;
        for (int i = WINDOW_DAYS - 1; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            if (repository.findByUserIdAndDate(userId, date).isPresent()) {
                continue;
            }
            int steps = 5200 + (date.getDayOfMonth() * 937) % 6500;
            double sleep = 6.4 + (date.getDayOfMonth() * 17 % 20) / 10.0;
            int heart = 62 + (date.getDayOfMonth() * 7 % 14);
            double weight = 65.0 + (date.getDayOfMonth() * 3 % 15) / 10.0;
            repository.save(HealthRecord.builder()
                    .userId(userId).date(date)
                    .steps(steps).sleepHours(Math.round(sleep * 10) / 10.0)
                    .restingHeartRate(heart).weight(weight)
                    .build());
            created++;
        }
        return created;
    }
}
