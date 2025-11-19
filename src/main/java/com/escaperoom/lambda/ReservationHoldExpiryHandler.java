package com.escaperoom.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.escaperoom.model.ReservationStatus;
import com.escaperoom.model.RoomReservation;
import com.escaperoom.reservations.ReservationController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * AWS Lambda handler responsible for processing hold-expiry events from Step Functions.
 * The lambda will expire a reservation in hold status whose holding period expires
 */
public class ReservationHoldExpiryHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger logger = LoggerFactory.getLogger(ReservationHoldExpiryHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReservationController reservationController;

    public ReservationHoldExpiryHandler() {
        this.reservationController = new ReservationController();
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        logger.info("Received StepFunction input: {}", input);

        try {
            String reservationId = (String) input.get("reservationId");
            if (reservationId == null || reservationId.isEmpty()) {
                throw new IllegalArgumentException("Missing required field: reservationId");
            }

            logger.info("Expiring hold for reservationId={}", reservationId);
            RoomReservation reservation = reservationController.getReservationById(reservationId);
            if(reservation == null) {
                throw new IllegalArgumentException("No reservation found with id " + reservationId);
            }
            if(reservation.getReservationStatus() != ReservationStatus.HOLD) {
                logger.info("Reservation: {} no longer in HOLD status, ignore", reservationId);
            } else {
                reservationController.expireHold(reservationId);
                logger.info("Successfully expire reservation: {}", reservationId);
            }

            return Map.of(
                    "reservationId", reservationId,
                    "status", "COMPLETED"
            );

        }  catch (IllegalArgumentException ie) {
            // Expected failures — DO NOT retry
            return Map.of("status", "ERROR_INPUT", "message", ie.getMessage());

        } catch (Exception e) {
            // Unexpected/system failure — allow Step Functions to retry by rethrowing
            logger.error("Failed to expire hold", e);
            throw e;
        }
    }
}
