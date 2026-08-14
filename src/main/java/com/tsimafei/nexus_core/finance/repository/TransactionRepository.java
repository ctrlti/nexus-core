package com.tsimafei.nexus_core.finance.repository;

import com.tsimafei.nexus_core.finance.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Query("SELECT t FROM Transaction t JOIN FETCH t.account ORDER BY t.createdAt DESC LIMIT 10")
    List<Transaction> findTop10WithAccount();
}