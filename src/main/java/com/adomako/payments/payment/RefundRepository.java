package com.adomako.payments.payment;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

  List<Refund> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

  List<Refund> findByPaymentIdInOrderByCreatedAtAsc(Collection<UUID> paymentIds);
}
