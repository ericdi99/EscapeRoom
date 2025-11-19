package testdrivers;

import com.escaperoom.dao.EscapeRoomSlotsDao;
import com.escaperoom.dao.RoomReservationsDao;
import com.escaperoom.model.EscapeRoomSlot;
import com.escaperoom.model.RoomReservation;
import com.escaperoom.reservations.ReservationController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * !!! This test driver assume the AWS access key and credential are installed properly
 */
public class TestRoomReservationService {

    private static final Logger logger = LoggerFactory.getLogger(TestRoomReservationService.class);

    private static final ReservationController service = new ReservationController();
    private static final EscapeRoomSlotsDao slotDao = new EscapeRoomSlotsDao();
    private static final RoomReservationsDao reservationDao = new RoomReservationsDao();

    public static void main(String[] args) {
        try {
            String roomId = "ROOM-1";
            String slotId = "2025-11-19#12";
            String userId = "user-123";

            RoomReservation rev = createReservationTest(roomId, slotId, userId);
            confirmReservationTest(rev.getReservationId());
            cancelReservationTest(rev.getReservationId());

        } catch (Exception e) {
            logger.error("TestDriver encountered error: {}", e.getMessage(), e);
        } finally {
            // Close DynamoDB client if necessary
        }
    }

    private static RoomReservation createReservationTest(String roomId, String slotId, String userId) {
        logger.info("=== CREATE RESERVATION TEST ===");
        EscapeRoomSlot slotBefore = slotDao.getSlot(roomId, slotId);
        logger.info("Slot before create: {}", slotBefore);
        RoomReservation reservation = service.createReservation(roomId, slotId, userId);
        logger.info("Create reservation success: {}", reservation.toString());
        EscapeRoomSlot slotAfter = slotDao.getSlot(roomId, slotId);
        logger.info("Slot after create: {}", slotAfter);
        logger.info("Created reservation: {}", reservation);
        return reservation;
    }
    private static void confirmReservationTest(String reservationId) {
        logger.info("=== CONFIRM RESERVATION TEST ===");

        RoomReservation reservationBefore = reservationDao.getReservationById(reservationId);
        logger.info("Reservation before confirm: {}", reservationBefore);
        service.confirmReservation(reservationId);

        RoomReservation reservationAfter = reservationDao.getReservationById(reservationId);
        EscapeRoomSlot slotAfter = slotDao.getSlot(reservationAfter.getRoomId(), reservationAfter.getSlotId());
        logger.info("Reservation after confirm: {}", reservationAfter);
        logger.info("Slot after confirm: {}", slotAfter);
    }

    private static void cancelReservationTest(String reservationId) {
        logger.info("=== CANCEL RESERVATION TEST ===");

        RoomReservation reservationBefore = reservationDao.getReservationById(reservationId);
        logger.info("Reservation before cancel: {}", reservationBefore);

        service.cancelReservation(reservationId);


        RoomReservation reservationAfter = reservationDao.getReservationById(reservationId);
        logger.info("Reservation after cancel: {}", reservationAfter);
        EscapeRoomSlot slotAfter = slotDao.getSlot(reservationAfter.getRoomId(), reservationAfter.getSlotId());
        logger.info("Slot after cancel: {}", slotAfter);
    }
}