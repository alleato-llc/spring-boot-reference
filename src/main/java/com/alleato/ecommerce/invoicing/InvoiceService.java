package com.alleato.ecommerce.invoicing;

import com.alleato.ecommerce.ordering.models.Order;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

/** Generates and stores order invoices to the document client (S3 in prod). */
@Service
public class InvoiceService {

  private static final String INVOICE_BUCKET = "order-invoices";

  private final DocumentClient documentClient;

  public InvoiceService(DocumentClient documentClient) {
    this.documentClient = documentClient;
  }

  public void generateAndStore(Order order) {
    String invoiceContent = buildInvoiceText(order);
    String key = "invoices/order-%d.txt".formatted(order.getId());
    documentClient.store(
        INVOICE_BUCKET, key, invoiceContent.getBytes(StandardCharsets.UTF_8), "text/plain");
  }

  String buildInvoiceText(Order order) {
    StringBuilder sb = new StringBuilder();
    sb.append("INVOICE — Order #%d\n".formatted(order.getId()));
    sb.append("Customer: %s\n".formatted(order.getCustomerId()));
    sb.append("---\n");
    order
        .getLineItems()
        .forEach(
            li ->
                sb.append(
                    "%s x%d @ $%s = $%s\n"
                        .formatted(
                            li.getProductName(), li.getQuantity(),
                            li.getUnitPrice(), li.getLineTotal())));
    sb.append("---\n");
    sb.append("Subtotal: $%s\n".formatted(order.getSubtotal()));
    sb.append("Discount: -$%s\n".formatted(order.getDiscount()));
    sb.append("Total:    $%s\n".formatted(order.getTotal()));
    return sb.toString();
  }
}
