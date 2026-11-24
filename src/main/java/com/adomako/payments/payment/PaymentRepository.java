package com.adomako.payments.payment;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  /**
   * Loads the payment with SELECT ... FOR UPDATE, so concurrent changes to it run one at a time.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Payment p where p.id = :id")
  Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

  List<Payment> findByMerchantIdOrderByCreatedAtDescIdDesc(UUID merchantId, Limit limit);
}
