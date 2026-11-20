package com.example.distributedcommerce.inventoryservice;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class ReservationServiceTest {
  @Test void rejectsWhenStockUpdateAffectsNoRows() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
    ReservationService service=new ReservationService(jdbc);
    try {
      service.reserve(UUID.randomUUID(), UUID.randomUUID(), 11);
      throw new AssertionError("expected insufficient stock");
    } catch (IllegalStateException ex) {
      org.junit.jupiter.api.Assertions.assertEquals("INSUFFICIENT_STOCK",ex.getMessage());
    }
  }

  @Test void concurrentReservationsNeverExceedAvailableStock() throws Exception {
    var dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:inventory-race;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000", "sa", "");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE TABLE stock(product_id UUID PRIMARY KEY, available_quantity INT NOT NULL, reserved_quantity INT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMP DEFAULT now())");
    jdbc.execute("CREATE TABLE inventory_reservation(id UUID PRIMARY KEY, order_id UUID NOT NULL, product_id UUID NOT NULL, quantity INT NOT NULL, status VARCHAR(32) NOT NULL, expires_at TIMESTAMP NOT NULL, UNIQUE(order_id,product_id))");
    UUID productId = UUID.randomUUID();
    jdbc.update("INSERT INTO stock(product_id,available_quantity) VALUES (?,10)", productId);
    ReservationService service = new ReservationService(jdbc);
    TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    int attempts = 100;
    var ready = new CountDownLatch(attempts);
    var start = new CountDownLatch(1);
    var successes = new AtomicInteger();
    var unexpected = new ConcurrentLinkedQueue<Throwable>();
    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    try {
      for (int i = 0; i < attempts; i++) {
        UUID orderId = UUID.randomUUID();
        pool.submit(() -> {
          ready.countDown();
          try {
            start.await();
            transaction.executeWithoutResult(status -> service.reserve(productId, orderId, 1));
            successes.incrementAndGet();
          } catch (IllegalStateException expectedForSoldOutStock) {
            // The conditional stock update rejects requests after the last unit is reserved.
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } catch (Throwable failure) {
            unexpected.add(failure);
          }
        });
      }
      ready.await();
      start.countDown();
      pool.shutdown();
      while (!pool.isTerminated()) Thread.sleep(10);
    } finally {
      pool.shutdownNow();
    }
    org.junit.jupiter.api.Assertions.assertTrue(unexpected.isEmpty(), unexpected.toString());
    assertEquals(10, successes.get());
    assertEquals(0, jdbc.queryForObject("SELECT available_quantity FROM stock WHERE product_id=?", Integer.class, productId));
    assertEquals(10, jdbc.queryForObject("SELECT reserved_quantity FROM stock WHERE product_id=?", Integer.class, productId));
  }
}
