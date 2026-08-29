package com.tsimafei.nexus_core.reminder.service;

import com.tsimafei.nexus_core.reminder.domain.Reminder;
import com.tsimafei.nexus_core.reminder.repository.ReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReminderServiceTest {

    @Mock
    private ReminderRepository reminderRepository;

    private ReminderService reminderService;

    @BeforeEach
    void setUp() {
        reminderService = new ReminderService(reminderRepository);
    }

    @Test
    void createNote_shouldSaveNoteWithNoRemindAt() {
        when(reminderRepository.save(any(Reminder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Reminder note = reminderService.createNote("Buy milk");

        assertNotNull(note);
        assertEquals("Buy milk", note.getText());
        assertNull(note.getRemindAt());
        assertEquals("NONE", note.getRepeatInterval());
        assertTrue(note.isActive());
        verify(reminderRepository).save(any(Reminder.class));
    }

    @Test
    void createOneTimeReminder_shouldScheduleForTomorrow_ifTimeAlreadyPassed() {
        when(reminderRepository.save(any(Reminder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Set time 1 hour in the past
        LocalTime pastTime = LocalTime.now().minusHours(1);
        Reminder reminder = reminderService.createOneTimeReminder("Doctor visit", pastTime);

        assertNotNull(reminder.getRemindAt());
        assertTrue(reminder.getRemindAt().isAfter(LocalDateTime.now()));
        assertEquals("NONE", reminder.getRepeatInterval());
    }

    @Test
    void processTriggeredReminder_shouldDeactivate_whenNoRepeat() {
        Reminder reminder = new Reminder("Meeting", LocalDateTime.now(), "NONE");

        reminderService.processTriggeredReminder(reminder);

        assertFalse(reminder.isActive());
        verify(reminderRepository).save(reminder);
    }

    @Test
    void processTriggeredReminder_shouldShiftDaily_whenIntervalIsDaily() {
        LocalDateTime scheduledTime = LocalDateTime.of(2026, 8, 29, 10, 0);
        Reminder reminder = new Reminder("Standup", scheduledTime, "DAILY");

        reminderService.processTriggeredReminder(reminder);

        assertTrue(reminder.isActive());
        assertEquals(scheduledTime.plusDays(1), reminder.getRemindAt());
        verify(reminderRepository).save(reminder);
    }

    @Test
    void snoozeReminder_shouldUpdateRemindAtAndKeepActive() {
        Reminder existing = new Reminder("Task", LocalDateTime.now(), "NONE");
        when(reminderRepository.findById(1L)).thenReturn(Optional.of(existing));

        LocalDateTime newTime = LocalDateTime.now().plusMinutes(15);
        reminderService.snoozeReminder(1L, newTime);

        assertEquals(newTime, existing.getRemindAt());
        assertTrue(existing.isActive());
        verify(reminderRepository).save(existing);
    }
}