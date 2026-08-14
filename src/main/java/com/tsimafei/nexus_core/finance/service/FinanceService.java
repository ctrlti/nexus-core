package com.tsimafei.nexus_core.finance.service;

import com.tsimafei.nexus_core.finance.domain.Account;
import com.tsimafei.nexus_core.finance.domain.Category;
import com.tsimafei.nexus_core.finance.domain.Transaction;
import com.tsimafei.nexus_core.finance.repository.AccountRepository;
import com.tsimafei.nexus_core.finance.repository.CategoryRepository;
import com.tsimafei.nexus_core.finance.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class FinanceService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final NbpExchangeRateService exchangeRateService;

    public FinanceService(AccountRepository accountRepository,
                          TransactionRepository transactionRepository,
                          CategoryRepository categoryRepository,
                          NbpExchangeRateService exchangeRateService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.exchangeRateService = exchangeRateService;
    }

    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    @Transactional
    public Transaction addTransaction(String accountName, BigDecimal amount, String type, String comment) {
        Account account = accountRepository.findByName(accountName)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountName));

        // Автоматически привязываем дефолтную категорию "Other"
        Category category = categoryRepository.findByNameIgnoreCase("Other")
                .orElseGet(() -> categoryRepository.save(new Category("Other", type.toUpperCase())));

        BigDecimal currentBalance = account.getBalance();
        BigDecimal newBalance = "INCOME".equalsIgnoreCase(type)
                ? currentBalance.add(amount)
                : currentBalance.subtract(amount);

        account.setBalance(newBalance);
        accountRepository.save(account);

        Transaction transaction = new Transaction();
        transaction.setAccount(account);
        transaction.setAmount(amount);
        transaction.setType(type.toUpperCase());
        transaction.setCategory(category);
        transaction.setComment(comment);

        return transactionRepository.save(transaction);
    }

    public List<Transaction> getRecentTransactions() {
        return transactionRepository.findAll()
                .stream()
                .sorted((t1, t2) -> t2.getCreatedAt().compareTo(t1.getCreatedAt()))
                .limit(10)
                .toList();
    }

    public BigDecimal getTotalInPln() {
        List<Account> accounts = accountRepository.findAll();
        BigDecimal totalPln = BigDecimal.ZERO;

        BigDecimal usdRate = exchangeRateService.getRate("USD");
        BigDecimal eurRate = exchangeRateService.getRate("EUR");

        for (Account account : accounts) {
            BigDecimal balance = account.getBalance();
            if ("USD".equalsIgnoreCase(account.getCurrency())) {
                totalPln = totalPln.add(balance.multiply(usdRate));
            } else if ("EUR".equalsIgnoreCase(account.getCurrency())) {
                totalPln = totalPln.add(balance.multiply(eurRate));
            }
        }

        return totalPln.setScale(2, RoundingMode.HALF_UP);
    }
}