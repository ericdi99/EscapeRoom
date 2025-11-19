package com.escaperoom.lambda;

import com.escaperoom.reservations.ReservationController;
import com.escaperoom.model.RoomReservation;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Lambda entrypoint for the Escape Room Booking API exposed via API Gateway.
 *
 * <p>This handler routes HTTP requests to ReservationController methods based on
 * both HTTP method and path prefix matching. It supports the following operations:
 *   - GET    /reservations/getReservationById/{reservationId}
 *   - POST   /reservations/confirm/{reservationId}
 *   - POST   /reservations/cancel/{reservationId}
 *   - POST   /reservations/create   (body contains userId, roomId, slotId)
 *
 * <p>Response:
 *   - 200 OK for successful operations
 *   - 201 Created for reservation creation
 *   - 400 Bad Request for validation errors
 *   - 404 Not Found for path mismatches
 *   - 500 Internal Server Error for unexpected failures
 *   */
public class EscapeRoomAPIHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger logger = LoggerFactory.getLogger(EscapeRoomAPIHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ReservationController controller = new ReservationController();

    // Supported API Paths
    private static final String GET_RESERVATION_BY_USER = "/reservations/getReservationByUserId/";
    private static final String GET_RESERVATION_BY_ID = "/reservations/getReservationById/";
    private static final String POST_CONFIRM = "/reservations/confirm/";
    private static final String POST_CANCEL = "/reservations/cancel/";
    private static final String POST_CREATE = "/reservations/create";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        String path = event.getPath();
        String httpMethod = event.getHttpMethod();
        Map<String, String> pathParams = event.getPathParameters() != null ? event.getPathParameters() : Map.of();
        logger.info("Received API request: method={}, path={}, requestId={}",
                httpMethod, path,context.getAwsRequestId());
        try {
            if ("GET".equalsIgnoreCase(httpMethod)) {
                if (path.startsWith(GET_RESERVATION_BY_ID)) {
                    return handleGetReservationById(extractLastPathPart(path));
                }
            } else if ("POST".equalsIgnoreCase(httpMethod)) {
                if (path.startsWith(POST_CONFIRM)) {
                    return handleConfirmReservation(extractLastPathPart(path));
                } else if (path.startsWith(POST_CANCEL)) {
                    return handleCancelReservation(extractLastPathPart(path));
                }  else if (path.equals(POST_CREATE)) {
                    return handleCreateReservation(event.getBody());
                }
            }
            return respond(404, Map.of("error", "Unsupported path or method"));
        } catch (IllegalArgumentException e) {
            logger.warn("Validation error: {}", e.getMessage());
            return respond(400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Internal server error", e);
            return respond(500, Map.of("error", "Internal server error"));
        }
    }

    // ====================== Dedicated Handlers ======================

    private APIGatewayProxyResponseEvent handleGetReservationById(String reservationId) {
        logger.info("Fetching reservation by id: {}", reservationId);
        if (reservationId == null) {
            throw new IllegalArgumentException("Missing required fields: reservationId");
        }
        RoomReservation reservation = controller.getReservationById(reservationId);
        if(reservation == null) {
            throw new IllegalArgumentException("No reservation found");
        }
        return respond(200, reservation);
    }

    private APIGatewayProxyResponseEvent handleConfirmReservation(String reservationId) {
        logger.info("Confirming reservation: {}", reservationId);
        if (reservationId == null) {
            throw new IllegalArgumentException("Missing required fields: reservationId");
        }
        controller.confirmReservation(reservationId);
        return respond(200, Map.of("message", "Reservation confirmed"));
    }

    private APIGatewayProxyResponseEvent handleCancelReservation(String reservationId) {
        logger.info("Cancelling reservation: {}", reservationId);
        if (reservationId == null) {
            throw new IllegalArgumentException("Missing required fields: reservationId");
        }
        controller.cancelReservation(reservationId);
        return respond(200, Map.of("message", "Reservation cancelled"));
    }

    private APIGatewayProxyResponseEvent handleReleaseHold(String reservationId) {
        logger.info("Releasing hold for reservation: {}", reservationId);
        if (reservationId == null) {
            throw new IllegalArgumentException("Missing required fields: reservationId");
        }
        controller.expireHold(reservationId);
        return respond(200, Map.of("message", "Reservation hold released"));
    }

    private APIGatewayProxyResponseEvent handleCreateReservation(String body) throws Exception {
        logger.info("Creating reservation with body: {}", body);
        Map<String, String> payload = MAPPER.readValue(body, new TypeReference<Map<String, String>>() {});
        String userId = payload.get("userId");
        String roomId = payload.get("roomId");
        String slotId = payload.get("slotId");

        if (userId == null || roomId == null || slotId == null) {
            throw new IllegalArgumentException("Missing required fields: userId, roomId, slotId");
        }

        RoomReservation reservation = controller.createReservation(roomId, slotId, userId);

        return respond(201, reservation);
    }

    // ====================== Request & Response Helper ======================
    private String extractLastPathPart(String path) {
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String[] parts = path.split("/");
        return parts[parts.length - 1];
    }

    private APIGatewayProxyResponseEvent respond(int statusCode, Object bodyObj) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withBody(MAPPER.writeValueAsString(bodyObj))
                    .withHeaders(Map.of("Content-Type", "application/json"));
        } catch (Exception e) {
            logger.error("Serialization error", e);
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"message\":\"Serialization error\"}");
        }
    }
}
