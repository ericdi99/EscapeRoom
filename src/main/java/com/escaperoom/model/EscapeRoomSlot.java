package com.escaperoom.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import static com.escaperoom.utils.StringUtil.isNEmptyString;

/**
 * Represents a time slot for an Escape Room in database
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EscapeRoomSlot {

    private String roomId;
    private String slotId; // e.g., "2025-11-19#12"
    private SlotStatus slotStatus; // AVAILABLE, HOLD, BOOKED
    private String currentReservationId; // can be null
    private long lastUpdated; // epoch seconds
    private long createdTime; // epoch seconds
    // future fields: price etc

    /**
     * Custom constructor for new slot with status as String.
     */
    public EscapeRoomSlot(String roomId, String slotId, String status, long createdTime) {
        this.roomId = roomId;
        this.slotId = slotId;
        this.slotStatus = SlotStatus.valueOf(status);
        this.currentReservationId = null;
        this.createdTime = createdTime;
        this.lastUpdated = createdTime;
        validate();
    }

    /**
     * Validate that required fields are not null or invalid.
     */
    public void validate() {
        if (isNEmptyString(roomId) || isNEmptyString(slotId)
                || slotStatus == null) {
            throw new IllegalArgumentException("createdTime must be a positive epoch seconds value");
        }
        // currentReservationId can be null, no validation needed
    }

    /**
     * Factory method to create a new slot with optional reservationId.
     */
    public static EscapeRoomSlot createNew(String roomId, String slotId, String status, String reservationId) {
        long now = Instant.now().getEpochSecond();
        EscapeRoomSlot slot = new EscapeRoomSlot(roomId, slotId, status, now);
        if (reservationId != null) {
            slot.setCurrentReservationId(reservationId);
        }
        return slot;
    }
}