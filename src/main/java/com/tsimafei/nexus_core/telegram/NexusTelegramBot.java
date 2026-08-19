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

    // keeps track of current step for user input
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
            sendMessage(authorizedChatId, text, null, null);
            reminderService.processTriggeredReminder(reminder);
        }
    }

    @Override
    public void consume(Update update) {
        // handle inline button clicks
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

        // check if user is in an active input dialog
        if (userStates.containsKey(chatId)) {
            handleAwaitingInput(chatId, messageText);
            return;
        }

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
        } else if (text.equals("➕ Add Income")) {
            showAccountSelection(chatId, "INCOME");
        } else if (text.equals("➖ Add Expense")) {
            showAccountSelection(chatId, "EXPENSE");
        } else if (text.equals("📌 Add Note")) {
            userStates.put(chatId, "NOTE");
            sendMessage(chatId, "Type the text of your note:", null, null);
        } else if (text.equals("⏰ Add Reminder")) {
            userStates.put(chatId, "REMINDER");
            sendMessage(chatId, "Send reminder in format: `[HH:mm] [Text]`\nExample: `19:30 Call doctor`", null, null);
        } else {
            sendMessage(chatId, "Use the buttons below to navigate.", buildMainMenuKeyboard(), null);
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
            sendMessage(chatId, String.format("✅ Task `[%d]` completed!", taskId), null, null);
            sendActiveTasks(chatId);
        }
    }

    private void handleAwaitingInput(String chatId, String text) {
        String state = userStates.remove(chatId);

        try {
            if ("NOTE".equals(state)) {
                Reminder note = reminderService.createNote(text);
                sendMessage(chatId, String.format("📌 Note saved! `[%d]`", note.getId()), buildMainMenuKeyboard(), null);
            } else if ("REMINDER".equals(state)) {
                String[] parts = text.split(" ", 2);
                LocalTime time = LocalTime.parse(parts[0], TIME_FORMATTER);
                String task = parts[1];
                Reminder reminder = reminderService.createOneTimeReminder(task, time);
                sendMessage(chatId, String.format("⏰ Reminder set for *%s*! `[%d]`", reminder.getRemindAt().format(DATE_FORMATTER), reminder.getId()), buildMainMenuKeyboard(), null);
            } else if ("AMOUNT_INCOME".equals(state) || "AMOUNT_EXPENSE".equals(state)) {
                String accountName = selectedAccounts.remove(chatId);
                String type = "AMOUNT_INCOME".equals(state) ? "INCOME" : "EXPENSE";

                String[] parts = text.split(" ", 2);
                BigDecimal amount = new BigDecimal(parts[0]);
                String comment = parts.length > 1 ? parts[1] : "";

                financeService.addTransaction(accountName, amount, type, comment);
                String sign = "INCOME".equals(type) ? "➕" : "➖";
                sendMessage(chatId, String.format("%s *%.2f* recorded on *%s*!", sign, amount, accountName), buildMainMenuKeyboard(), null);
            }
        } catch (Exception e) {
            sendMessage(chatId, "⚠️ Invalid input format. Operation canceled.", buildMainMenuKeyboard(), null);
        }
    }

    private void showAccountSelection(String chatId, String operationType) {
        List<Account> accounts = financeService.getAllAccounts();
        if (accounts.isEmpty()) {
            sendMessage(chatId, "No accounts found.", buildMainMenuKeyboard(), null);
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

    private void sendActiveTasks(String chatId) {
        List<Reminder> tasks = reminderService.getAllActive();
        if (tasks.isEmpty()) {
            sendMessage(chatId, "No active tasks or reminders.", buildMainMenuKeyboard(), null);
            return;
        }

        StringBuilder sb = new StringBuilder("*Active Tasks & Reminders:*\n\n");
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (Reminder task : tasks) {
            if (task.getRemindAt() == null) {
                sb.append(String.format("📌 `[%d]` %s\n", task.getId(), task.getText()));
            } else {
                String repeat = "NONE".equalsIgnoreCase(task.getRepeatInterval()) ? "" : " [" + task.getRepeatInterval() + "]";
                sb.append(String.format("⏰ `[%d]` %s — *%s*%s\n",
                        task.getId(),
                        task.getText(),
                        task.getRemindAt().format(DATE_FORMATTER),
                        repeat));
            }

            InlineKeyboardButton doneBtn = InlineKeyboardButton.builder()
                    .text("✅ Done #" + task.getId())
                    .callbackData("DONE_TASK_" + task.getId())
                    .build();
            InlineKeyboardRow row = new InlineKeyboardRow();
            row.add(doneBtn);
            rows.add(row);
        }

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();
        sendMessage(chatId, sb.toString(), buildMainMenuKeyboard(), markup);
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
        sendMessage(chatId, sb.toString(), buildMainMenuKeyboard(), null);
    }

    private void sendHistory(String chatId) {
        List<Transaction> transactions = financeService.getRecentTransactions();

        if (transactions.isEmpty()) {
            sendMessage(chatId, "No transactions found yet.", buildMainMenuKeyboard(), null);
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

        sendMessage(chatId, sb.toString(), buildMainMenuKeyboard(), null);
    }

    private void sendHelp(String chatId) {
        String helpText = """
                *Nexus Core Bot*
                
                Use the bottom menu for quick actions:
                • *📊 Balance* - View account balances
                • *📜 History* - View recent operations
                • *➕ Add Income* / *➖ Add Expense* - Log transactions via buttons
                • *📝 Tasks* - View and complete tasks
                • *📌 Add Note* / *⏰ Add Reminder* - Fast inputs
                """;
        sendMessage(chatId, helpText, buildMainMenuKeyboard(), null);
    }

    private ReplyKeyboardMarkup buildMainMenuKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📊 Balance");
        row1.add("📜 History");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("➕ Add Income");
        row2.add("➖ Add Expense");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("📝 Tasks");
        row3.add("📌 Add Note");
        row3.add("⏰ Add Reminder");

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
}