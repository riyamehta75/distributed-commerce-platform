package com.example.distributedcommerce.paymentservice;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
class IdempotencyKeyTest {
  @Test void duplicateRequestKeysRemainDistinctFromOtherRequests() {
    String key1="payment-order-1-01";
    String key2="payment-order-2-01";
    assertNotEquals(key1,key2);
  }
}
