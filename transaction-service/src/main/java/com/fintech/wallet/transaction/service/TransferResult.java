package com.fintech.wallet.transaction.service;

import com.fintech.wallet.transaction.domain.Transfer;

/**
 * @param created true if this call created the transfer, false if it is a replay of an earlier request
 *                with the same Idempotency-Key
 */
public record TransferResult(Transfer transfer, boolean created) {
}
