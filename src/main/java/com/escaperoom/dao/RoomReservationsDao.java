package com.escaperoom.dao;

import com.escaperoom.model.ReservationStatus;
import com.escaperoom.model.RoomReservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.annotations.NotNull;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.Map;

/**
 * DAO (Data Access Object) for managing RoomReservations in DynamoDB.
 *
 * Responsibilities:
 * 1. Create, read, and update RoomReservation records.
 * 2. Provide helper methods to build DynamoDB TransactWriteItem objects for transactional updates.
 */
public class RoomReservationsDao {

    private static final Logger logger = LoggerFactory.getLogger(RoomReservationsDao.class);
    private static final String TABLE_NAME = "RoomReservations";

    private final DynamoDbClient dynamoDb;

    public RoomReservationsDao() {
        this.dynamoDb = DynamoDbClientProvider.getClient();
    }

    /**
     * Inserts a RoomReservation into the DynamoDB table.
     *
     * @param res RoomReservation object to insert
     */
    public void putReservation(@NotNull RoomReservation res) {
        res.validate();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("reservationId", AttributeValue.builder().s(res.getReservationId()).build());
        item.put("roomId", AttributeValue.builder().s(res.getRoomId()).build());
        item.put("slotId", AttributeValue.builder().s(res.getSlotId()).build());
        item.put("userId", AttributeValue.builder().s(res.getUserId()).build());
        item.put("reservationStatus", AttributeValue.builder().s(res.getReservationStatus().name()).build());
        item.put("createdAt", AttributeValue.builder().n(String.valueOf(res.getCreatedAt())).build());
        item.put("holdExpiresAt", AttributeValue.builder().n(String.valueOf(res.getHoldExpiresAt())).build());
        item.put("lastUpdated", AttributeValue.builder().n(String.valueOf(res.getLastUpdated())).build());

        try {
            dynamoDb.putItem(builder -> builder.tableName(TABLE_NAME).item(item));
            logger.info("Reservation {} inserted successfully", res.getReservationId());
        } catch (DynamoDbException e) {
            logger.error("Failed to put reservation {} - {}", res.getReservationId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Fetches a RoomReservation from DynamoDB by reservationId.
     *
     * @param reservationId reservation identifier
     * @return RoomReservation object if found; null otherwise
     */
    public RoomReservation getReservationById(@NotNull String reservationId) {
        Map<String, AttributeValue> key = Map.of(
                "reservationId", AttributeValue.builder().s(reservationId).build()
        );

        try {
            GetItemResponse response = dynamoDb.getItem(builder -> builder.tableName(TABLE_NAME).key(key));
            if (!response.hasItem()) {
                logger.warn("Reservation {} not found", reservationId);
                return null;
            }

            Map<String, AttributeValue> item = response.item();
            RoomReservation res = new RoomReservation();
            res.setReservationId(item.get("reservationId").s());
            res.setRoomId(item.get("roomId").s());
            res.setSlotId(item.get("slotId").s());
            res.setUserId(item.get("userId").s());
            res.setReservationStatus(ReservationStatus.valueOf(item.get("reservationStatus").s()));
            res.setCreatedAt(Long.parseLong(item.get("createdAt").n()));
            res.setHoldExpiresAt(Long.parseLong(item.get("holdExpiresAt").n()));
            res.setLastUpdated(Long.parseLong(item.get("lastUpdated").n()));

            return res;

        } catch (DynamoDbException e) {
            logger.error("Failed to get reservation {} - {}", reservationId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Creates a TransactWriteItem for inserting a reservation in a DynamoDB transaction.
     *
     * @param res RoomReservation object
     * @return TransactWriteItem for use in DynamoDB transaction
     */
    public TransactWriteItem buildPutReservationTransactItem(@NotNull RoomReservation res) {
        res.validate();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("reservationId", AttributeValue.builder().s(res.getReservationId()).build());
        item.put("roomId", AttributeValue.builder().s(res.getRoomId()).build());
        item.put("slotId", AttributeValue.builder().s(res.getSlotId()).build());
        item.put("userId", AttributeValue.builder().s(res.getUserId()).build());
        item.put("reservationStatus", AttributeValue.builder().s(res.getReservationStatusStr()).build());
        item.put("createdAt", AttributeValue.builder().n(String.valueOf(res.getCreatedAt())).build());
        item.put("holdExpiresAt", AttributeValue.builder().n(String.valueOf(res.getHoldExpiresAt())).build());
        item.put("lastUpdated", AttributeValue.builder().n(String.valueOf(res.getLastUpdated())).build());

        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName("RoomReservations")
                        .item(item)
                        .build())
                .build();
    }

    /**
     * Builds a TransactWriteItem to confirm a reservation atomically.
     *
     * @param reservation the RoomReservation to confirm
     * @param newTimestamp timestamp to set as lastUpdated
     * @return TransactWriteItem for DynamoDB transaction
     */
    public TransactWriteItem buildConfirmReservationTransactItem(
            @NotNull RoomReservation reservation, long newTimestamp) {

        Map<String, AttributeValue> key = Map.of(
                "reservationId", AttributeValue.builder()
                        .s(reservation.getReservationId()).build()
        );

        String updateExpr =
                "SET reservationStatus = :confirmed, " +
                        "holdExpiresAt = :zero, " +
                        "lastUpdated = :newTimestamp";

        String conditionExpr = "lastUpdated = :expectedLastUpdated";

        Map<String, AttributeValue> values = Map.of(
                ":confirmed", AttributeValue.builder().s(ReservationStatus.CONFIRMED.name()).build(),
                ":zero", AttributeValue.builder().n("0").build(),
                ":newTimestamp", AttributeValue.builder().n(String.valueOf(newTimestamp)).build(),
                ":expectedLastUpdated", AttributeValue.builder().n(String.valueOf(reservation.getLastUpdated())).build()
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
     * Builds a TransactWriteItem to cancel a reservation atomically.
     *
     * @param reservation the RoomReservation to cancel
     * @param newTimestamp timestamp to set as lastUpdated
     * @return TransactWriteItem for DynamoDB transaction
     */
    public TransactWriteItem buildCancelReservationTransactItem(
            @NotNull RoomReservation reservation, long newTimestamp) {

        Map<String, AttributeValue> key = Map.of(
                "reservationId", AttributeValue.builder().s(reservation.getReservationId()).build()
        );

        String updateExpr = "SET reservationStatus = :cancelled, lastUpdated = :newTs";

        String conditionExpr = "lastUpdated = :expectedTs";

        Map<String, AttributeValue> values = Map.of(
                ":cancelled", AttributeValue.builder().s(ReservationStatus.CANCELLED.name()).build(),
                ":newTs", AttributeValue.builder().n(String.valueOf(newTimestamp)).build(),
                ":expectedTs", AttributeValue.builder().n(String.valueOf(reservation.getLastUpdated())).build()
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
     * Builds a TransactWriteItem to expire a reservation atomically.
     *
     * @param reservation the RoomReservation to expire
     * @param newTimestamp timestamp to set as lastUpdated
     * @return TransactWriteItem for DynamoDB transaction
     * */
    public TransactWriteItem buildExpireReservationTransactItem(
            @NotNull RoomReservation reservation, long newTimestamp) {
        Map<String, AttributeValue> key = Map.of(
                "reservationId", AttributeValue.builder().s(reservation.getReservationId()).build()
        );

        String updateExpr = "SET reservationStatus = :expired, lastUpdated = :newTs";

        String conditionExpr = "lastUpdated = :expectedTs";

        Map<String, AttributeValue> values = Map.of(
                ":expired", AttributeValue.builder().s(ReservationStatus.EXPIRED.name()).build(),
                ":newTs", AttributeValue.builder().n(String.valueOf(newTimestamp)).build(),
                ":expectedTs", AttributeValue.builder().n(String.valueOf(reservation.getLastUpdated())).build()
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

}
