# Banking System Microservices: Flow Analysis & Pending Tasks

This document provides a detailed, in-depth analysis of the microservices architecture, its event-driven workflows, and a list of bugs, gaps, and tasks that still remain to be completed.

---

## 1. System Architecture & Node Connections

The Banking System consists of the following components:
- **API Gateway (Port 8088)**: Entry point for requests. It routes account, transaction, and payment path prefixes and applies Redis-backed request rate limiting.
- **Account Service (Port 8081)**: Manages bank accounts, balances, and account statuses (Active/Blocked). Uses MySQL.
- **Transaction Service (Port 8082)**: Coordinates money transfers between accounts. Implements a SAGA-like orchestration flow. Uses MySQL and Redis (for OTP storage).
- **Fraud Detection Service (Port 8084)**: Evaluates transaction risks based on velocity, amount thresholds, and balance percentages. Uses Redis (for velocity/average tracking).
- **Payment Service (Port 8080, Spring Boot default)**: Integrates with Razorpay for deposit orders and processes webhooks. Uses MySQL. No explicit `server.port` is configured.
- **Notification Service (Port 8085)**: Consumes Kafka events to log/alert users of transactions, refunds, OTPs, and blocks.
- **Kafka Broker (host port 9092; Docker-network listener 29092)**: Handles asynchronous event streaming between services. The Transaction Service currently targets `localhost:29092`, while the other services target `localhost:9092`.
- **Redis (Port 6379)**: Acts as a shared cache/temporary store for velocity checks, average transaction metrics, and OTP codes.

### System Connections & Event Flow Diagram

Below is the complete flow chart showing how all components interact synchronously (via Feign/REST Clients) and asynchronously (via Kafka Topics):

```

![Neon Banking Microservices Architecture.png](../../Downloads/Neon%20Banking%20Microservices%20Architecture.png)

```

---

## 2. In-Depth Workflow Walks

### A. Money Transfer Flow (SAGA Orchestration Pattern)
1. **Initiate Transfer**: Client sends a POST request to `/api/v1/transactions/transfer` on `Transaction Service`.
2. **Deduct Balance**: `Transaction Service` synchronously calls `Account Service` via Feign client to deduct the transfer amount from the sender's account.
   * If balance is insufficient or account is blocked, the request fails immediately.
3. **Save Transaction**: `Transaction Service` creates a transaction record in MySQL with `PROCESSING` status.
4. **Publish Initiated Event**: `Transaction Service` publishes a `TransactionInitiatedEvent` to the `transaction.initiated` Kafka topic.
5. **Fraud Evaluation**: `Fraud Detection Service` consumes `transaction.initiated`. It checks:
   * **Velocity**: Number of transactions in the last 60 seconds (stored in Redis).
   * **Amount Rule**: If transaction amount is > 3x average amount (average tracked in Redis).
   * **Balance Rule**: If transaction amount consumes > 90% of the account's balance (fetched from `Account Service` via Feign client).
6. **Fraud Check Result**:
   * **Clean**: If no rules are triggered, `Fraud Detection Service` publishes `isFraud: false` to the `fraud.check.clean` topic. `Transaction Service` consumes this and moves the status to `COMPLETED`, credits the receiver (missing step / currently only saves status completed and publishes transaction.completed), and publishes a completed event.
   * **Suspicious**: If rules are triggered, it publishes a `verification.required` event with a reason.
7. **OTP Verification Required Flow**:
   * `Transaction Service` consumes `verification.required`, changes transaction status to `PENDING_VERIFICATION`, generates a 6-digit OTP, stores it in Redis (expiry: 5 minutes), and publishes `transaction.otp.generated`.
   * `Notification Service` consumes the OTP event and logs/alerts the user with the OTP code.
   * The client submits the OTP code to `/verify` in `Transaction Service`:
     * **Success**: Transaction is completed, status is changed to `COMPLETED`, and `transaction.completed` event is published.
     * **Expired OTP**: Balance is credited back (compensated), status changed to `FLAGGED`, and `transaction.refunded` is published.
     * **Incorrect OTP**: Publishes `fraud.detected`, block account notification is fired, balance is compensated (credited back), status changed to `FLAGGED`, and `transaction.refunded` is published.

