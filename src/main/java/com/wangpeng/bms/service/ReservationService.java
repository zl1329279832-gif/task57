package com.wangpeng.bms.service;

import com.wangpeng.bms.model.Reservation;

import java.util.List;

public interface ReservationService {

    Reservation createReservation(Integer userid, Integer bookid);

    Reservation cancelReservation(Integer reservationid);

    List<Reservation> getQueueForBook(Integer bookid);

    List<Reservation> getUserReservations(Integer userid);

    Reservation consumeReservation(Integer reservationid);

    Reservation triggerReservation(Integer bookid);

    Reservation expireAndAdvance(Integer bookid);

    Reservation getActiveReservation(Integer bookid);

    Reservation adjustPosition(Integer reservationid, Integer newPosition);
}
