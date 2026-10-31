package com.example.transactionservice.controller;

import com.example.transactionservice.dto.TransactionResponse;
import com.example.transactionservice.dto.TransferRequest;
import com.example.transactionservice.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Slf4j

public class TransactionController {

private final TransactionService transactionService;

//Transctions money accounts ke bich me
    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @Valid @RequestBody TransferRequest request
            ){
        return ResponseEntity.status(HttpStatus.CREATED).
                body(transactionService.transfer(request));

    }

    @PostMapping("/{transactionId}/verify-otp")
    public ResponseEntity<TransactionResponse> verifyOTP(
            @PathVariable String transactionId,
            @RequestParam String otp
            ){
        return ResponseEntity.ok(transactionService.verifyOTP(transactionId, otp));
    }
}
