package com.travel.agent.service;

import com.travel.agent.entity.HealthRecord;
import com.travel.agent.repository.HealthRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HealthService {

    private final HealthRecordRepository repository;

    public List<HealthRecord> list(Long userId) {
        return repository.findByUserIdOrderByDateDesc(userId);
    }

    public HealthRecord latest(Long userId) {
        return list(userId).stream().findFirst().orElse(null);
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

    /** 最近 7 天均值（不足 7 天按已有数据） */
    public double[] weeklyAverages(Long userId) {
        LocalDate since = LocalDate.now().minusDays(6);
        List<HealthRecord> recent = list(userId).stream()
                .filter(r -> !r.getDate().isBefore(since))
                .toList();
        double steps = recent.stream().filter(r -> r.getSteps() != null).mapToInt(HealthRecord::getSteps).average().orElse(0);
        double sleep = recent.stream().filter(r -> r.getSleepHours() != null).mapToDouble(HealthRecord::getSleepHours).average().orElse(0);
        return new double[]{steps, sleep, recent.size()};
    }

    /** 模拟小米手环导入：生成最近 7 天演示数据（已有数据不覆盖） */
    @Transactional
    public int seedDemo(Long userId) {
        int created = 0;
        for (int i = 6; i >= 0; i--) {
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

    public List<HealthRecord> last7Days(Long userId) {
        LocalDate since = LocalDate.now().minusDays(6);
        return list(userId).stream()
                .filter(r -> !r.getDate().isBefore(since))
                .sorted(Comparator.comparing(HealthRecord::getDate))
                .toList();
    }
}
