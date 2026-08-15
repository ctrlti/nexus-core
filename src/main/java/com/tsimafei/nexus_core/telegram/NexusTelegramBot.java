package com.tsimafei.nexus_core.telegram;

import com.tsimafei.nexus_core.finance.domain.Account;
import com.tsimafei.nexus_core.finance.domain.Transaction;
import com.tsimafei.nexus_core.finance.service.FinanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class NexusTelegramBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(NexusTelegramBot.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final TelegramClient telegramClient;
    private final FinanceService financeService;
    private final String botToken;
    private final String authorizedChatId;

    public NexusTelegramBot(
            @Value("${nexus.telegram.bot-token}") String botToken,
            @Value("${nexus.telegram.authorized-chat-id}") String authorizedChatId,
            FinanceService financeService) {
        this.botToken = botToken;
        this.authorizedChatId = authorizedChatId;
        this.financeService = financeService;
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

    private void handleTransfer(String chatId, String text) {
        try {
            // Формат: /transfer USD Card -> USD Cash 50 ATM withdrawal
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

    private void handleCommand(String chatId, String text) {
        if (text.startsWith("/start") || text.startsWith("/help") || text.equals("ℹ️ Help")) {
            sendHelp(chatId);
        } else if (text.startsWith("/balance") || text.equals("📊 Balance")) {
            sendBalance(chatId);
        } else if (text.startsWith("/history") || text.equals("📜 History")) {
            sendHistory(chatId);
        } else if (text.startsWith("/add ")) {
            handleAddTransaction(chatId, text);
        } else if (text.startsWith("/transfer ")) {
            handleTransfer(chatId, text);
        } else {
            sendMessage(chatId, "Unknown command. Use buttons below or /help.", buildMainMenuKeyboard());
        }
    }

    private void sendHelp(String chatId) {
        String helpText = """
                *Nexus Core Bot*
                
                Available commands:
                • Click *📊 Balance* or send `/balance`
                • Click *📜 History* or send `/history`
                • `/add [Account] [Amount] [TYPE] [Comment]` - Add transaction
                • `/transfer [From Account] -> [To Account] [Amount] [Comment]` - Transfer funds
                
                Examples:
                `/add USD Cash 100 INCOME Salary`
                `/add EUR Card 15 EXPENSE Bus ticket`
                `/transfer USD Card -> USD Cash 50 ATM withdrawal`
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

    private ReplyKeyboardMarkup buildMainMenuKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📊 Balance");
        row1.add("📜 History");

        KeyboardRow row2 = new KeyboardRow();
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