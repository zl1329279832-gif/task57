package com.wangpeng.bms.service.impl;

import com.wangpeng.bms.mapper.ReservationMapper;
import com.wangpeng.bms.model.Reservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ReservationServiceImplTest {

    @Mock
    private ReservationMapper reservationMapper;

    @InjectMocks
    private ReservationServiceImpl reservationService;

    private Reservation makeReservation(Integer id, Integer userid, Integer bookid, byte status, int position) {
        Reservation r = new Reservation();
        r.setReservationid(id);
        r.setUserid(userid);
        r.setBookid(bookid);
        r.setStatus(status);
        r.setQueueposition(position);
        r.setCreatetime(new Date());
        return r;
    }

    @BeforeEach
    void setUp() {
        // 每个测试前重置
    }

    // ==================== createReservation ====================

    @Test
    void createReservation_emptyQueue_positionIsOne() {
        when(reservationMapper.selectActiveByBookAndUser(1, 100)).thenReturn(null);
        when(reservationMapper.getMaxQueuePosition(1)).thenReturn(null);
        when(reservationMapper.insertSelective(any())).thenReturn(1);

        Reservation result = reservationService.createReservation(100, 1);

        assertNotNull(result);
        assertEquals(1, result.getQueueposition());
        assertEquals(Reservation.STATUS_WAITING, result.getStatus().byteValue());
        assertEquals(100, result.getUserid());
        assertEquals(1, result.getBookid());
        verify(reservationMapper).insertSelective(any());
    }

    @Test
    void createReservation_existingQueue_positionIncrements() {
        when(reservationMapper.selectActiveByBookAndUser(1, 100)).thenReturn(null);
        when(reservationMapper.getMaxQueuePosition(1)).thenReturn(3);
        when(reservationMapper.insertSelective(any())).thenReturn(1);

        Reservation result = reservationService.createReservation(100, 1);

        assertNotNull(result);
        assertEquals(4, result.getQueueposition());
        verify(reservationMapper).insertSelective(any());
    }

    @Test
    void createReservation_duplicate_returnsNull() {
        Reservation existing = makeReservation(1, 100, 1, Reservation.STATUS_WAITING, 1);
        when(reservationMapper.selectActiveByBookAndUser(1, 100)).thenReturn(existing);

        Reservation result = reservationService.createReservation(100, 1);

        assertNull(result);
        verify(reservationMapper, never()).insertSelective(any());
    }

    // ==================== cancelReservation ====================

    @Test
    void cancelReservation_waiting_success() {
        Reservation r = makeReservation(1, 100, 1, Reservation.STATUS_WAITING, 1);
        when(reservationMapper.selectByPrimaryKey(1)).thenReturn(r);
        when(reservationMapper.updateByPrimaryKeySelective(any())).thenReturn(1);

        Reservation result = reservationService.cancelReservation(1);

        assertNotNull(result);
        assertEquals(Reservation.STATUS_CANCELLED, result.getStatus().byteValue());
        verify(reservationMapper).updateByPrimaryKeySelective(any());
    }

    @Test
    void cancelReservation_reserved_success() {
        Reservation r = makeReservation(1, 100, 1, Reservation.STATUS_RESERVED, 1);
        r.setReservetime(new Date());
        r.setExpirytime(new Date(System.currentTimeMillis() + 3600000));
        when(reservationMapper.selectByPrimaryKey(1)).thenReturn(r);
        when(reservationMapper.updateByPrimaryKeySelective(any())).thenReturn(1);

        Reservation result = reservationService.cancelReservation(1);

        assertNotNull(result);
        assertEquals(Reservation.STATUS_CANCELLED, result.getStatus().byteValue());
    }

    @Test
    void cancelReservation_notFound_returnsNull() {
        when(reservationMapper.selectByPrimaryKey(999)).thenReturn(null);

        Reservation result = reservationService.cancelReservation(999);

        assertNull(result);
        verify(reservationMapper, never()).updateByPrimaryKeySelective(any());
    }

    @Test
    void cancelReservation_fulfilled_returnsNull() {
        Reservation r = makeReservation(1, 100, 1, Reservation.STATUS_FULFILLED, 1);
        when(reservationMapper.selectByPrimaryKey(1)).thenReturn(r);

        Reservation result = reservationService.cancelReservation(1);

        assertNull(result);
        verify(reservationMapper, never()).updateByPrimaryKeySelective(any());
    }

    // ==================== getQueueForBook ====================

    @Test
    void getQueueForBook_returnsList() {
        List<Reservation> queue = Arrays.asList(
                makeReservation(1, 100, 1, Reservation.STATUS_WAITING, 1),
                makeReservation(2, 101, 1, Reservation.STATUS_WAITING, 2)
        );
        when(reservationMapper.selectWaitingByBook(1)).thenReturn(queue);

        List<Reservation> result = reservationService.getQueueForBook(1);

        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getQueueposition());
        assertEquals(2, result.get(1).getQueueposition());
    }

    @Test
    void getQueueForBook_emptyQueue_returnsEmptyList() {
        when(reservationMapper.selectWaitingByBook(1)).thenReturn(Collections.emptyList());

        List<Reservation> result = reservationService.getQueueForBook(1);

        assertTrue(result.isEmpty());
    }

    // ==================== consumeReservation ====================

    @Test
    void consumeReservation_success() {
        Reservation r = makeReservation(1, 100, 1, Reservation.STATUS_RESERVED, 1);
        when(reservationMapper.selectByPrimaryKey(1)).thenReturn(r);
        when(reservationMapper.updateByPrimaryKeySelective(any())).thenReturn(1);

        Reservation result = reservationService.consumeReservation(1);

        assertNotNull(result);
        assertEquals(Reservation.STATUS_FULFILLED, result.getStatus().byteValue());
    }

    @Test
    void consumeReservation_notFound_returnsNull() {
        when(reservationMapper.selectByPrimaryKey(999)).thenReturn(null);

        Reservation result = reservationService.consumeReservation(999);

        assertNull(result);
    }

    // ==================== triggerReservation ====================

    @Test
    void triggerReservation_withWaiting_activatesNext() {
        Reservation next = makeReservation(1, 100, 1, Reservation.STATUS_WAITING, 1);
        when(reservationMapper.selectNextWaiting(1)).thenReturn(next);
        when(reservationMapper.updateByPrimaryKeySelective(any())).thenReturn(1);

        Reservation result = reservationService.triggerReservation(1);

        assertNotNull(result);
        assertEquals(Reservation.STATUS_RESERVED, result.getStatus().byteValue());
        assertNotNull(result.getReservetime());
        assertNotNull(result.getExpirytime());
        verify(reservationMapper).updateByPrimaryKeySelective(any());
    }

    @Test
    void triggerReservation_emptyQueue_returnsNull() {
        when(reservationMapper.selectNextWaiting(1)).thenReturn(null);

        Reservation result = reservationService.triggerReservation(1);

        assertNull(result);
        verify(reservationMapper, never()).updateByPrimaryKeySelective(any());
    }

    @Test
    void triggerReservation_holdWindow72Hours() {
        Reservation next = makeReservation(1, 100, 1, Reservation.STATUS_WAITING, 1);
        when(reservationMapper.selectNextWaiting(1)).thenReturn(next);
        when(reservationMapper.updateByPrimaryKeySelective(any())).thenReturn(1);

        Reservation result = reservationService.triggerReservation(1);

        assertNotNull(result);
        long diff = result.getExpirytime().getTime() - result.getReservetime().getTime();
        assertEquals(72L * 60 * 60 * 1000, diff);
    }

    // ==================== expireAndAdvance ====================

    @Test
    void expireAndAdvance_expired_advancesToNext() {
        Reservation expired = makeReservation(1, 100, 1, Reservation.STATUS_RESERVED, 1);
        expired.setReservetime(new Date(System.currentTimeMillis() - 80 * 3600000L));
        expired.setExpirytime(new Date(System.currentTimeMillis() - 8 * 3600000L));

        Reservation nextWaiting = makeReservation(2, 101, 1, Reservation.STATUS_WAITING, 2);

        when(reservationMapper.selectActiveByBookAndUser(1, null)).thenReturn(expired);
        when(reservationMapper.updateByPrimaryKeySelective(any())).thenReturn(1);
        when(reservationMapper.selectNextWaiting(1)).thenReturn(nextWaiting);

        Reservation result = reservationService.expireAndAdvance(1);

        assertNotNull(result);
        assertEquals(101, result.getUserid());
        assertEquals(Reservation.STATUS_RESERVED, result.getStatus().byteValue());
        verify(reservationMapper, times(2)).updateByPrimaryKeySelective(any());
    }

    @Test
    void expireAndAdvance_notExpired_returnsCurrent() {
        Reservation active = makeReservation(1, 100, 1, Reservation.STATUS_RESERVED, 1);
        active.setReservetime(new Date());
        active.setExpirytime(new Date(System.currentTimeMillis() + 3600000L));

        when(reservationMapper.selectActiveByBookAndUser(1, null)).thenReturn(active);

        Reservation result = reservationService.expireAndAdvance(1);

        assertNotNull(result);
        assertEquals(100, result.getUserid());
        assertEquals(Reservation.STATUS_RESERVED, result.getStatus().byteValue());
        verify(reservationMapper, never()).updateByPrimaryKeySelective(any());
    }

    @Test
    void expireAndAdvance_noActive_returnsNull() {
        when(reservationMapper.selectActiveByBookAndUser(1, null)).thenReturn(null);

        Reservation result = reservationService.expireAndAdvance(1);

        assertNull(result);
    }

    @Test
    void expireAndAdvance_expiredNoNext_returnsNull() {
        Reservation expired = makeReservation(1, 100, 1, Reservation.STATUS_RESERVED, 1);
        expired.setReservetime(new Date(System.currentTimeMillis() - 80 * 3600000L));
        expired.setExpirytime(new Date(System.currentTimeMillis() - 8 * 3600000L));

        when(reservationMapper.selectActiveByBookAndUser(1, null)).thenReturn(expired);
        when(reservationMapper.updateByPrimaryKeySelective(any())).thenReturn(1);
        when(reservationMapper.selectNextWaiting(1)).thenReturn(null);

        Reservation result = reservationService.expireAndAdvance(1);

        assertNull(result);
    }

    // ==================== getActiveReservation ====================

    @Test
    void getActiveReservation_reserved_returnsIt() {
        Reservation r = makeReservation(1, 100, 1, Reservation.STATUS_RESERVED, 1);
        r.setReservetime(new Date());
        r.setExpirytime(new Date(System.currentTimeMillis() + 3600000));
        when(reservationMapper.selectActiveByBookAndUser(1, null)).thenReturn(r);

        Reservation result = reservationService.getActiveReservation(1);

        assertNotNull(result);
        assertEquals(Reservation.STATUS_RESERVED, result.getStatus().byteValue());
    }

    @Test
    void getActiveReservation_onlyWaiting_returnsNull() {
        Reservation r = makeReservation(1, 100, 1, Reservation.STATUS_WAITING, 1);
        when(reservationMapper.selectActiveByBookAndUser(1, null)).thenReturn(r);

        Reservation result = reservationService.getActiveReservation(1);

        assertNull(result);
    }

    @Test
    void getActiveReservation_none_returnsNull() {
        when(reservationMapper.selectActiveByBookAndUser(1, null)).thenReturn(null);

        Reservation result = reservationService.getActiveReservation(1);

        assertNull(result);
    }

    // ==================== adjustPosition ====================

    @Test
    void adjustPosition_swap_success() {
        Reservation r1 = makeReservation(1, 100, 1, Reservation.STATUS_WAITING, 1);
        Reservation r2 = makeReservation(2, 101, 1, Reservation.STATUS_WAITING, 2);

        when(reservationMapper.selectByPrimaryKey(1)).thenReturn(r1);
        when(reservationMapper.selectWaitingByBook(1)).thenReturn(Arrays.asList(r1, r2));
        when(reservationMapper.updateByPrimaryKeySelective(any())).thenReturn(1);

        Reservation result = reservationService.adjustPosition(1, 2);

        assertNotNull(result);
        assertEquals(2, result.getQueueposition());
        verify(reservationMapper, times(2)).updateByPrimaryKeySelective(any());
    }

    @Test
    void adjustPosition_directNoTarget_success() {
        Reservation r1 = makeReservation(1, 100, 1, Reservation.STATUS_WAITING, 1);

        when(reservationMapper.selectByPrimaryKey(1)).thenReturn(r1);
        when(reservationMapper.selectWaitingByBook(1)).thenReturn(Collections.singletonList(r1));
        when(reservationMapper.updateByPrimaryKeySelective(any())).thenReturn(1);

        Reservation result = reservationService.adjustPosition(1, 5);

        assertNotNull(result);
        assertEquals(5, result.getQueueposition());
        verify(reservationMapper, times(1)).updateByPrimaryKeySelective(any());
    }

    @Test
    void adjustPosition_noop_samePosition() {
        Reservation r1 = makeReservation(1, 100, 1, Reservation.STATUS_WAITING, 3);

        when(reservationMapper.selectByPrimaryKey(1)).thenReturn(r1);

        Reservation result = reservationService.adjustPosition(1, 3);

        assertNotNull(result);
        assertEquals(3, result.getQueueposition());
        verify(reservationMapper, never()).updateByPrimaryKeySelective(any());
    }

    @Test
    void adjustPosition_notFound_returnsNull() {
        when(reservationMapper.selectByPrimaryKey(999)).thenReturn(null);

        Reservation result = reservationService.adjustPosition(999, 1);

        assertNull(result);
    }

    @Test
    void adjustPosition_notWaiting_returnsNull() {
        Reservation r = makeReservation(1, 100, 1, Reservation.STATUS_RESERVED, 1);
        when(reservationMapper.selectByPrimaryKey(1)).thenReturn(r);

        Reservation result = reservationService.adjustPosition(1, 2);

        assertNull(result);
        verify(reservationMapper, never()).updateByPrimaryKeySelective(any());
    }

    // ==================== getUserReservations ====================

    @Test
    void getUserReservations_returnsList() {
        List<Reservation> list = Arrays.asList(
                makeReservation(1, 100, 1, Reservation.STATUS_WAITING, 1),
                makeReservation(2, 100, 2, Reservation.STATUS_FULFILLED, 1)
        );
        when(reservationMapper.selectByUser(100)).thenReturn(list);

        List<Reservation> result = reservationService.getUserReservations(100);

        assertEquals(2, result.size());
    }

    @Test
    void getUserReservations_empty_returnsEmptyList() {
        when(reservationMapper.selectByUser(100)).thenReturn(Collections.emptyList());

        List<Reservation> result = reservationService.getUserReservations(100);

        assertTrue(result.isEmpty());
    }
}
