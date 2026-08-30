package com.tsimafei.nexus_core.finance.service;

import com.tsimafei.nexus_core.finance.domain.Account;
import com.tsimafei.nexus_core.finance.domain.Transaction;
import com.tsimafei.nexus_core.finance.repository.AccountRepository;
import com.tsimafei.nexus_core.finance.repository.CategoryRepository;
import com.tsimafei.nexus_core.finance.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinanceServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private NbpExchangeRateService nbpExchangeRateService;

    private FinanceService financeService;

    @BeforeEach
    void setUp() {
        financeService = new FinanceService(
                accountRepository,
                transactionRepository,
                categoryRepository,
                nbpExchangeRateService
        );
    }

    private Account createAccount(String name, String currency, BigDecimal balance) {
        Account account = new Account();
        account.setName(name);
        account.setCurrency(currency);
        account.setBalance(balance);
        return account;
    }

    @Test
    void addTransaction_income_shouldIncreaseBalance() {
        Account account = createAccount("Main", "PLN", BigDecimal.valueOf(100.00));
        when(accountRepository.findByName("Main")).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        financeService.addTransaction("Main", BigDecimal.valueOf(50.00), "INCOME", "Salary bonus");

        assertEquals(BigDecimal.valueOf(150.00), account.getBalance());
        verify(accountRepository).save(account);
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    void addTransaction_expense_shouldDecreaseBalance() {
        Account account = createAccount("Main", "PLN", BigDecimal.valueOf(100.00));
        when(accountRepository.findByName("Main")).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        financeService.addTransaction("Main", BigDecimal.valueOf(30.00), "EXPENSE", "Groceries");

        assertEquals(BigDecimal.valueOf(70.00), account.getBalance());
        verify(accountRepository).save(account);
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    void addTransaction_accountNotFound_shouldThrowException() {
        when(accountRepository.findByName("Unknown")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                financeService.addTransaction("Unknown", BigDecimal.TEN, "EXPENSE", "Test")
        );

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transfer_successful_shouldUpdateBothBalances() {
        Account from = createAccount("Card", "PLN", BigDecimal.valueOf(500.00));
        Account to = createAccount("Cash", "PLN", BigDecimal.valueOf(100.00));

        when(accountRepository.findByName("Card")).thenReturn(Optional.of(from));
        when(accountRepository.findByName("Cash")).thenReturn(Optional.of(to));

        financeService.transfer("Card", "Cash", BigDecimal.valueOf(200.00), "ATM withdrawal");

        assertEquals(BigDecimal.valueOf(300.00), from.getBalance());
        assertEquals(BigDecimal.valueOf(300.00), to.getBalance());
        verify(accountRepository).save(from);
        verify(accountRepository).save(to);
        verify(transactionRepository, times(2)).save(any(Transaction.class));
    }

    @Test
    void transfer_differentCurrencies_shouldThrowException() {
        Account from = createAccount("Card PLN", "PLN", BigDecimal.valueOf(500.00));
        Account to = createAccount("Cash USD", "USD", BigDecimal.valueOf(100.00));

        when(accountRepository.findByName("Card PLN")).thenReturn(Optional.of(from));
        when(accountRepository.findByName("Cash USD")).thenReturn(Optional.of(to));

        assertThrows(IllegalArgumentException.class, () ->
                financeService.transfer("Card PLN", "Cash USD", BigDecimal.valueOf(50.00), "Exchange")
        );

        verify(transactionRepository, never()).save(any());
    }
}