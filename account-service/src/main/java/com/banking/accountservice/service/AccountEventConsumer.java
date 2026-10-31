package com.banking.accountservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountEventConsumer {

    private final AccountService accountService;

    /**
     * Listen for successful Razorpay payments to credit user balances.
     */
    @KafkaListener(topics = "payment.completed", groupId = "account-group")
    public void consumePaymentCompleted(@Payload Map<String, Object> payload) {
        try {
            String accountNumber = (String) payload.get("accountNumber");
            BigDecimal amount = new BigDecimal(payload.get("amount").toString());

            log.info("Processing deposit: Crediting account {} with amount {}", accountNumber, amount);
            accountService.creditBalance(accountNumber, amount);
        } catch (Exception e) {
            log.error("Failed to process payment completion event", e);
        }
    }

    /**
     * Listen for fraud detection events to block compromised accounts.
     */
    @KafkaListener(topics = "fraud.detected", groupId = "account-group")
    public void consumeFraudDetected(@Payload Map<String, Object> payload) {
        try {
            String accountNumber = (String) payload.get("accountNumber");
            String reason = (String) payload.get("reason");

            log.warn("Processing block: Blocking account {} due to: {}", accountNumber, reason);
            accountService.blockAccount(accountNumber);
        } catch (Exception e) {
            log.error("Failed to process fraud detection event", e);
        }
    }
}
