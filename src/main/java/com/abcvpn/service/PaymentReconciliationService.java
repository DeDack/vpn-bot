package com.abcvpn.service;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.abcvpn.config.PlategaProperties;
import com.abcvpn.entity.Payment;
import com.abcvpn.repository.PaymentRepository;

@Service
public class PaymentReconciliationService {
    private static final Logger log=LoggerFactory.getLogger(PaymentReconciliationService.class);
    private final PaymentRepository paymentRepository;private final PaymentService paymentService;
    private final PlategaProperties properties;private final Clock clock;
    public PaymentReconciliationService(PaymentRepository paymentRepository,PaymentService paymentService,
                                        PlategaProperties properties,Clock clock) {
        this.paymentRepository=paymentRepository;this.paymentService=paymentService;this.properties=properties;this.clock=clock;
    }
    @Scheduled(fixedDelayString="${abcvpn.payment-reconciliation-delay-ms:60000}")
    public void reconcile() {
        if(!properties.enabled()) return;
        for(Payment payment:paymentRepository.findForReconciliation(clock.instant(),100)) {
            try { paymentService.processCallback(paymentService.checkStatus(payment.id())); }
            catch(RuntimeException exception) { log.warn("[PLATEGA] Status reconciliation failed for payment {}",payment.id()); }
        }
    }
}
