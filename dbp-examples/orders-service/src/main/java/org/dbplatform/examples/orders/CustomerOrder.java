package org.dbplatform.examples.orders;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One row of the GET_ORDERS_FOR_CUSTOMER ref cursor. */
public record CustomerOrder(long id,
                            String orderNo,
                            OffsetDateTime orderDate,
                            String status,
                            BigDecimal totalAmount,
                            String currencyCode,
                            long itemCount,
                            String paymentStatus) {
}
