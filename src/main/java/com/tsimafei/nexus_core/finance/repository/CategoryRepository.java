package com.tsimafei.nexus_core.finance.repository;

import com.tsimafei.nexus_core.finance.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findByNameIgnoreCase(String name);
}