package com.tsimafei.nexus_core.reminder.repository;

import com.tsimafei.nexus_core.reminder.domain.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    // fetch all active tasks that should trigger now
    @Query("SELECT r FROM Reminder r WHERE r.active = true AND r.remindAt IS NOT NULL AND r.remindAt <= :now")
    List<Reminder> findDueReminders(LocalDateTime now);

    // fetch all active entries for listing
    List<Reminder> findByActiveTrueOrderByCreatedAtDesc();
}