package com.escaperoom.dao;
import com.escaperoom.model.EscapeRoomSlot;
import com.escaperoom.model.SlotStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.annotations.NotNull;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.Map;

/**
 * DAO (Data Access Object) for managing Escape Room slots in DynamoDB.
 *
 * This class provides methods to:
 * - Create or update slots (putSlot)
 * - Retrieve a slot (getSlot)
 * - Build transactional update items for slot operations:
 *      - Confirm a slot (book)
 *      - Hold a slot
 *      - Release a slot (make available)
 */
public class EscapeRoomSlotsDao {

    private static final Logger logger = LoggerFactory.getLogger(EscapeRoomSlotsDao.class);
    private static final String TABLE_NAME = "EscapeRoomSlots";

    private final DynamoDbClient dynamoDb;

    public EscapeRoomSlotsDao() {
        this.dynamoDb = DynamoDbClientProvider.getClient();
    }


    /**
     * Build a TransactWriteItem to confirm (book) a slot.
     * Uses conditional update to ensure lastUpdated matches the expected value (optimistic locking).
     *
     * @param slot         the slot to confirm
     * @param newTimestamp epoch seconds for the new lastUpdated value
     * @return TransactWriteItem ready for DynamoDB transaction
     */
    public TransactWriteItem buildConfirmSlotTransactItem(@NotNull EscapeRoomSlot slot, long newTimestamp) {

        Map<String, AttributeValue> key = Map.of(
                "roomId", AttributeValue.builder().s(slot.getRoomId()).build(),
                "slotId", AttributeValue.builder().s(slot.getSlotId()).build()
        );

        String updateExpr =
                "SET slotStatus = :confirmed, " +
                        "lastUpdated = :newTimestamp";

        String conditionExpr = "lastUpdated = :expectedLastUpdated";

        Map<String, AttributeValue> values = Map.of(
                ":confirmed", AttributeValue.builder().s(SlotStatus.BOOKED.name()).build(),
                ":newTimestamp", AttributeValue.builder().n(String.valueOf(newTimestamp)).build(),
                ":expectedLastUpdated", AttributeValue.builder().n(String.valueOf(slot.getLastUpdated())).build()
        );

        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(TABLE_NAME)
                        .key(key)
                        .updateExpression(updateExpr)
                        .conditionExpression(conditionExpr)
                        .expressionAttributeValues(values)
                        .build())
                .build();
    }

    /**
     * Build a TransactWriteItem to put a slot on HOLD for a reservation.
     *
     * @param slot           the slot to hold
     * @param reservationId  the reservationId associated with the hold
     * @param newLastUpdated epoch seconds for the new lastUpdated value
     * @return TransactWriteItem ready for DynamoDB transaction
     */
    public TransactWriteItem buildHoldSlotTransactItem(@NotNull EscapeRoomSlot slot, String reservationId,
                                                       long newLastUpdated) {
        Map<String, AttributeValue> key = Map.of(
                "roomId", AttributeValue.builder().s(slot.getRoomId()).build(),
                "slotId", AttributeValue.builder().s(slot.getSlotId()).build()
        );

        Map<String, AttributeValue> exprValues = new HashMap<>();
        exprValues.put(":expectedLastUpdated", AttributeValue.builder().n(String.valueOf(slot.getLastUpdated())).build());
        exprValues.put(":newStatus", AttributeValue.builder().s(SlotStatus.HOLD.name()).build());
        exprValues.put(":newReservationId", AttributeValue.builder().s(reservationId).build());
        exprValues.put(":newLastUpdated", AttributeValue.builder().n(String.valueOf(newLastUpdated)).build());

        String conditionExpression = "lastUpdated = :expectedLastUpdated ";
        String updateExpression = "SET slotStatus = :newStatus, currentReservationId = :newReservationId, lastUpdated = :newLastUpdated";

        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(TABLE_NAME)
                        .key(key)
                        .conditionExpression(conditionExpression)
                        .updateExpression(updateExpression)
                        .expressionAttributeValues(exprValues)
                        .build())
                .build();
    }

    /**
     * Build a TransactWriteItem to release a slot (make it AVAILABLE).
     *
     * @param slot         the slot to release
     * @param newTimestamp epoch seconds for the new lastUpdated value
     * @return TransactWriteItem ready for DynamoDB transaction
     */
    public TransactWriteItem buildReleaseSlotTransactItem(@NotNull EscapeRoomSlot slot, long newTimestamp) {

        Map<String, AttributeValue> key = Map.of(
                "roomId", AttributeValue.builder().s(slot.getRoomId()).build(),
                "slotId", AttributeValue.builder().s(slot.getSlotId()).build()
        );

        String updateExpr = "SET slotStatus = :available, currentReservationId = :nullVal, lastUpdated = :newTs";

        String conditionExpr = "lastUpdated = :expectedTs";

        Map<String, AttributeValue> values = Map.of(
                ":available", AttributeValue.builder().s(SlotStatus.AVAILABLE.name()).build(),
                ":nullVal", AttributeValue.builder().nul(true).build(),
                ":newTs", AttributeValue.builder().n(String.valueOf(newTimestamp)).build(),
                ":expectedTs", AttributeValue.builder().n(String.valueOf(slot.getLastUpdated())).build()
        );

        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(TABLE_NAME)
                        .key(key)
                        .updateExpression(updateExpr)
                        .conditionExpression(conditionExpr)
                        .expressionAttributeValues(values)
                        .build())
                .build();
    }

    /**
     * Insert or update a slot in DynamoDB.
     * For use by data ingestion to populate more records.
     *
     * @param slot the slot to put into the table
     */
    public void putSlot(@NotNull EscapeRoomSlot slot) {
        slot.validate();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("roomId", AttributeValue.builder().s(slot.getRoomId()).build());
        item.put("slotId", AttributeValue.builder().s(slot.getSlotId()).build());
        item.put("slotStatus", AttributeValue.builder().s(slot.getSlotStatus().name()).build());
        item.put("lastUpdated", AttributeValue.builder().n(String.valueOf(slot.getLastUpdated())).build());
        item.put("createdTime", AttributeValue.builder().n(String.valueOf(slot.getCreatedTime())).build());

        // Always include currentReservationId, set NULL if no reservation
        if (slot.getCurrentReservationId() != null) {
            item.put("currentReservationId", AttributeValue.builder().s(slot.getCurrentReservationId()).build());
        } else {
            item.put("currentReservationId", AttributeValue.builder().nul(true).build());
        }

        try {
            dynamoDb.putItem(builder -> builder.tableName(TABLE_NAME).item(item));
            logger.info("Slot {}:{} inserted/updated successfully", slot.getRoomId(), slot.getSlotId());
        } catch (DynamoDbException e) {
            logger.error("Failed to put slot {}:{} - {}", slot.getRoomId(), slot.getSlotId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Get a slot from DynamoDB by roomId and slotId.
     * Throws IllegalStateException if required timestamp fields are missing.
     *
     * @param roomId the room identifier
     * @param slotId the slot identifier
     * @return EscapeRoomSlot object, or null if not found
     */
    public EscapeRoomSlot getSlot(@NotNull String roomId, @NotNull String slotId) {
        Map<String, AttributeValue> key = Map.of(
                "roomId", AttributeValue.builder().s(roomId).build(),
                "slotId", AttributeValue.builder().s(slotId).build()
        );

        try {
            GetItemResponse response = dynamoDb.getItem(builder -> builder.tableName(TABLE_NAME).key(key));
            if (!response.hasItem()) {
                logger.warn("Slot {}:{} not found", roomId, slotId);
                return null;
            }

            Map<String, AttributeValue> item = response.item();
            EscapeRoomSlot slot = new EscapeRoomSlot();
            slot.setRoomId(item.get("roomId").s());
            slot.setSlotId(item.get("slotId").s());
            slot.setSlotStatus(SlotStatus.valueOf(item.get("slotStatus").s()));

            // optional
            if (item.containsKey("currentReservationId")) {
                slot.setCurrentReservationId(item.get("currentReservationId").s());
            } else {
                slot.setCurrentReservationId(null);
            }

            // required fields
            if (!item.containsKey("lastUpdated") || !item.containsKey("createdTime")) {
                throw new IllegalStateException("Required timestamp attribute missing for slot " + roomId + ":" + slotId);
            }
            slot.setLastUpdated(Long.parseLong(item.get("lastUpdated").n()));
            slot.setCreatedTime(Long.parseLong(item.get("createdTime").n()));

            slot.validate();
            return slot;

        } catch (DynamoDbException e) {
            logger.error("Failed to get slot {}:{} - {}", roomId, slotId, e.getMessage(), e);
            throw e;
        }
    }
}
