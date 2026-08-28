package com.tsimafei.nexus_core.telegram;

import com.tsimafei.nexus_core.finance.domain.Account;
import com.tsimafei.nexus_core.finance.domain.Transaction;
import com.tsimafei.nexus_core.finance.service.FinanceService;
import com.tsimafei.nexus_core.reminder.domain.Reminder;
import com.tsimafei.nexus_core.reminder.service.ReminderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Component
public class NexusTelegramBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(NexusTelegramBot.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final TelegramClient telegramClient;
    private final FinanceService financeService;
    private final ReminderService reminderService;
    private final String botToken;
    private final String authorizedChatId;

    private final Map<String, String> userStates = new ConcurrentHashMap<>();
    private final Map<String, String> selectedAccounts = new ConcurrentHashMap<>();

    public NexusTelegramBot(
            @Value("${nexus.telegram.bot-token}") String botToken,
            @Value("${nexus.telegram.authorized-chat-id}") String authorizedChatId,
            FinanceService financeService,
            ReminderService reminderService) {
        this.botToken = botToken;
        this.authorizedChatId = authorizedChatId;
        this.financeService = financeService;
        this.reminderService = reminderService;
        this.telegramClient = new OkHttpTelegramClient(botToken);
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Scheduled(fixedRate = 30000)
    public void checkAndSendReminders() {
        List<Reminder> dueReminders = reminderService.getDueReminders();
        for (Reminder reminder : dueReminders) {
            String text = String.format("🔔 *Reminder:*\n\n%s", reminder.getText());
            InlineKeyboardMarkup snoozeMarkup = buildSnoozeKeyboard(reminder.getId());
            sendMessage(authorizedChatId, text, null, snoozeMarkup);
            reminderService.processTriggeredReminder(reminder);
        }
    }

    @Override
    public void consume(Update update) {
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
            return;
        }

        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String chatId = update.getMessage().getChatId().toString();

        if (!chatId.equals(authorizedChatId)) {
            sendMessage(chatId, "Access denied.", null, null);
            return;
        }

        String messageText = update.getMessage().getText().trim();

        if (userStates.containsKey(chatId)) {
            handleAwaitingInput(chatId, messageText);
            return;
        }

        handleCommand(chatId, messageText);
    }
    private void handleDeleteReminder(String chatId, String text) {
        try {
            String[] parts = text.split(" ");
            Long id = Long.parseLong(parts[1].trim());
            boolean deleted = reminderService.deleteById(id);
            if (deleted) {
                sendMessage(chatId, String.format("Task `[%d]` removed / completed!", id), buildTasksKeyboard(), null);
            } else {
                sendMessage(chatId, "Task not found with ID: " + id, buildTasksKeyboard(), null);
            }
        } catch (Exception e) {
            sendMessage(chatId, "Usage: `/done [ID]` or `/delete [ID]`", buildTasksKeyboard(), null);
        }
    }

    private void handleRepeatCommand(String chatId, String text) {
        try {
            String[] parts = text.split(" ");
            String frequency = parts[1].toLowerCase();

            if ("weekly".equals(frequency)) {
                // /repeat weekly monday 10:00 Team sync
                java.time.DayOfWeek day = java.time.DayOfWeek.valueOf(parts[2].toUpperCase());
                LocalTime time = LocalTime.parse(parts[3], TIME_FORMATTER);
                String taskText = text.substring(text.indexOf(parts[3]) + parts[3].length()).trim();

                Reminder reminder = reminderService.createWeeklyReminder(taskText, day, time);
                sendMessage(chatId, String.format("🔄 Weekly reminder set for *%s* at *%s*! `[%d]`",
                        day, reminder.getRemindAt().format(TIME_FORMATTER), reminder.getId()), buildTasksKeyboard(), null);
            } else if ("daily".equals(frequency)) {
                LocalTime time = LocalTime.parse(parts[2], TIME_FORMATTER);
                String taskText = text.substring(text.indexOf(parts[2]) + parts[2].length()).trim();
                Reminder reminder = reminderService.createDailyReminder(taskText, time);
                sendMessage(chatId, String.format("🔄 Daily reminder set for *%s*! `[%d]`",
                        reminder.getRemindAt().format(TIME_FORMATTER), reminder.getId()), buildTasksKeyboard(), null);
            } else if ("monthly".equals(frequency)) {
                int day = Integer.parseInt(parts[2]);
                LocalTime time = LocalTime.parse(parts[3], TIME_FORMATTER);
                String taskText = text.substring(text.indexOf(parts[3]) + parts[3].length()).trim();
                Reminder reminder = reminderService.createMonthlyReminder(taskText, day, time);
                sendMessage(chatId, String.format("🔄 Monthly reminder set for day *%d* at *%s*! `[%d]`",
                        day, reminder.getRemindAt().format(TIME_FORMATTER), reminder.getId()), buildTasksKeyboard(), null);
            }
        } catch (Exception e) {
            sendMessage(chatId, "Format error. Examples:\n`/repeat weekly monday 10:00 Team meeting`\n`/repeat daily 09:00 Workout`\n`/repeat monthly 15 12:00 Rent`", buildTasksKeyboard(), null);
        }
    }

    private void handleCommand(String chatId, String text) {
        switch (text) {
            case "/start", "/help", "⬅️ Main Menu" ->
                    sendMessage(chatId, "Select category:", buildMainMenuKeyboard(), null);

            // --- FINANCE NAVIGATION ---
            case "💰 Finance" ->
                    sendMessage(chatId, "*Finance Menu*", buildFinanceKeyboard(), null);
            case "📊 Balance", "/balance" ->
                    sendBalance(chatId);
            case "📜 History", "/history" ->
                    sendHistory(chatId);
            case "➕ Add Income" ->
                    showAccountSelection(chatId, "INCOME");
            case "➖ Add Expense" ->
                    showAccountSelection(chatId, "EXPENSE");

            // --- TASKS NAVIGATION ---
            case "📝 Tasks & Notes" ->
                    sendMessage(chatId, "*Tasks & Notes Menu*", buildTasksKeyboard(), null);
            case "📋 List Tasks", "/tasks" ->
                    sendActiveTasks(chatId);
            case "📌 Add Note" -> {
                userStates.put(chatId, "NOTE");
                sendMessage(chatId, "Enter your note text:", null, null);
            }
            case "⏰ One-time Reminder" -> {
                userStates.put(chatId, "REMINDER");
                sendMessage(chatId, "Format:\n`[HH:mm] [Text]` (today/tomorrow)\n`[dd.MM] [HH:mm] [Text]` (specific date)\n\nExamples:\n`19:30 Call doctor`\n`25.08 14:00 Visit dentist`", null, null);
            }
            case "🔄 Weekly Reminder" -> {
                userStates.put(chatId, "WEEKLY_REMINDER");
                sendMessage(chatId, "Format: `[Day] [HH:mm] [Text]`\nExample: `monday 10:00 Team sync`", null, null);
            }
            case "🔄 Monthly Reminder" -> {
                userStates.put(chatId, "MONTHLY_REMINDER");
                sendMessage(chatId, "Format: `[Day] [HH:mm] [Text]`\nExample: `15 12:00 Pay rent`", null, null);
            }

            // --- COMMAND PREFIXES ---
            case String s when s.startsWith("/repeat ") ->
                    handleRepeatCommand(chatId, text);

            case String s when s.startsWith("/done ") || s.startsWith("/delete ") ->
                    handleDeleteReminder(chatId, text);

            default ->
                    sendMessage(chatId, "Use buttons below to navigate.", buildMainMenuKeyboard(), null);
        }
    }

    private void handleCallbackQuery(CallbackQuery query) {
        String chatId = query.getMessage().getChatId().toString();
        String data = query.getData();

        if (!chatId.equals(authorizedChatId)) return;

        if (data.startsWith("ACC_INC_")) {
            String accountName = data.replace("ACC_INC_", "");
            selectedAccounts.put(chatId, accountName);
            userStates.put(chatId, "AMOUNT_INCOME");
            sendMessage(chatId, String.format("Account: *%s*\nEnter amount and comment:\nExample: `100 Salary`", accountName), null, null);
        } else if (data.startsWith("ACC_EXP_")) {
            String accountName = data.replace("ACC_EXP_", "");
            selectedAccounts.put(chatId, accountName);
            userStates.put(chatId, "AMOUNT_EXPENSE");
            sendMessage(chatId, String.format("Account: *%s*\nEnter amount and comment:\nExample: `25 Coffee and lunch`", accountName), null, null);
        } else if (data.startsWith("DONE_TASK_")) {
            Long taskId = Long.parseLong(data.replace("DONE_TASK_", ""));
            reminderService.deleteById(taskId);
            sendMessage(chatId, "✅ Task completed!", null, null);
            sendActiveTasks(chatId);
        } else if (data.startsWith("SNOOZE_")) {
            handleSnoozeCallback(chatId, data);
        }
    }

    private LocalDateTime parseDateTime(String input) {
        // format: 25.08 14:00 OR 25.08.2026 14:00 OR 14:00
        String[] parts = input.trim().split(" ");

        if (parts.length == 1) {
            // only time provided -> schedule for today or tomorrow
            LocalTime time = LocalTime.parse(parts[0], TIME_FORMATTER);
            LocalDateTime target = LocalDateTime.of(LocalDate.now(), time);
            return target.isBefore(LocalDateTime.now()) ? target.plusDays(1) : target;
        }

        // date + time provided (e.g. "25.08 14:00")
        String datePart = parts[0];
        String timePart = parts[1];
        LocalTime time = LocalTime.parse(timePart, TIME_FORMATTER);

        LocalDate date;
        if (datePart.length() == 5) { // dd.MM
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM");
            java.time.MonthDay monthDay = java.time.MonthDay.parse(datePart, formatter);
            date = monthDay.atYear(LocalDate.now().getYear());
            if (date.isBefore(LocalDate.now())) {
                date = date.plusYears(1);
            }
        } else { // dd.MM.yyyy
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
            date = LocalDate.parse(datePart, formatter);
        }

        return LocalDateTime.of(date, time);
    }

    private void handleAwaitingInput(String chatId, String text) {
        String state = userStates.remove(chatId);

        try {
            if ("NOTE".equals(state)) {
                reminderService.createNote(text);
                sendMessage(chatId, "📌 Note saved!", buildTasksKeyboard(), null);
            } else if ("REMINDER".equals(state)) {
                String[] parts = text.split(" ");
                LocalDateTime remindAt;
                String task;

                if (parts[0].contains(".")) {
                    // Date + time provided: "24.08 12:20 Task"
                    remindAt = parseDateTime(parts[0] + " " + parts[1]);
                    task = text.substring(parts[0].length() + parts[1].length() + 2).trim();
                } else {
                    // Time only provided: "19:30 Task"
                    remindAt = parseDateTime(parts[0]);
                    task = text.substring(parts[0].length()).trim();
                }

                Reminder reminder = reminderService.createOneTimeReminder(task, remindAt);
                sendMessage(chatId, String.format("⏰ Reminder set for *%s*!",
                        reminder.getRemindAt().format(DATE_FORMATTER)), buildTasksKeyboard(), null);
            } else if ("MONTHLY_REMINDER".equals(state)) {
                String[] parts = text.split(" ", 3);
                int day = Integer.parseInt(parts[0]);
                LocalTime time = LocalTime.parse(parts[1], TIME_FORMATTER);
                String task = parts[2];
                Reminder reminder = reminderService.createMonthlyReminder(task, day, time);
                sendMessage(chatId, String.format("🔄 Monthly task set for day *%d* at *%s*!",
                        day, reminder.getRemindAt().format(TIME_FORMATTER)), buildTasksKeyboard(), null);
            } else if ("WEEKLY_REMINDER".equals(state)) {
                String[] parts = text.split(" ", 3);
                java.time.DayOfWeek day = java.time.DayOfWeek.valueOf(parts[0].toUpperCase());
                LocalTime time = LocalTime.parse(parts[1], TIME_FORMATTER);
                String task = parts[2];
                Reminder reminder = reminderService.createWeeklyReminder(task, day, time);
                sendMessage(chatId, String.format("🔄 Weekly reminder set for *%s* at *%s*!",
                        day, reminder.getRemindAt().format(TIME_FORMATTER)), buildTasksKeyboard(), null);
            } else if ("AMOUNT_INCOME".equals(state) || "AMOUNT_EXPENSE".equals(state)) {
                String accountName = selectedAccounts.remove(chatId);
                String type = "AMOUNT_INCOME".equals(state) ? "INCOME" : "EXPENSE";

                String[] parts = text.split(" ", 2);
                BigDecimal amount = new BigDecimal(parts[0]);
                String comment = parts.length > 1 ? parts[1] : "";

                financeService.addTransaction(accountName, amount, type, comment);
                String sign = "INCOME".equals(type) ? "➕" : "➖";
                sendMessage(chatId, String.format("%s *%.2f* recorded on *%s*!", sign, amount, accountName), buildFinanceKeyboard(), null);
            }

        } catch (Exception e) {
            sendMessage(chatId, "⚠️ Invalid format. Operation canceled.", buildMainMenuKeyboard(), null);
        }
    }

    private void showAccountSelection(String chatId, String operationType) {
        List<Account> accounts = financeService.getAllAccounts();
        if (accounts.isEmpty()) {
            sendMessage(chatId, "No accounts found.", buildFinanceKeyboard(), null);
            return;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        String prefix = "INCOME".equals(operationType) ? "ACC_INC_" : "ACC_EXP_";

        for (Account acc : accounts) {
            InlineKeyboardButton btn = InlineKeyboardButton.builder()
                    .text(String.format("%s (%s)", acc.getName(), acc.getCurrency()))
                    .callbackData(prefix + acc.getName())
                    .build();
            InlineKeyboardRow row = new InlineKeyboardRow();
            row.add(btn);
            rows.add(row);
        }

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();
        sendMessage(chatId, "Select account for *" + operationType + "*:", null, markup);
    }

    private void sendBalance(String chatId) {
        List<Account> accounts = financeService.getAllAccounts();
        BigDecimal totalPln = financeService.getTotalInPln();

        StringBuilder sb = new StringBuilder("*Accounts Overview:*\n\n");
        for (Account account : accounts) {
            sb.append(String.format("• %s: *%.2f %s*\n",
                    account.getName(), account.getBalance(), account.getCurrency()));
        }

        sb.append(String.format("\n*Total in PLN:* ~%.2f PLN", totalPln));
        sendMessage(chatId, sb.toString(), buildFinanceKeyboard(), null);
    }

    private void sendHistory(String chatId) {
        List<Transaction> transactions = financeService.getRecentTransactions();

        if (transactions.isEmpty()) {
            sendMessage(chatId, "No transactions found yet.", buildFinanceKeyboard(), null);
            return;
        }

        StringBuilder sb = new StringBuilder("*Recent Transactions (Last 10):*\n\n");
        for (Transaction tx : transactions) {
            String sign = "INCOME".equalsIgnoreCase(tx.getType()) ? "➕" : "➖";
            String date = tx.getCreatedAt() != null ? tx.getCreatedAt().format(DATE_FORMATTER) : "recently";
            String comment = (tx.getComment() != null && !tx.getComment().isBlank()) ? (" — " + tx.getComment()) : "";

            sb.append(String.format("%s *%.2f %s* (%s)%s\n_%s [%s]_\n\n",
                    sign,
                    tx.getAmount(),
                    tx.getAccount().getCurrency(),
                    tx.getAccount().getName(),
                    comment,
                    date,
                    tx.getType()));
        }

        sendMessage(chatId, sb.toString(), buildFinanceKeyboard(), null);
    }

    private void sendActiveTasks(String chatId) {
        List<Reminder> tasks = reminderService.getAllActive();
        if (tasks.isEmpty()) {
            sendMessage(chatId, "No active tasks or reminders.", buildTasksKeyboard(), null);
            return;
        }

        StringBuilder sb = new StringBuilder("*Active Tasks & Reminders:*\n\n");
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (int i = 0; i < tasks.size(); i++) {
            Reminder task = tasks.get(i);
            int displayIndex = i + 1;

            if (task.getRemindAt() == null) {
                sb.append(String.format("%d. 📌 %s\n", displayIndex, task.getText()));
            } else {
                String repeat = "NONE".equalsIgnoreCase(task.getRepeatInterval()) ? "" : " [" + task.getRepeatInterval() + "]";
                sb.append(String.format("%d. ⏰ %s — *%s*%s\n",
                        displayIndex,
                        task.getText(),
                        task.getRemindAt().format(DATE_FORMATTER),
                        repeat));
            }

            InlineKeyboardButton doneBtn = InlineKeyboardButton.builder()
                    .text(String.format("✅ Done #%d", displayIndex))
                    .callbackData("DONE_TASK_" + task.getId())
                    .build();
            InlineKeyboardRow row = new InlineKeyboardRow();
            row.add(doneBtn);
            rows.add(row);
        }

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();
        sendMessage(chatId, sb.toString(), null, markup);
    }

    // --- KEYBOARDS ---

    private ReplyKeyboardMarkup buildMainMenuKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add("💰 Finance");
        row1.add("📝 Tasks & Notes");

        List<KeyboardRow> keyboard = new ArrayList<>();
        keyboard.add(row1);

        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboard)
                .resizeKeyboard(true)
                .build();
    }

    private ReplyKeyboardMarkup buildFinanceKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📊 Balance");
        row1.add("📜 History");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("➕ Add Income");
        row2.add("➖ Add Expense");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("⬅️ Main Menu");

        List<KeyboardRow> keyboard = new ArrayList<>();
        keyboard.add(row1);
        keyboard.add(row2);
        keyboard.add(row3);

        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboard)
                .resizeKeyboard(true)
                .build();
    }

    private ReplyKeyboardMarkup buildTasksKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📋 List Tasks");
        row1.add("📌 Add Note");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("⏰ One-time Reminder");
        row2.add("🔄 Weekly Reminder");
        row2.add("🔄 Monthly Reminder");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("⬅️ Main Menu");

        List<KeyboardRow> keyboard = new ArrayList<>();
        keyboard.add(row1);
        keyboard.add(row2);
        keyboard.add(row3);

        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboard)
                .resizeKeyboard(true)
                .build();
    }

    private void sendMessage(String chatId, String text, ReplyKeyboardMarkup replyMarkup, InlineKeyboardMarkup inlineMarkup) {
        SendMessage.SendMessageBuilder builder = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("Markdown");

        if (replyMarkup != null) {
            builder.replyMarkup(replyMarkup);
        } else if (inlineMarkup != null) {
            builder.replyMarkup(inlineMarkup);
        }

        try {
            telegramClient.execute(builder.build());
        } catch (TelegramApiException e) {
            log.error("Failed to send telegram message", e);
        }
    }

    private void handleSnoozeCallback(String chatId, String data) {
        // Format: SNOOZE_{MINUTES}_{TASK_ID} or SNOOZE_TOMORROW_{TASK_ID}
        String[] parts = data.split("_");
        Long taskId = Long.parseLong(parts[2]);
        LocalDateTime newTime;
        String readableTime;

        if ("TOMORROW".equals(parts[1])) {
            newTime = LocalDate.now().plusDays(1).atTime(9, 0);
            readableTime = "tomorrow at 09:00";
        } else {
            int minutes = Integer.parseInt(parts[1]);
            newTime = LocalDateTime.now().plusMinutes(minutes);
            readableTime = String.format("+%dm (%s)", minutes, newTime.format(TIME_FORMATTER));
        }

        reminderService.snoozeReminder(taskId, newTime);
        sendMessage(chatId, String.format("💤 Reminder snoozed until *%s*.", readableTime), null, null);
    }

    private InlineKeyboardMarkup buildSnoozeKeyboard(Long taskId) {
        InlineKeyboardButton btn15m = InlineKeyboardButton.builder()
                .text("⏰ +15m")
                .callbackData(String.format("SNOOZE_15_%d", taskId))
                .build();

        InlineKeyboardButton btn1h = InlineKeyboardButton.builder()
                .text("⏰ +1h")
                .callbackData(String.format("SNOOZE_60_%d", taskId))
                .build();

        InlineKeyboardButton btn3h = InlineKeyboardButton.builder()
                .text("⏰ +3h")
                .callbackData(String.format("SNOOZE_180_%d", taskId))
                .build();

        InlineKeyboardButton btnTomorrow = InlineKeyboardButton.builder()
                .text("📅 Tomorrow 09:00")
                .callbackData(String.format("SNOOZE_TOMORROW_%d", taskId))
                .build();

        InlineKeyboardButton btnDone = InlineKeyboardButton.builder()
                .text("✅ Done")
                .callbackData("DONE_TASK_" + taskId)
                .build();

        InlineKeyboardRow row1 = new InlineKeyboardRow();
        row1.add(btn15m);
        row1.add(btn1h);
        row1.add(btn3h);

        InlineKeyboardRow row2 = new InlineKeyboardRow();
        row2.add(btnTomorrow);
        row2.add(btnDone);

        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(row1);
        rows.add(row2);

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}