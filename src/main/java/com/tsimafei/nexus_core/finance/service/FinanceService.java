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
        return transactionRepository.findTop10WithAccount();
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

    @Transactional
    public void transfer(String fromAccountName, String toAccountName, BigDecimal amount, String comment) {
        Account fromAccount = accountRepository.findByName(fromAccountName)
                .orElseThrow(() -> new IllegalArgumentException("Source account not found: " + fromAccountName));

        Account toAccount = accountRepository.findByName(toAccountName)
                .orElseThrow(() -> new IllegalArgumentException("Target account not found: " + toAccountName));

        if (fromAccount.getBalance().compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient funds in account: " + fromAccountName);
        }

        // Списываем со счета-источника
        fromAccount.setBalance(fromAccount.getBalance().subtract(amount));
        accountRepository.save(fromAccount);

        // Пополняем счет-получатель
        toAccount.setBalance(toAccount.getBalance().add(amount));
        accountRepository.save(toAccount);

        String txComment = (comment != null && !comment.isBlank()) ? comment : "Transfer";

        // Записываем две связанные транзакции
        Transaction expenseTx = new Transaction();
        expenseTx.setAccount(fromAccount);
        expenseTx.setAmount(amount);
        expenseTx.setType("EXPENSE");
        expenseTx.setComment("[Transfer -> " + toAccountName + "] " + txComment);
        transactionRepository.save(expenseTx);

        Transaction incomeTx = new Transaction();
        incomeTx.setAccount(toAccount);
        incomeTx.setAmount(amount);
        incomeTx.setType("INCOME");
        incomeTx.setComment("[Transfer <- " + fromAccountName + "] " + txComment);
        transactionRepository.save(incomeTx);
    }

    @Transactional
    public Account createAccount(String name, String currency) {
        if (accountRepository.findByName(name).isPresent()) {
            throw new IllegalArgumentException("Account with name '" + name + "' already exists.");
        }

        Account account = new Account();
        account.setName(name);
        account.setCurrency(currency.toUpperCase());
        account.setBalance(BigDecimal.ZERO);

        return accountRepository.save(account);
    }
}