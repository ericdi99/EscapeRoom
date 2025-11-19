package com.escaperoom.model;

import java.time.Instant;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

import static com.escaperoom.utils.StringUtil.isNEmptyString;

/**
 * RoomReservation represents a booking of an escape room slot.
 * <p>
 * This class is used for holding, confirming, cancelling, and expiring reservations.
 */
@Data
@NoArgsConstructor
public class RoomReservation {

    /** Unique reservation ID */
    @JsonProperty("reservationId")
    private String reservationId;

    /** Room identifier */
    @JsonProperty("roomId")
    private String roomId;

    /** Slot identifier in format YYYY-MM-DD#HH */
    @JsonProperty("slotId")
    private String slotId;

    /** User who made the reservation */
    @JsonProperty("userId")
    private String userId;

    /** Current reservation status: HOLD | CONFIRMED | CANCELLED | EXPIRED */
    @JsonProperty("reservationStatus")
    private ReservationStatus reservationStatus;

    /** Creation timestamp (epoch seconds) */
    @JsonProperty("createdAt")
    private long createdAt = Instant.now().getEpochSecond();

    /** Hold expiration timestamp (epoch seconds) */
    @JsonProperty("holdExpiresAt")
    private long holdExpiresAt;

    /** Last update timestamp (epoch seconds) */
    @JsonProperty("lastUpdated")
    private long lastUpdated = Instant.now().getEpochSecond();


    /**
     * Custom constructor for creating a new reservation with HOLD status
     */
    public RoomReservation(String reservationId, String roomId, String slotId, String userId, ReservationStatus reservationStatus) {
        this.reservationId = reservationId;
        this.roomId = roomId;
        this.slotId = slotId;
        this.userId = userId;
        this.reservationStatus = reservationStatus;
        this.createdAt = Instant.now().getEpochSecond();
        this.lastUpdated = this.createdAt;
    }

    /**
     * Returns reservationStatus as String, for DynamoDB storage.
     */
    @JsonIgnore
    public String getReservationStatusStr() {
        return reservationStatus != null ? reservationStatus.name() : null;
    }

    /**
     * Simple validation to check required fields are non-null/non-empty.
     * Throws IllegalArgumentException if any required field is missing.
     */
    @JsonIgnore
    public void validate() {
        if (isNEmptyString(reservationId)
                || isNEmptyString(roomId)
                || isNEmptyString(slotId)
                || isNEmptyString(userId)
                || reservationStatus == null) {
            throw new IllegalArgumentException("Object validation failed");
        }
    }

}