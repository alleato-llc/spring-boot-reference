package com.alleato.ecommerce.ordering.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleato.ecommerce.ordering.support.BaseIntegrationTest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * INTEGRATION TEST — verifies that Flyway migrations run correctly against a real Postgres instance
 * (via docker-compose).
 *
 * <p>This catches issues that in-memory databases (H2) would miss: - Postgres-specific syntax (e.g.
 * BIGSERIAL, TIMESTAMP WITH TIME ZONE) - ALTER TABLE migrations that depend on existing schema -
 * Index creation on Postgres
 *
 * <p>This is a key benefit of testing against real Postgres: you test against the real database
 * engine, not a substitute.
 */
class FlywayMigrationIntegrationTest extends BaseIntegrationTest {

  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("all Flyway migrations apply successfully and produce expected schema")
  void migrationsProduceExpectedSchema() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      DatabaseMetaData meta = conn.getMetaData();

      // Verify expected tables exist
      List<String> tables = getTableNames(meta);
      assertThat(tables).contains("orders", "order_line_items");

      // Verify V2 migration added the 'notes' column
      List<String> orderColumns = getColumnNames(meta, "orders");
      assertThat(orderColumns)
          .contains(
              "id",
              "customer_id",
              "status",
              "subtotal",
              "discount",
              "total",
              "payment_transaction_id",
              "created_at",
              "notes",
              "inventory_reservation_id");
    }
  }

  private List<String> getTableNames(DatabaseMetaData meta) throws Exception {
    List<String> tables = new ArrayList<>();
    try (ResultSet rs = meta.getTables(null, "public", "%", new String[] {"TABLE"})) {
      while (rs.next()) {
        tables.add(rs.getString("TABLE_NAME"));
      }
    }
    return tables;
  }

  private List<String> getColumnNames(DatabaseMetaData meta, String table) throws Exception {
    List<String> columns = new ArrayList<>();
    try (ResultSet rs = meta.getColumns(null, "public", table, "%")) {
      while (rs.next()) {
        columns.add(rs.getString("COLUMN_NAME"));
      }
    }
    return columns;
  }
}
