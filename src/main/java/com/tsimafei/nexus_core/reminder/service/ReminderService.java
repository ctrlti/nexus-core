package com.tsimafei.nexus_core.reminder.service;

import com.tsimafei.nexus_core.reminder.domain.Reminder;
import com.tsimafei.nexus_core.reminder.repository.ReminderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;

@Service
public class ReminderService {

    private final ReminderRepository reminderRepository;

    public ReminderService(ReminderRepository reminderRepository) {
        this.reminderRepository = reminderRepository;
    }

    @Transactional
    public Reminder createNote(String text) {
        Reminder note = new Reminder(text, null, "NONE");
        return reminderRepository.save(note);
    }

    @Transactional
    public Reminder createOneTimeReminder(String text, LocalTime time) {
        LocalDateTime remindAt = LocalDateTime.of(LocalDate.now(), time);
        // if time already passed today, schedule for tomorrow
        if (remindAt.isBefore(LocalDateTime.now())) {
            remindAt = remindAt.plusDays(1);
        }
        return reminderRepository.save(new Reminder(text, remindAt, "NONE"));
    }

    @Transactional
    public Reminder createDailyReminder(String text, LocalTime time) {
        LocalDateTime remindAt = LocalDateTime.of(LocalDate.now(), time);
        if (remindAt.isBefore(LocalDateTime.now())) {
            remindAt = remindAt.plusDays(1);
        }
        return reminderRepository.save(new Reminder(text, remindAt, "DAILY"));
    }

    @Transactional
    public Reminder createMonthlyReminder(String text, int dayOfMonth, LocalTime time) {
        LocalDate today = LocalDate.now();
        LocalDate targetDate = today.withDayOfMonth(Math.min(dayOfMonth, today.lengthOfMonth()));
        LocalDateTime remindAt = LocalDateTime.of(targetDate, time);

        if (remindAt.isBefore(LocalDateTime.now())) {
            LocalDate nextMonth = today.plusMonths(1);
            targetDate = nextMonth.withDayOfMonth(Math.min(dayOfMonth, nextMonth.lengthOfMonth()));
            remindAt = LocalDateTime.of(targetDate, time);
        }

        return reminderRepository.save(new Reminder(text, remindAt, "MONTHLY"));
    }

    public List<Reminder> getAllActive() {
        return reminderRepository.findByActiveTrueOrderByCreatedAtDesc();
    }

    public List<Reminder> getDueReminders() {
        return reminderRepository.findDueReminders(LocalDateTime.now());
    }

    @Transactional
    public Reminder createOneTimeReminder(String text, LocalDateTime remindAt) {
        return reminderRepository.save(new Reminder(text, remindAt, "NONE"));
    }

    @Transactional
    public void processTriggeredReminder(Reminder reminder) {
        String interval = reminder.getRepeatInterval();

        if ("DAILY".equalsIgnoreCase(interval)) {
            reminder.setRemindAt(reminder.getRemindAt().plusDays(1));
        } else if ("WEEKLY".equalsIgnoreCase(interval)) {
            reminder.setRemindAt(reminder.getRemindAt().plusWeeks(1));
        } else if ("MONTHLY".equalsIgnoreCase(interval)) {
            reminder.setRemindAt(reminder.getRemindAt().plusMonths(1));
        } else {
            reminder.setActive(false);
        }

        reminderRepository.save(reminder);
    }

    @Transactional
    public boolean deleteById(Long id) {
        return reminderRepository.findById(id).map(r -> {
            r.setActive(false);
            reminderRepository.save(r);
            return true;
        }).orElse(false);
    }

    @Transactional
    public Reminder createWeeklyReminder(String text, DayOfWeek dayOfWeek, LocalTime time) {
        LocalDate today = LocalDate.now();
        // find next occurrence of the day of week
        LocalDate targetDate = today.with(TemporalAdjusters.nextOrSame(dayOfWeek));
        LocalDateTime remindAt = LocalDateTime.of(targetDate, time);

        // if the target time today has already passed, schedule for next week
        if (remindAt.isBefore(LocalDateTime.now())) {
            remindAt = remindAt.plusWeeks(1);
        }

        return reminderRepository.save(new Reminder(text, remindAt, "WEEKLY"));
    }

    @Transactional
    public void snoozeReminder(Long id, LocalDateTime newRemindAt) {
        reminderRepository.findById(id).ifPresent(reminder -> {
            reminder.setRemindAt(newRemindAt);
            reminder.setActive(true);
            reminderRepository.save(reminder);
        });
    }
}