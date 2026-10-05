/**
 * One immutable record per DBP wire message (section 4 of {@code docs/wire-protocol.md}), the sealed
 * {@link org.dbplatform.protocol.messages.Message} interface they implement, the
 * {@link org.dbplatform.protocol.messages.Messages} frame dispatcher and the streaming
 * {@link org.dbplatform.protocol.messages.RowsWriter}.
 */
package org.dbplatform.protocol.messages;
