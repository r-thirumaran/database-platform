package org.dbplatform.examples.orders;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One ORDERS row joined with the customer e-mail. */
public record OrderView(long id,
                        String orderNo,
                        long customerId,
                        String customerEmail,
                        OffsetDateTime orderDate,
                        String status,
                        BigDecimal totalAmount,
                        String currencyCode) {
}
