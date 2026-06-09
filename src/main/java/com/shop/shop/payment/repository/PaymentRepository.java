package com.shop.shop.payment.repository;

import com.shop.shop.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Payment JPA Repository.
 *
 * <p>주문당 결제 1건: {@code uq_payments_order_id} 제약.
 *
 * <p>ready row 선점(#2):
 * {@code save(Payment.create(...))} INSERT 후 {@code saveAndFlush}로 즉시 flush.
 * 동시 2건 중 하나만 INSERT 성공, 나머지는 {@code DataIntegrityViolationException}.
 * 호출 측({@link com.shop.shop.payment.service.PaymentService})에서 catch 후 재조회·분기 처리.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * orderId로 결제 row 조회.
     *
     * <p>주문당 결제 1건(uq_payments_order_id)이므로 Optional로 반환.
     *
     * @param orderId 주문 ID
     * @return 결제 row (없으면 empty)
     */
    Optional<Payment> findByOrderId(long orderId);
}