### B. Deposit Flow (Razorpay Integration)
1. **Create Order**: Client sends POST `/api/v1/payments/create-order` to `Payment Service`.
2. **Razorpay Request**: `Payment Service` calls the Razorpay API to generate an Order ID, saves the payment in MySQL as `CREATED`, and returns order info to client.
3. **Webhook Callback**: Once the user completes the payment, Razorpay sends a webhook notification to `/api/v1/payments/webhook`.
   * **Success (`payment.capture`)**: `Payment Service` marks the payment status as `COMPLETED` and publishes `payment.completed` to Kafka.
   * **Failure (`payment.failed`)**: Marks payment as `FAILED` and publishes `payment.failed` to Kafka.
4. **Notification**: `Notification Service` consumes `payment.completed` or `payment.failed` and logs an alert.

---

## 3. Things That Still Remain (Bugs & Gaps Checklist)

### 🐛 Critical Bugs & Structural Mismatches

- [ ] **Kafka Topic Typo in Notification Service**:
  * In `TransactionService.java`, the completion event is sent to `"transaction.completed"`.
  * In `NotificationService.java` (Line 20), the listener is listening to `"transanction.completed"` (note the extra **'n'** in `transanction`).
  * **Impact**: Debit/Credit notifications for completed transactions are never processed.
- [ ] **Feign Client Variable Binding Bug in Fraud Detection Service**:
  * In `AccountServiceClient.java` (Line 12) of the `fraud-detection-service`, the method signature is:
    `BigDecimal getbalance(@PathVariable String accountHolder);`
  * The path variable in `@GetMapping` is `/api/v1/accounts/{accountNumber}/balance`.
  * **Impact**: Feign will throw a runtime binding exception because the path variable `{accountNumber}` does not match the parameter name `accountHolder`.
- [ ] **Missing Account Balance Update on Payment Success**:
  * When a Razorpay payment completes, `Payment Service` publishes a `payment.completed` event containing the amount and account number.
  * **Impact**: There is no listener in `account-service` or webhook handler that credits this amount to the user's account balance. The payment shows as completed, but the user's balance never increases.
- [ ] **Missing Account Blocking Logic on Fraud Detection**:
  * In `TransactionService.java`, when a wrong OTP is entered, it publishes a `fraud.detected` event with the account number.
  * `Notification Service` catches this and prints a notification that the account is blocked.
  * **Impact**: The actual status of the account in the `Account Service` database remains `ACTIVE` because `account-service` has no Kafka listener or Feign client configuration to change the database status to `BLOCKED`.
- [ ] **Incomplete Credit step in Transaction Completion**:
  * In `TransactionService.java`, when completing the transaction (`completeTransaction`), the status is updated to `COMPLETED` and the event `transaction.completed` is published.
  * **Impact**: The receiver's account balance is never credited! The sender's balance was deducted at step 1, but the receiver's balance is never updated in the DB.

### ⚙️ Infrastructure & Configuration Gaps

- [x] **API Gateway Routing Configuration**:
  * `api-gateway/src/main/resources/application.yaml` defines routes for `/api/v1/accounts/**`, `/api/v1/transactions/**`, and `/api/v1/payments/**` and runs on port `8088`.
  * **Follow-up**: The payment route still targets `http://localhost:8083`, but `payment-service` currently has no explicit `server.port` and therefore runs on Spring Boot's default port `8080`.
- [ ] **Empty Rate Limiter Configuration**:
  * `RateLimiterConfig.java` in `api-gateway` is an empty class stub. No Redis rate limiting or custom request filters are set up.
- [ ] **Placeholder Database Credentials**:
  * In both `transaction-service` and other services' configurations, DB credentials match default placeholders or are unconfigured. The docker-compose database credentials must be harmonized.
- [ ] **Kafka / Redis Host Configuration for Docker-compose**:
  * Some service configurations use `localhost` for Redis and Kafka brokers. If run inside docker containers, these must be configured to use container names (`redis`, `kafka`) or environment variable overrides.
  * The Transaction Service additionally uses `localhost:29092`, but Docker exposes Kafka to local host processes on `localhost:9092`.

### 🛠️ Architectural Improvements

- [ ] **No Dead Letter Queues (DLQ) or Retries**:
  * If a transaction event fails during consumption (e.g. database locks or network blips), there is no retry policy or DLQ configuration.
- [ ] **Lack of Global Exception Handlers**:
  * Rest services need unified error payloads (`@RestControllerAdvice`) to handle exceptions like `InsufficientBalanceException` gracefully and return correct HTTP statuses to the clients.
- [ ] **Lack of Integration/Unit Tests**:
  * Basic unit tests or test containers for Kafka and MySQL flows are not implemented.
