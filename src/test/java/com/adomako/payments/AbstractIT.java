package com.adomako.payments;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests. One Postgres container is started per JVM and shared by every
 * subclass, so the Spring context cache stays valid across test classes.
 *
 * <p>Each test runs in a transaction that is rolled back, so tests never see each other's data.
 * Code running on another thread (e.g. a request served by the embedded server) is not covered.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.endpoint.health.show-details=always")
@Transactional
public abstract class AbstractIT {

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static {
    POSTGRES.start();
  }
}
