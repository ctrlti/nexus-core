package com.tsimafei.nexus_core.finance.repository;

import com.tsimafei.nexus_core.finance.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findTop10ByAccountIdOrderByCreatedAtDesc(Long accountId);
}