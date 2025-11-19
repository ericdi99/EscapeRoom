package com.escaperoom.reservations;

import com.escaperoom.dao.DynamoDbClientProvider;
import com.escaperoom.dao.EscapeRoomSlotsDao;
import com.escaperoom.dao.RoomReservationsDao;
import com.escaperoom.model.ReservationStatus;
import com.escaperoom.model.RoomReservation;
import com.escaperoom.model.EscapeRoomSlot;
import com.escaperoom.model.SlotStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.annotations.NotNull;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.*;

public class ReservationController {
    private static final Logger logger = LoggerFactory.getLogger(ReservationController.class);
    private static final int TIMEOUT_SECONDS = 60*5;
    private final DynamoDbClient dynamoDb;
    private final EscapeRoomSlotsDao slotsDao;
    private final RoomReservationsDao reservationsDao;
    private final ReservationHoldWorkflowInvoker invoker;

    public ReservationController() {
        this.dynamoDb = DynamoDbClientProvider.getClient();
        this.slotsDao = new EscapeRoomSlotsDao();
        this.reservationsDao = new RoomReservationsDao();
        this.invoker = new ReservationHoldWorkflowInvoker();
    }

    /**
     * Creates a new reservation for a given escape room slot. The reservation is initially in HOLD status.
     * Performs a DynamoDB transactional write to update both the RoomReservations table and the EscapeRoomSlots table.
     *
     * @param roomId the room id
     * @param slotId the slot id in the format Date#Hour
     * @param userId the user making the reservation
     * @return the created RoomReservation
     * @throws IllegalArgumentException if the slot is not available or already reserved
     * @throws RuntimeException if the creation fails
     */
    public RoomReservation createReservation(@NotNull String roomId, @NotNull String slotId, @NotNull String userId) {
        EscapeRoomSlot slot = slotsDao.getSlot(roomId, slotId);
        // Eligibility check
        if (!isSlotAvailableForReservation(slot)) {
            logger.warn("Slot not available: {}:{}", roomId, slotId);
            throw new IllegalArgumentException("The escape room time slot is no longer available");
        }

        //Use second here so that it can be used as TTL for DDB record (in furture)
        long newTimeStamp = Instant.now().getEpochSecond();
        String reservationId = UUID.randomUUID().toString();
        RoomReservation res = new RoomReservation(reservationId, roomId, slotId, userId, ReservationStatus.HOLD);
        res.setHoldExpiresAt(newTimeStamp + TIMEOUT_SECONDS);

        List<TransactWriteItem> transactItems = List.of(
                slotsDao.buildHoldSlotTransactItem(slot, reservationId, newTimeStamp),
                reservationsDao.buildPutReservationTransactItem(res)
        );

        try {
            dynamoDb.transactWriteItems(builder -> builder.transactItems(transactItems));
            logger.info("Reservation {} created atomically", reservationId);
            invoker.startHoldExpiryWorkflow(reservationId);
            return  reservationsDao.getReservationById(reservationId);
        } catch (TransactionCanceledException e) {
            logger.warn("Transaction failed to create reservation: {}", e.getMessage());
            throw new RuntimeException("Failed to create reservation: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error creating reservation {}: {}", reservationId, e.getMessage(), e);
            throw new RuntimeException("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Confirms a reservation by updating its status to CONFIRMED and updating the corresponding
     * EscapeRoomSlot. The operation is performed atomically using DynamoDB transaction.
     *
     * @param reservationId the reservation ID to confirm
     * @throws IllegalArgumentException if the reservation or slot does not exist, or validation fails
     * @throws RuntimeException if the DynamoDB transaction fails
     */
    public void confirmReservation(@NotNull String reservationId) {
        RoomReservation reservation = reservationsDao.getReservationById(reservationId);
        //Input validation
        if(reservation == null) {
            throw new IllegalArgumentException("Reservation not found with id " + reservationId);
        }
        EscapeRoomSlot slot = slotsDao.getSlot(reservation.getRoomId(), reservation.getSlotId());
        if(slot == null) {
            throw new IllegalArgumentException("Slot not found with id " + reservation.getSlotId());
        }
        // Eligibility check
        if (!isValidForConfirmation(reservation, slot)) {
            logger.error("Escape Room validation failed: reservation {}, slot {} ", reservation, slot);
            throw new IllegalArgumentException("Reservation no longer available.");
        }

        long now = System.currentTimeMillis();
        List<TransactWriteItem> transactItems = List.of(
                slotsDao.buildConfirmSlotTransactItem(slot, now),
                reservationsDao.buildConfirmReservationTransactItem(reservation, now)
        );

        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(transactItems)
                    .build());
        } catch (TransactionCanceledException e) {
            logger.error("Failed to confirm reservation with id {} due to transaction failure. ErrorMessage: {}", reservationId, e.getMessage());
            throw new RuntimeException("Confirmation failed with " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error confirming reservation {}: {}", reservationId, e.getMessage(), e);
            throw new RuntimeException("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Cancels a reservation explicitly as user requested.
     * Only reservations in HOLD or CONFIRMED status can be cancelled.
     * Actions
     * 1.Update EscapeRoomSlots record status to AVAILABLE and clear reservationId
     * 2.Updates the reservation status to CANCELLED.
     *
     * @param reservationId the ID of the reservation to cancel
     * @throws IllegalArgumentException if reservation or slot not found, or validation fails
     * @throws RuntimeException if DynamoDB transaction fails
     */
    public void cancelReservation(@NotNull String reservationId) {
        RoomReservation reservation = reservationsDao.getReservationById(reservationId);
        //Input validation
        if(reservation == null) {
            throw new IllegalArgumentException("Reservation not found with id " + reservationId);
        }
        EscapeRoomSlot slot = slotsDao.getSlot(reservation.getRoomId(), reservation.getSlotId());
        if(slot == null) {
            throw new IllegalArgumentException("Slot not found with id " + reservation.getSlotId());
        }
        // Eligibility check
        if (!isValidForCancellation(reservation, slot)) {
            logger.error("cancelReservation Validation failed: reservation {}, slot {}",
                    reservation, slot);
            throw new IllegalArgumentException("Reservation not valid for cancellation");
        }

        long now = System.currentTimeMillis();
        List<TransactWriteItem> transactItems = List.of(
                slotsDao.buildReleaseSlotTransactItem(slot, now),
                reservationsDao.buildCancelReservationTransactItem(reservation, now)
        );

        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(transactItems).build());
        } catch (TransactionCanceledException e) {
            logger.error("Transaction with reservation cancellation: id {}, ErrorMessage: {}", reservationId, e.getMessage());
            throw new RuntimeException("Cancellation failed. {} " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error cancelling reservation {}: {}", reservationId, e.getMessage(), e);
            throw new RuntimeException("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Expires a reservation whose HOLD has passed its TTL timeout.
     * This is invoked automatically by a system automated process, not by the user.
     *
     * Rules:
     *  - Reservation must be in HOLD status
     *  - Reservation  must be expired: holdExpiresAt <= current time
     *  - Slot must still be in HOLD status
     *  - Slot must reference the same reservationId
     *
     * If valid, this method set the slot to AVAILABLE marks the reservation as EXPIRED.
     *
     * @param reservationId the ID of the reservation whose hold should be expired
     * @throws IllegalArgumentException if reservation or slot not found, or validation fails
     * @throws RuntimeException if the DynamoDB transaction fails
     */
    public void expireHold(@NotNull String reservationId) {
        RoomReservation reservation = reservationsDao.getReservationById(reservationId);
        long now = System.currentTimeMillis();
        //Input Validation
        if(reservation == null) {
            throw new IllegalArgumentException("Reservation not found with id " + reservationId);
        }
        EscapeRoomSlot slot = slotsDao.getSlot(reservation.getRoomId(), reservation.getSlotId());
        if(slot == null) {
            throw new IllegalArgumentException("Slot not found with id " + reservation.getSlotId());
        }

        // Eligibility check
        if (!isValidToExpire(reservation, slot, now)) {
            logger.error("ExpireHold Validation failed: reservation {}, slot {} ", reservation.toString(),  slot.toString());
            throw new IllegalArgumentException("Reservation not valid to expire");
        }

        List<TransactWriteItem> transactItems = List.of(
                slotsDao.buildReleaseSlotTransactItem(slot, now),
                reservationsDao.buildExpireReservationTransactItem(reservation, now)
        );

        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(transactItems).build());
        } catch (TransactionCanceledException e) {
            logger.error("Transaction with reservation expiry: id {}, ErrorMessage: {}", reservationId, e.getMessage());
            throw new RuntimeException("Cancellation failed. {} " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error expiring reservation {}: {}", reservationId, e.getMessage(), e);
            throw new RuntimeException("Unexpected error: " + e.getMessage());
        }
    }


    /**
     * Retrieve a reservation by its unique reservationId.
     *
     * @param reservationId the reservation identifier (must not be null)
     * @return the RoomReservation object if found
     * @throws RuntimeException if the underlying DAO call fails
     */
    public RoomReservation getReservationById(@NotNull String reservationId) {
        try {
            return reservationsDao.getReservationById(reservationId);
        } catch (TransactionCanceledException e) {
            logger.error("Failed to fetch reservation for id {}. ErrorMessage: {}", reservationId, e.getMessage());
            throw new RuntimeException("getReservationById failed. {} " + e.getMessage());
        }
    }

// ============ Validation Helpers ===================================================
    /**
     * Validates whether a reservation is eligible to have its HOLD expired.
     * @param reservation the reservation record
     * @param slot the escape room slot record
     * @param now the current timestamp
     * @return true if the reservation hold can be expired, false otherwise
     */
    private boolean isValidToExpire(@NotNull RoomReservation reservation, @NotNull EscapeRoomSlot slot, long now) {
        boolean reservationHoldExpired =
                reservation.getReservationStatus() == ReservationStatus.HOLD &&
                        reservation.getHoldExpiresAt() <= now;
        boolean slotValid =
                slot.getSlotStatus() == SlotStatus.HOLD &&
                        Objects.equals(reservation.getReservationId(), slot.getCurrentReservationId());

        return reservationHoldExpired && slotValid;
    }

    /**
     * Validates if a reservation can be cancelled by the user.
     * Rules:
     *  - Reservation status must be HOLD or CONFIRMED
     *  - Slot status must match reservation status (HOLD or BOOKED)
     *  - Reservation ID must match the slot's currentReservationId
     *
     * @param reservation the reservation object
     * @param slot the escape room slot object
     * @return true if reservation can be cancelled, false otherwise
     */
    private boolean isValidForCancellation(@NotNull RoomReservation reservation, @NotNull EscapeRoomSlot slot) {
        if (reservation == null || slot == null) return false;
        boolean validStatus = reservation.getReservationStatus() == ReservationStatus.HOLD
                || reservation.getReservationStatus() == ReservationStatus.CONFIRMED;
        boolean validSlot = slot.getSlotStatus() == SlotStatus.HOLD
                || slot.getSlotStatus() == SlotStatus.BOOKED;
        boolean idMatch = Objects.equals(reservation.getReservationId(), slot.getCurrentReservationId());
        return validStatus && validSlot && idMatch;
    }

    /**
     * Validates whether a reservation and its corresponding slot are eligible for confirmation.
     *  1. The reservationId Matches between to records
     *  2. Only HOLD status is eligible to CONFIRM
     * @param reservation the reservation object
     * @param slot the corresponding escape room slot
     * @return true if the reservation can be confirmed; false otherwise
     */
    private boolean isValidForConfirmation(@NotNull RoomReservation reservation, @NotNull EscapeRoomSlot slot) {
        return Objects.equals(slot.getCurrentReservationId(), reservation.getReservationId())
                && reservation.getReservationStatus() == ReservationStatus.HOLD
                && slot.getSlotStatus() == SlotStatus.HOLD;
    }

    /**
     * Checks if an escape room slot is available for reservation.
     *
     * @param slot the EscapeRoomSlot object
     * @return true if the slot exists, is AVAILABLE, and not currently reserved; false otherwise
     */
    private boolean isSlotAvailableForReservation(EscapeRoomSlot slot) {
        return slot != null && slot.getCurrentReservationId() == null
                && slot.getSlotStatus() == SlotStatus.AVAILABLE;

    }
}
