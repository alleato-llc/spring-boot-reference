package com.alleato.ecommerce.ordering.invoicing;

/**
 * Contract for storing documents (e.g. order invoices/receipts).
 * Real impl uses AWS S3. Test fakes store in-memory.
 */
public interface DocumentClient {

    void store(String bucket, String key, byte[] content, String contentType);

    byte[] retrieve(String bucket, String key);
}
