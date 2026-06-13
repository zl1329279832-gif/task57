package com.wangpeng.bms.web;

import com.wangpeng.bms.model.BookInfo;
import com.wangpeng.bms.model.Borrow;
import com.wangpeng.bms.model.Reservation;
import com.wangpeng.bms.service.BookInfoService;
import com.wangpeng.bms.service.BorrowService;
import com.wangpeng.bms.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ReservationFlowTest {

    @Mock
    private ReservationService reservationService;
    @Mock
    private BookInfoService bookInfoService;
    @Mock
    private BorrowService borrowService;

    @InjectMocks
    private ReservationController controller;

    private BookInfo availableBook;
    private BookInfo borrowedBook;

    @BeforeEach
    void setUp() {
        availableBook = new BookInfo();
        availableBook.setBookid(1);
        availableBook.setBookname("Test Book");
        availableBook.setIsborrowed((byte) 0);

        borrowedBook = new BookInfo();
        borrowedBook.setBookid(1);
        borrowedBook.setBookname("Test Book");
        borrowedBook.setIsborrowed((byte) 1);
    }

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

    // ==================== 创建预约 ====================

    @Test
    void createReservation_bookBorrowed_success() {
        when(bookInfoService.queryBookInfoById(1)).thenReturn(borrowedBook);
        Reservation r = makeReservation(1, 100, 1, Reservation.STATUS_WAITING, 1);
        when(reservationService.createReservation(100, 1)).thenReturn(r);

        Map<String, Object> result = controller.createReservation(100, 1);

        assertEquals(200, result.get("status"));
        assertNotNull(result.get("data"));
    }

    @Test
    void createReservation_bookAvailable_rejected() {
        when(bookInfoService.queryBookInfoById(1)).thenReturn(availableBook);

        Map<String, Object> result = controller.createReservation(100, 1);

        assertEquals(420, result.get("status"));
        verify(reservationService, never()).createReservation(anyInt(), anyInt());
    }

    @Test
    void createReservation_bookNotFound_rejected() {
        when(bookInfoService.queryBookInfoById(999)).thenReturn(null);

        Map<String, Object> result = controller.createReservation(100, 999);

        assertEquals(420, result.get("status"));
    }

    @Test
    void createReservation_duplicate_rejected() {
        when(bookInfoService.queryBookInfoById(1)).thenReturn(borrowedBook);
        when(reservationService.createReservation(100, 1)).thenReturn(null);

        Map<String, Object> result = controller.createReservation(100, 1);

        assertEquals(420, result.get("status"));
    }

    // ==================== 取消预约 ====================

    @Test
    void cancelReservation_success() {
        Reservation r = makeReservation(1, 100, 1, Reservation.STATUS_CANCELLED, 1);
        when(reservationService.cancelReservation(1)).thenReturn(r);

        Map<String, Object> result = controller.cancelReservation(1);

        assertEquals(200, result.get("status"));
    }

    @Test
    void cancelReservation_notFound_fails() {
        when(reservationService.cancelReservation(999)).thenReturn(null);

        Map<String, Object> result = controller.cancelReservation(999);

        assertEquals(420, result.get("status"));
    }

    // ==================== 借书（预约感知） ====================

    @Test
    void borrowBook_noReservation_success() {
        when(bookInfoService.queryBookInfoById(1)).thenReturn(availableBook);
        when(reservationService.getActiveReservation(1)).thenReturn(null);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);
        when(borrowService.addBorrow2(any())).thenReturn(1);

        Integer result = controller.borrowBook(100, 1);

        assertEquals(1, result);
        verify(bookInfoService).updateBookInfo(any());
        verify(borrowService).addBorrow2(any());
    }

    @Test
    void borrowBook_bookAlreadyBorrowed_returns0() {
        when(bookInfoService.queryBookInfoById(1)).thenReturn(borrowedBook);

        Integer result = controller.borrowBook(100, 1);

        assertEquals(0, result);
    }

    @Test
    void borrowBook_withReservation_correctUser_success() {
        Reservation active = makeReservation(1, 100, 1, Reservation.STATUS_RESERVED, 1);
        active.setReservetime(new Date());
        active.setExpirytime(new Date(System.currentTimeMillis() + 3600000));

        when(bookInfoService.queryBookInfoById(1)).thenReturn(availableBook);
        when(reservationService.getActiveReservation(1)).thenReturn(active);
        when(reservationService.expireAndAdvance(1)).thenReturn(active);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);
        when(borrowService.addBorrow2(any())).thenReturn(1);
        when(reservationService.consumeReservation(1)).thenReturn(active);

        Integer result = controller.borrowBook(100, 1);

        assertEquals(1, result);
        verify(reservationService).consumeReservation(1);
    }

    @Test
    void borrowBook_withReservation_wrongUser_returns0() {
        Reservation active = makeReservation(1, 200, 1, Reservation.STATUS_RESERVED, 1);
        active.setReservetime(new Date());
        active.setExpirytime(new Date(System.currentTimeMillis() + 3600000));

        when(bookInfoService.queryBookInfoById(1)).thenReturn(availableBook);
        when(reservationService.getActiveReservation(1)).thenReturn(active);
        when(reservationService.expireAndAdvance(1)).thenReturn(active);

        Integer result = controller.borrowBook(100, 1);

        assertEquals(0, result);
        verify(borrowService, never()).addBorrow2(any());
    }

    @Test
    void borrowBook_reservationExpired_noNext_allowsBorrow() {
        Reservation expired = makeReservation(1, 200, 1, Reservation.STATUS_RESERVED, 1);
        expired.setReservetime(new Date(System.currentTimeMillis() - 80 * 3600000L));
        expired.setExpirytime(new Date(System.currentTimeMillis() - 8 * 3600000L));

        when(bookInfoService.queryBookInfoById(1)).thenReturn(availableBook);
        when(reservationService.getActiveReservation(1)).thenReturn(expired);
        when(reservationService.expireAndAdvance(1)).thenReturn(null);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);
        when(borrowService.addBorrow2(any())).thenReturn(1);

        Integer result = controller.borrowBook(100, 1);

        assertEquals(1, result);
    }

    @Test
    void borrowBook_bookNotFound_returns0() {
        when(bookInfoService.queryBookInfoById(999)).thenReturn(null);

        Integer result = controller.borrowBook(100, 999);

        assertEquals(0, result);
    }

    // ==================== 还书（触发预约） ====================

    @Test
    void returnBook_triggersReservation() {
        Borrow borrow = new Borrow();
        borrow.setBorrowid(1);
        borrow.setBookid(1);
        borrow.setUserid(100);
        borrow.setBorrowtime(new Date());

        when(bookInfoService.queryBookInfoById(1)).thenReturn(borrowedBook);
        when(borrowService.queryBorrowsById(1)).thenReturn(borrow);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);
        when(borrowService.updateBorrow2(any())).thenReturn(1);

        Reservation next = makeReservation(2, 101, 1, Reservation.STATUS_RESERVED, 1);
        when(reservationService.triggerReservation(1)).thenReturn(next);

        Integer result = controller.returnBook(1, 1);

        assertEquals(1, result);
        verify(reservationService).triggerReservation(1);
    }

    @Test
    void returnBook_noWaitingReservation_stillSucceeds() {
        Borrow borrow = new Borrow();
        borrow.setBorrowid(1);
        borrow.setBookid(1);
        borrow.setUserid(100);
        borrow.setBorrowtime(new Date());

        when(bookInfoService.queryBookInfoById(1)).thenReturn(borrowedBook);
        when(borrowService.queryBorrowsById(1)).thenReturn(borrow);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);
        when(borrowService.updateBorrow2(any())).thenReturn(1);
        when(reservationService.triggerReservation(1)).thenReturn(null);

        Integer result = controller.returnBook(1, 1);

        assertEquals(1, result);
    }

    @Test
    void returnBook_alreadyReturned_returns0() {
        Borrow borrow = new Borrow();
        borrow.setBorrowid(1);
        borrow.setBookid(1);
        borrow.setReturntime(new Date());

        when(bookInfoService.queryBookInfoById(1)).thenReturn(borrowedBook);
        when(borrowService.queryBorrowsById(1)).thenReturn(borrow);

        Integer result = controller.returnBook(1, 1);

        assertEquals(0, result);
    }

    // ==================== 管理员操作 ====================

    @Test
    void getQueueForBook_success() {
        List<Reservation> queue = Arrays.asList(
                makeReservation(1, 100, 1, Reservation.STATUS_WAITING, 1),
                makeReservation(2, 101, 1, Reservation.STATUS_WAITING, 2)
        );
        when(reservationService.getQueueForBook(1)).thenReturn(queue);

        Map<String, Object> result = controller.getQueueForBook(1);

        assertEquals(200, result.get("status"));
        List<?> data = (List<?>) result.get("data");
        assertEquals(2, data.size());
    }

    @Test
    void adminCancelReservation_success() {
        Reservation r = makeReservation(1, 100, 1, Reservation.STATUS_CANCELLED, 1);
        when(reservationService.cancelReservation(1)).thenReturn(r);

        Map<String, Object> result = controller.adminCancelReservation(1);

        assertEquals(200, result.get("status"));
    }

    @Test
    void adminCancelReservation_notFound_fails() {
        when(reservationService.cancelReservation(999)).thenReturn(null);

        Map<String, Object> result = controller.adminCancelReservation(999);

        assertEquals(420, result.get("status"));
    }

    @Test
    void adjustPosition_success() {
        Reservation r = makeReservation(1, 100, 1, Reservation.STATUS_WAITING, 3);
        when(reservationService.adjustPosition(1, 3)).thenReturn(r);

        Map<String, Object> result = controller.adjustPosition(1, 3);

        assertEquals(200, result.get("status"));
    }

    @Test
    void adjustPosition_fails() {
        when(reservationService.adjustPosition(999, 1)).thenReturn(null);

        Map<String, Object> result = controller.adjustPosition(999, 1);

        assertEquals(420, result.get("status"));
    }

    // ==================== 用户预约查询 ====================

    @Test
    void getUserReservations_success() {
        List<Reservation> list = Arrays.asList(
                makeReservation(1, 100, 1, Reservation.STATUS_WAITING, 1),
                makeReservation(2, 100, 2, Reservation.STATUS_FULFILLED, 1)
        );
        when(reservationService.getUserReservations(100)).thenReturn(list);

        Map<String, Object> result = controller.getUserReservations(100);

        assertEquals(200, result.get("status"));
        List<?> data = (List<?>) result.get("data");
        assertEquals(2, data.size());
    }

    // ==================== 完整流程 ====================

    @Test
    void fullFlow_borrowReturnReserveBorrowByReserver() {
        // 1. 第一个用户借书
        when(bookInfoService.queryBookInfoById(1)).thenReturn(availableBook);
        when(reservationService.getActiveReservation(1)).thenReturn(null);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);
        when(borrowService.addBorrow2(any())).thenReturn(1);

        Integer borrowResult = controller.borrowBook(100, 1);
        assertEquals(1, borrowResult);

        // 2. 第二个用户预约（书已被借）
        when(bookInfoService.queryBookInfoById(1)).thenReturn(borrowedBook);
        Reservation reservation = makeReservation(1, 101, 1, Reservation.STATUS_WAITING, 1);
        when(reservationService.createReservation(101, 1)).thenReturn(reservation);

        Map<String, Object> reserveResult = controller.createReservation(101, 1);
        assertEquals(200, reserveResult.get("status"));

        // 3. 第一个用户还书，触发保留
        Borrow borrow = new Borrow();
        borrow.setBorrowid(1);
        borrow.setBookid(1);
        borrow.setUserid(100);
        borrow.setBorrowtime(new Date());
        when(borrowService.queryBorrowsById(1)).thenReturn(borrow);
        when(borrowService.updateBorrow2(any())).thenReturn(1);

        Reservation triggered = makeReservation(1, 101, 1, Reservation.STATUS_RESERVED, 1);
        triggered.setReservetime(new Date());
        triggered.setExpirytime(new Date(System.currentTimeMillis() + 72 * 3600000L));
        when(reservationService.triggerReservation(1)).thenReturn(triggered);

        Integer returnResult = controller.returnBook(1, 1);
        assertEquals(1, returnResult);

        // 4. 预约用户借书成功
        when(bookInfoService.queryBookInfoById(1)).thenReturn(availableBook);
        when(reservationService.getActiveReservation(1)).thenReturn(triggered);
        when(reservationService.expireAndAdvance(1)).thenReturn(triggered);
        when(reservationService.consumeReservation(1)).thenReturn(triggered);

        Integer borrowResult2 = controller.borrowBook(101, 1);
        assertEquals(1, borrowResult2);
        verify(reservationService).consumeReservation(1);
    }

    @Test
    void fullFlow_expirationCascading() {
        // 保留过期后顺延给下一位
        Reservation expired = makeReservation(1, 100, 1, Reservation.STATUS_RESERVED, 1);
        expired.setExpirytime(new Date(System.currentTimeMillis() - 1000));

        Reservation next = makeReservation(2, 101, 1, Reservation.STATUS_RESERVED, 2);
        next.setReservetime(new Date());
        next.setExpirytime(new Date(System.currentTimeMillis() + 72 * 3600000L));

        when(bookInfoService.queryBookInfoById(1)).thenReturn(availableBook);
        when(reservationService.getActiveReservation(1)).thenReturn(expired);
        when(reservationService.expireAndAdvance(1)).thenReturn(next);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);
        when(borrowService.addBorrow2(any())).thenReturn(1);
        when(reservationService.consumeReservation(2)).thenReturn(next);

        // 下一位用户来借书，应该成功
        Integer result = controller.borrowBook(101, 1);
        assertEquals(1, result);
    }
}
