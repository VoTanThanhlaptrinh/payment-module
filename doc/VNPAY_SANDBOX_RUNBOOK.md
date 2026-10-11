# VNPay sandbox IPN and QueryDR runbook

## 1. Prepare configuration

Copy `.env.example` to the ignored `.env` file and provide the real sandbox
`VNPAY_TMN_CODE` and `VNPAY_HASH_SECRET`. Never commit `.env`.

Install Cloudflare Tunnel on Windows:

```powershell
winget install --id Cloudflare.cloudflared
```

Start a quick tunnel first. It may temporarily report that the localhost origin
is unavailable until the application starts:

```powershell
cloudflared tunnel --url http://localhost:8080
```

Copy the generated `https://*.trycloudflare.com` URL into `.env`:

```dotenv
VNPAY_RETURN_URL=https://<tunnel>/api/v1/payments/webhook/vnpay/return
VNPAY_IPN_URL=https://<tunnel>/api/v1/payments/webhook/vnpay
```

The quick-tunnel hostname changes whenever the tunnel is recreated. Update both
environment variables and the VNPay sandbox merchant configuration after every
change.

## 2. Register the server callback

Register the following IPN URL with the VNPay sandbox merchant account or send it
to VNPay support when required by the sandbox account:

```text
https://<tunnel>/api/v1/payments/webhook/vnpay
```

The browser Return URL does not confirm a payment. Only the signed IPN endpoint
and signed QueryDR responses may change database state.

## 3. Start the service

The `local` profile imports the repository `.env` automatically
(`spring.config.import` in `application-local.yaml`), and `mvnw spring-boot:run`
activates `local` by default. Start the service from the repository root:

```powershell
.\mvnw.cmd spring-boot:run
```

The packaged JAR has no default profile; set `SPRING_PROFILES_ACTIVE` explicitly
when running it. Only `local` reads `.env`; staging/production read real
environment variables.

At startup, verify the sanitized log contains the expected `ipnUrl` and
`reconciliationEnabled=true`. Secrets and signatures must not appear in logs.

## 4. Sandbox acceptance flow

1. Create a new payment with a new order ID and `Idempotency-Key`.
2. Open `checkoutUrl` in a browser and complete the VNPay sandbox payment.
3. Verify Cloudflare Tunnel recorded a GET request to the IPN path, not only the
   `/return` path.
4. Verify the IPN response is HTTP 200 with `RspCode=00`.
5. Verify persistence:

```sql
SELECT p.id AS payment_id,
       p.status AS payment_status,
       t.id AS transaction_id,
       t.status AS transaction_status,
       t.provider_transaction_id,
       t.provider_response_code,
       t.completed_at
FROM payments p
JOIN transactions t ON t.payment_id = p.id
WHERE t.id = <vnp_TxnRef>;
```

Expected values are `COMPLETED`, `SUCCESS`, a non-empty provider transaction ID,
response code `00`, and a non-null `completed_at`.

## 5. Recover a missed IPN with QueryDR

Set the outbound public IP of the machine/server in `VNPAY_MERCHANT_IP`, then set:

```dotenv
VNPAY_RECONCILIATION_ENABLED=true
VNPAY_QUERY_API_URL=https://sandbox.vnpayment.vn/merchant_webapi/api/transaction
```

After a pending charge passes `expired_at`, the scheduler queries VNPay after an
initial 30-second delay and then every five minutes. It tries at most 12 times.
Successful, failed and review-required results are applied only after checksum,
merchant, transaction reference, amount and transaction type have all matched.

If VNPay answers with a correctly signed response code `91` (no such transaction)
and the charge is past `expired_at` plus `VNPAY_RECONCILIATION_NOT_FOUND_GRACE`
(default 5 minutes), the payment and transaction become `EXPIRED`, so the order is
no longer locked by an abandoned `PENDING` payment and the customer can start a new
one. Before the grace period the charge stays `PENDING` and is retried. Verify the
`91` behaviour once against the sandbox before relying on it in production.
Reconciliation is enabled by default in the staging and production profiles; they
fail to start without `VNPAY_QUERY_API_URL` and `VNPAY_MERCHANT_IP`.

If an IPN later reports success for a charge already closed as `EXPIRED` or `FAILED`,
the service still answers `RspCode=02` but logs an ERROR (`late success on closed
transaction`). Refund or settle it manually.

If all attempts are exhausted, the payment remains `PENDING` and
`reconciliation_exhausted=true`; investigate it manually rather than marking it
failed without provider evidence.

## 6. Troubleshooting

- Only `/return` is visible in tunnel logs: the VNPay merchant IPN URL was not
  registered or still points to an old tunnel hostname.
- `RspCode=97`: the callback secret or signature canonicalization does not match.
- `RspCode=01`: `vnp_TxnRef` does not identify a stored transaction.
- `RspCode=04`: VNPay amount does not equal stored VND amount multiplied by 100.
- `RspCode=99`: inspect sanitized application logs for merchant mismatch,
  conflicting status codes, unknown codes or a temporary database error.

## 7. Redis requirements for idempotency keys

`POST /api/v1/payments` reserves its `Idempotency-Key` in Redis before touching
PostgreSQL. Redis must be reachable, otherwise the endpoint answers `503
IDEMPOTENCY_STORE_UNAVAILABLE` and creates nothing.

- Set `maxmemory-policy noeviction` (or at least a policy that never evicts keys with
  a TTL under memory pressure). An evicted `IN_PROGRESS` key removes the fast guard
  against concurrent duplicates.
- staging/production require `SPRING_DATA_REDIS_HOST` and `SPRING_DATA_REDIS_PASSWORD`;
  TLS is on by default (`SPRING_DATA_REDIS_SSL_ENABLED=true`).
- Losing Redis data (restart, failover) is safe: PostgreSQL unique constraints
  (`uq_idempotency_scope_key`, `uq_payments_order_id_active`) still prevent duplicates,
  and a retry with the same key and body gets the original payment back.
- `payment.idempotency.in-progress-ttl` must be at least twice
  `payment.idempotency.max-processing-time-seconds`; the application refuses to start
  otherwise.
