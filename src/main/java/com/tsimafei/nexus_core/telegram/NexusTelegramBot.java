package com.tsimafei.nexus_core.telegram;

import com.tsimafei.nexus_core.finance.domain.Account;
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
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.util.List;

@Component
public class NexusTelegramBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(NexusTelegramBot.class);

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
            sendMessage(chatId, "Access denied.");
            return;
        }

        String messageText = update.getMessage().getText().trim();
        handleCommand(chatId, messageText);
    }

    private void handleCommand(String chatId, String text) {
        if (text.startsWith("/start") || text.startsWith("/help")) {
            sendHelp(chatId);
        } else if (text.startsWith("/balance")) {
            sendBalance(chatId);
        } else if (text.startsWith("/add ")) {
            handleAddTransaction(chatId, text);
        } else {
            sendMessage(chatId, "Unknown command. Use /help for available commands.");
        }
    }

    private void sendHelp(String chatId) {
        String helpText = """
                *Nexus Core Bot*
                
                Available commands:
                `/balance` - View total balance and all accounts
                `/add [Account] [Amount] [INCOME/EXPENSE] [Comment]` - Add transaction
                
                Example:
                `/add USD Cash 100 INCOME Salary`
                """;
        sendMessage(chatId, helpText);
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
        sendMessage(chatId, sb.toString());
    }

    private void handleAddTransaction(String chatId, String text) {
        try {
            String[] parts = text.split(" ", 5);
            if (parts.length < 5) {
                sendMessage(chatId, "Usage: `/add [Account Name] [Amount] [INCOME/EXPENSE] [Comment]`");
                return;
            }

            String accountName = parts[1] + " " + parts[2];
            BigDecimal amount = new BigDecimal(parts[3]);
            String type = parts[4].split(" ")[0];
            String comment = parts[4].substring(type.length()).trim();

            financeService.addTransaction(accountName, amount, type, comment);
            sendMessage(chatId, String.format("Transaction added successfully to *%s*!", accountName));
        } catch (Exception e) {
            sendMessage(chatId, "Error adding transaction. Check format: `/add USD Cash 100 INCOME Salary`");
        }
    }

    private void sendMessage(String chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("Markdown")
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send telegram message", e);
        }
    }
}