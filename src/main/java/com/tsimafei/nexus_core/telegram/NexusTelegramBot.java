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
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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

    // background task runs every 30 seconds
    @Scheduled(fixedRate = 30000)
    public void checkAndSendReminders() {
        List<Reminder> dueReminders = reminderService.getDueReminders();
        for (Reminder reminder : dueReminders) {
            String text = String.format("🔔 *Reminder:*\n\n%s", reminder.getText());
            sendMessage(authorizedChatId, text, null);
            reminderService.processTriggeredReminder(reminder);
        }
    }

    @Override
    public void consume(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String chatId = update.getMessage().getChatId().toString();

        if (!chatId.equals(authorizedChatId)) {
            sendMessage(chatId, "Access denied.", null);
            return;
        }

        String messageText = update.getMessage().getText().trim();
        handleCommand(chatId, messageText);
    }

    private void handleCommand(String chatId, String text) {
        if (text.startsWith("/start") || text.startsWith("/help") || text.equals("ℹ️ Help")) {
            sendHelp(chatId);
        } else if (text.startsWith("/balance") || text.equals("📊 Balance")) {
            sendBalance(chatId);
        } else if (text.startsWith("/history") || text.equals("📜 History")) {
            sendHistory(chatId);
        } else if (text.startsWith("/tasks") || text.equals("📝 Tasks")) {
            sendActiveTasks(chatId);
        } else if (text.startsWith("/add ")) {
            handleAddTransaction(chatId, text);
        } else if (text.startsWith("/transfer ")) {
            handleTransfer(chatId, text);
        } else if (text.startsWith("/newaccount ")) {
            handleCreateAccount(chatId, text);
        } else if (text.startsWith("/note ")) {
            handleCreateNote(chatId, text);
        } else if (text.startsWith("/remind ")) {
            handleCreateReminder(chatId, text);
        } else if (text.startsWith("/repeat ")) {
            handleCreateRepeatingReminder(chatId, text);
        } else if (text.startsWith("/delete ") || text.startsWith("/done ")) {
            handleDeleteReminder(chatId, text);
        } else {
            sendMessage(chatId, "Unknown command. Use buttons below or /help.", buildMainMenuKeyboard());
        }
    }

    private void sendHelp(String chatId) {
        String helpText = """
                *Nexus Core Bot*
                
                *Finance Commands:*
                • `/balance` - View total balance and all accounts
                • `/history` - View last 10 transactions
                • `/add [Account] [Amount] [TYPE] [Comment]` - Add transaction
                • `/transfer [From] -> [To] [Amount] [Comment]` - Transfer funds
                • `/newaccount [Name] [Currency]` - Create a new account
                
                *Notes & Reminders:*
                • `/tasks` - View active notes & reminders
                • `/note [Text]` - Save quick note
                • `/remind [HH:mm] [Text]` - One-time reminder
                • `/repeat daily [HH:mm] [Text]` - Daily recurring reminder
                • `/repeat monthly [Day] [HH:mm] [Text]` - Monthly reminder
                • `/done [ID]` or `/delete [ID]` - Complete or remove task
                """;
        sendMessage(chatId, helpText, buildMainMenuKeyboard());
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
        sendMessage(chatId, sb.toString(), buildMainMenuKeyboard());
    }

    private void sendHistory(String chatId) {
        List<Transaction> transactions = financeService.getRecentTransactions();

        if (transactions.isEmpty()) {
            sendMessage(chatId, "No transactions found yet.", buildMainMenuKeyboard());
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

        sendMessage(chatId, sb.toString(), buildMainMenuKeyboard());
    }

    private void sendActiveTasks(String chatId) {
        List<Reminder> tasks = reminderService.getAllActive();
        if (tasks.isEmpty()) {
            sendMessage(chatId, "No active tasks or reminders.", buildMainMenuKeyboard());
            return;
        }

        StringBuilder sb = new StringBuilder("*Active Tasks & Reminders:*\n\n");
        for (Reminder task : tasks) {
            if (task.getRemindAt() == null) {
                sb.append(String.format("📌 `[%d]` %s _(Note)_\n", task.getId(), task.getText()));
            } else {
                String repeat = "NONE".equalsIgnoreCase(task.getRepeatInterval()) ? "" : " [" + task.getRepeatInterval() + "]";
                sb.append(String.format("⏰ `[%d]` %s — *%s*%s\n",
                        task.getId(),
                        task.getText(),
                        task.getRemindAt().format(DATE_FORMATTER),
                        repeat));
            }
        }
        sb.append("\nUse `/done [ID]` to complete/delete a task.");
        sendMessage(chatId, sb.toString(), buildMainMenuKeyboard());
    }

    private void handleCreateNote(String chatId, String text) {
        String noteContent = text.substring(6).trim();
        if (noteContent.isBlank()) {
            sendMessage(chatId, "Usage: `/note [Text]`", buildMainMenuKeyboard());
            return;
        }
        Reminder note = reminderService.createNote(noteContent);
        sendMessage(chatId, String.format("📌 Note saved! `[%d]`", note.getId()), buildMainMenuKeyboard());
    }

    private void handleCreateReminder(String chatId, String text) {
        try {
            // Format: /remind 19:30 Call friend
            String[] parts = text.split(" ", 3);
            LocalTime time = LocalTime.parse(parts[1], TIME_FORMATTER);
            String reminderText = parts[2].trim();

            Reminder reminder = reminderService.createOneTimeReminder(reminderText, time);
            sendMessage(chatId, String.format("⏰ Reminder set for *%s*! `[%d]`",
                    reminder.getRemindAt().format(DATE_FORMATTER), reminder.getId()), buildMainMenuKeyboard());
        } catch (Exception e) {
            sendMessage(chatId, "Error. Format: `/remind 19:30 Call friend`", buildMainMenuKeyboard());
        }
    }

    private void handleCreateRepeatingReminder(String chatId, String text) {
        try {
            // Format: /repeat daily 09:00 Workout OR /repeat monthly 1 12:00 Pay rent
            String[] parts = text.split(" ");
            String frequency = parts[1].toLowerCase();

            if ("daily".equals(frequency)) {
                LocalTime time = LocalTime.parse(parts[2], TIME_FORMATTER);
                String taskText = text.substring(text.indexOf(parts[2]) + parts[2].length()).trim();
                Reminder reminder = reminderService.createDailyReminder(taskText, time);
                sendMessage(chatId, String.format("🔄 Daily reminder set for *%s*! `[%d]`",
                        reminder.getRemindAt().format(TIME_FORMATTER), reminder.getId()), buildMainMenuKeyboard());
            } else if ("monthly".equals(frequency)) {
                int day = Integer.parseInt(parts[2]);
                LocalTime time = LocalTime.parse(parts[3], TIME_FORMATTER);
                String taskText = text.substring(text.indexOf(parts[3]) + parts[3].length()).trim();
                Reminder reminder = reminderService.createMonthlyReminder(taskText, day, time);
                sendMessage(chatId, String.format("🔄 Monthly reminder set for day *%d* at *%s*! `[%d]`",
                        day, reminder.getRemindAt().format(TIME_FORMATTER), reminder.getId()), buildMainMenuKeyboard());
            } else {
                sendMessage(chatId, "Unknown interval. Use `daily` or `monthly`.", buildMainMenuKeyboard());
            }
        } catch (Exception e) {
            sendMessage(chatId, "Error. Format:\n`/repeat daily 09:00 Workout`\n`/repeat monthly 1 12:00 Pay rent`", buildMainMenuKeyboard());
        }
    }

    private void handleDeleteReminder(String chatId, String text) {
        try {
            String[] parts = text.split(" ");
            Long id = Long.parseLong(parts[1].trim());
            boolean deleted = reminderService.deleteById(id);
            if (deleted) {
                sendMessage(chatId, String.format("Task `[%d]` removed / completed!", id), buildMainMenuKeyboard());
            } else {
                sendMessage(chatId, "Task not found with ID: " + id, buildMainMenuKeyboard());
            }
        } catch (Exception e) {
            sendMessage(chatId, "Usage: `/done [ID]` or `/delete [ID]`", buildMainMenuKeyboard());
        }
    }

    private void handleAddTransaction(String chatId, String text) {
        try {
            String[] parts = text.split(" ", 5);
            if (parts.length < 5) {
                sendMessage(chatId, "Usage: `/add [Account Name] [Amount] [INCOME/EXPENSE] [Comment]`", buildMainMenuKeyboard());
                return;
            }

            String accountName = parts[1] + " " + parts[2];
            BigDecimal amount = new BigDecimal(parts[3]);
            String type = parts[4].split(" ")[0];
            String comment = parts[4].substring(type.length()).trim();

            financeService.addTransaction(accountName, amount, type, comment);
            sendMessage(chatId, String.format("Transaction added successfully to *%s*!", accountName), buildMainMenuKeyboard());
        } catch (Exception e) {
            sendMessage(chatId, "Error adding transaction. Check format:\n`/add USD Cash 100 INCOME Salary`", buildMainMenuKeyboard());
        }
    }

    private void handleTransfer(String chatId, String text) {
        try {
            String payload = text.substring(10).trim();
            String[] parts = payload.split("->");
            if (parts.length < 2) {
                sendMessage(chatId, "Usage: `/transfer [From Account] -> [To Account] [Amount] [Comment]`", buildMainMenuKeyboard());
                return;
            }

            String fromAccount = parts[0].trim();
            String[] restParts = parts[1].trim().split(" ", 3);

            String toAccount = restParts[0] + " " + restParts[1];
            String[] amountAndComment = restParts[2].split(" ", 2);
            BigDecimal amount = new BigDecimal(amountAndComment[0]);
            String comment = amountAndComment.length > 1 ? amountAndComment[1] : "Transfer";

            financeService.transfer(fromAccount, toAccount, amount, comment);
            sendMessage(chatId, String.format("Transferred *%.2f* from *%s* to *%s* successfully!", amount, fromAccount, toAccount), buildMainMenuKeyboard());
        } catch (IllegalStateException e) {
            sendMessage(chatId, "Transfer error: " + e.getMessage(), buildMainMenuKeyboard());
        } catch (Exception e) {
            sendMessage(chatId, "Error processing transfer. Check format:\n`/transfer USD Card -> USD Cash 50 ATM withdrawal`", buildMainMenuKeyboard());
        }
    }

    private void handleCreateAccount(String chatId, String text) {
        try {
            String[] parts = text.split(" ");
            if (parts.length < 3) {
                sendMessage(chatId, "Usage: `/newaccount [Account Name] [Currency]`\nExample: `/newaccount Revolut EUR`", buildMainMenuKeyboard());
                return;
            }

            String currency = parts[parts.length - 1].trim().toUpperCase();
            String name = text.substring(12, text.lastIndexOf(parts[parts.length - 1])).trim();

            financeService.createAccount(name, currency);
            sendMessage(chatId, String.format("Account *%s* (%s) created successfully with balance *0.00*!", name, currency), buildMainMenuKeyboard());
        } catch (IllegalArgumentException e) {
            sendMessage(chatId, "Error: " + e.getMessage(), buildMainMenuKeyboard());
        } catch (Exception e) {
            sendMessage(chatId, "Error creating account. Check format:\n`/newaccount Revolut EUR`", buildMainMenuKeyboard());
        }
    }

    private ReplyKeyboardMarkup buildMainMenuKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📊 Balance");
        row1.add("📜 History");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("📝 Tasks");
        row2.add("ℹ️ Help");

        List<KeyboardRow> keyboard = new ArrayList<>();
        keyboard.add(row1);
        keyboard.add(row2);

        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboard)
                .resizeKeyboard(true)
                .build();
    }

    private void sendMessage(String chatId, String text, ReplyKeyboardMarkup keyboardMarkup) {
        SendMessage.SendMessageBuilder builder = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("Markdown");

        if (keyboardMarkup != null) {
            builder.replyMarkup(keyboardMarkup);
        }

        try {
            telegramClient.execute(builder.build());
        } catch (TelegramApiException e) {
            log.error("Failed to send telegram message", e);
        }
    }
}