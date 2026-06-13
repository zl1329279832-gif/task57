package com.wangpeng.bms.service;

import com.wangpeng.bms.model.BookInfo;
import com.wangpeng.bms.model.Reservation;
import com.wangpeng.bms.web.ReservationController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 预约排队全流程测试
 * <p>
 * 通过 ReservationController 测试完整的预约→还书→保留→借书流程，
 * 使用 Mockito 模拟 Service 层，无需 Redis/MySQL。
 * <p>
 * 覆盖场景：
 * 1. 无库存预约
 * 2. 重复预约拒绝
 * 3. 还书触发保留（委托给 BorrowService）
 * 4. 借书识别保留名额（委托给 BorrowService）
 * 5. 管理员队列操作（查看、取消、调整位置）
 */
@ExtendWith(MockitoExtension.class)
class ReservationFlowTest {

    @InjectMocks
    private ReservationController reservationController;

    @Mock
    private ReservationService reservationService;

    @Mock
    private BookInfoService bookInfoService;

    @Mock
    private BorrowService borrowService;

    private BookInfo borrowedBook;
    private BookInfo availableBook;
    private BookInfo reservedBook;

    @BeforeEach
    void setUp() {
        borrowedBook = new BookInfo();
        borrowedBook.setBookid(200);
        borrowedBook.setIsborrowed((byte) 1);
        borrowedBook.setBookname("测试图书");

        availableBook = new BookInfo();
        availableBook.setBookid(200);
        availableBook.setIsborrowed((byte) 0);
        availableBook.setBookname("测试图书");

        reservedBook = new BookInfo();
        reservedBook.setBookid(200);
        reservedBook.setIsborrowed((byte) 2);
        reservedBook.setBookname("测试图书");
    }

    // ========================= 1. 无库存预约 =========================

    @Test
    @DisplayName("无库存时读者可以成功预约排队")
    void noStock_createReservation() {
        when(bookInfoService.queryBookInfoById(200)).thenReturn(borrowedBook);
        when(reservationService.createReservation(100, 200))
                .thenReturn(makeReservation(1, 100, 200, Reservation.STATUS_WAITING, 1));

        Map<String, Object> result = reservationController.createReservation(100, 200);

        assertEquals(1, result.get("status"));
        assertNotNull(result.get("data"));
        verify(reservationService).createReservation(100, 200);
    }

    @Test
    @DisplayName("图书可借时提示无需预约")
    void bookAvailable_noReservationNeeded() {
        when(bookInfoService.queryBookInfoById(200)).thenReturn(availableBook);

        Map<String, Object> result = reservationController.createReservation(100, 200);

        assertEquals(0, result.get("status"));
        verify(reservationService, never()).createReservation(anyInt(), anyInt());
    }

    @Test
    @DisplayName("图书不存在时创建预约失败")
    void bookNotFound_createReservationFails() {
        when(bookInfoService.queryBookInfoById(999)).thenReturn(null);

        Map<String, Object> result = reservationController.createReservation(100, 999);

        assertEquals(0, result.get("status"));
        verify(reservationService, never()).createReservation(anyInt(), anyInt());
    }

    // ========================= 2. 重复预约拒绝 =========================

    @Test
    @DisplayName("同一用户对同一本书不可重复预约")
    void duplicateReservation_rejected() {
        when(bookInfoService.queryBookInfoById(200)).thenReturn(borrowedBook);
        when(reservationService.createReservation(100, 200))
                .thenThrow(new RuntimeException("您已存在该图书的预约记录，不可重复预约"));

        Map<String, Object> result = reservationController.createReservation(100, 200);

        assertEquals(0, result.get("status"));
        assertTrue(result.get("message").toString().contains("不可重复预约"));
    }

    // ========================= 3. 还书（委托给 BorrowService） =========================

    @Test
    @DisplayName("还书成功 - 委托给 BorrowService.returnBook")
    void returnBook_success_delegatesToService() {
        when(borrowService.returnBook(1, 200)).thenReturn(1);

        Integer result = reservationController.returnBook(1, 200);

        assertEquals(1, result);
        verify(borrowService).returnBook(1, 200);
    }

    @Test
    @DisplayName("重复还书 - BorrowService 返回 0（幂等）")
    void returnBook_duplicate_delegatesToService() {
        when(borrowService.returnBook(1, 200)).thenReturn(0);

        Integer result = reservationController.returnBook(1, 200);

        assertEquals(0, result);
    }

    @Test
    @DisplayName("还书异常 - BorrowService 抛异常，Controller 返回 0")
    void returnBook_exception_returnsZero() {
        when(borrowService.returnBook(1, 200))
                .thenThrow(new RuntimeException("图书不存在"));

        Integer result = reservationController.returnBook(1, 200);

        assertEquals(0, result);
    }

    // ========================= 4. 借书（委托给 BorrowService） =========================

    @Test
    @DisplayName("借书成功 - 委托给 BorrowService.borrowBook")
    void borrowBook_success_delegatesToService() {
        when(borrowService.borrowBook(100, 200)).thenReturn(1);

        Integer result = reservationController.borrowBook(100, 200);

        assertEquals(1, result);
        verify(borrowService).borrowBook(100, 200);
    }

    @Test
    @DisplayName("借书失败 - BorrowService 抛异常，Controller 返回 0")
    void borrowBook_exception_returnsZero() {
        when(borrowService.borrowBook(100, 200))
                .thenThrow(new RuntimeException("库存不足"));

        Integer result = reservationController.borrowBook(100, 200);

        assertEquals(0, result);
    }

    @Test
    @DisplayName("书已借出且无预约 - BorrowService 返回 0")
    void borrowBook_borrowedNoReservation_returnsZero() {
        when(borrowService.borrowBook(100, 200)).thenReturn(0);

        Integer result = reservationController.borrowBook(100, 200);

        assertEquals(0, result);
    }

    // ========================= 5. 管理员队列操作 =========================

    @Test
    @DisplayName("管理员查看预约队列")
    void admin_viewQueue() {
        Reservation r1 = makeReservation(1, 100, 200, Reservation.STATUS_WAITING, 1);
        r1.setUsername("张三");
        Reservation r2 = makeReservation(2, 101, 200, Reservation.STATUS_WAITING, 2);
        r2.setUsername("李四");

        when(reservationService.getQueueForBook(200)).thenReturn(Arrays.asList(r1, r2));

        Map<String, Object> result = reservationController.getQueueForBook(200);

        assertEquals(1, result.get("status"));
        assertTrue(result.get("data") instanceof java.util.List);
        assertEquals(2, ((java.util.List<?>) result.get("data")).size());
    }

    @Test
    @DisplayName("管理员取消预约")
    void admin_cancelReservation() {
        when(reservationService.cancelReservation(1)).thenReturn(1);

        Map<String, Object> result = reservationController.adminCancelReservation(1);

        assertEquals(1, result.get("status"));
    }

    @Test
    @DisplayName("管理员取消不存在的预约")
    void admin_cancelNonexistent() {
        when(reservationService.cancelReservation(999)).thenReturn(0);

        Map<String, Object> result = reservationController.adminCancelReservation(999);

        assertEquals(0, result.get("status"));
    }

    @Test
    @DisplayName("管理员调整排队位置")
    void admin_adjustPosition() {
        when(reservationService.adjustPosition(1, 2)).thenReturn(1);

        Map<String, Object> result = reservationController.adjustPosition(1, 2);

        assertEquals(1, result.get("status"));
    }

    @Test
    @DisplayName("管理员调整位置失败")
    void admin_adjustPositionFails() {
        when(reservationService.adjustPosition(999, 2)).thenReturn(0);

        Map<String, Object> result = reservationController.adjustPosition(999, 2);

        assertEquals(0, result.get("status"));
    }

    // ========================= 6. 完整流程 =========================

    @Test
    @DisplayName("完整流程 - 预约阶段：无库存时创建预约")
    void fullFlow_phase1_createReservation() {
        when(bookInfoService.queryBookInfoById(200)).thenReturn(borrowedBook);
        when(reservationService.createReservation(100, 200))
                .thenReturn(makeReservation(1, 100, 200, Reservation.STATUS_WAITING, 1));

        Map<String, Object> result = reservationController.createReservation(100, 200);

        assertEquals(1, result.get("status"));
        verify(reservationService).createReservation(100, 200);
    }

    @Test
    @DisplayName("完整流程 - 还书阶段：委托给 BorrowService")
    void fullFlow_phase2_returnDelegates() {
        when(borrowService.returnBook(1, 200)).thenReturn(1);

        Integer result = reservationController.returnBook(1, 200);

        assertEquals(1, result);
        verify(borrowService).returnBook(1, 200);
    }

    @Test
    @DisplayName("完整流程 - 借书阶段：委托给 BorrowService")
    void fullFlow_phase3_borrowDelegates() {
        when(borrowService.borrowBook(100, 200)).thenReturn(1);

        Integer result = reservationController.borrowBook(100, 200);

        assertEquals(1, result);
        verify(borrowService).borrowBook(100, 200);
    }

    @Test
    @DisplayName("完整流程 - 借书失败：BorrowService 返回 0")
    void fullFlow_phase4_borrowFails() {
        when(borrowService.borrowBook(999, 200)).thenReturn(0);

        Integer result = reservationController.borrowBook(999, 200);

        assertEquals(0, result);
    }

    @Test
    @DisplayName("完整流程 - 重复还书幂等：BorrowService 返回 0")
    void fullFlow_phase5_duplicateReturn() {
        when(borrowService.returnBook(1, 200)).thenReturn(0);

        Integer result = reservationController.returnBook(1, 200);

        assertEquals(0, result);
    }

    // ========================= 辅助方法 =========================

    private Reservation makeReservation(Integer id, Integer userid, Integer bookid, byte status, int queuePos) {
        Reservation r = new Reservation();
        r.setReservationid(id);
        r.setUserid(userid);
        r.setBookid(bookid);
        r.setStatus(status);
        r.setQueueposition(queuePos);
        r.setCreatetime(new Date());
        return r;
    }

    private com.wangpeng.bms.model.Borrow makeBorrow(Integer id, Integer userid, Integer bookid) {
        com.wangpeng.bms.model.Borrow b = new com.wangpeng.bms.model.Borrow();
        b.setBorrowid(id);
        b.setUserid(userid);
        b.setBookid(bookid);
        b.setBorrowtime(new Date(System.currentTimeMillis() - 7 * 24 * 3600 * 1000));
        b.setReturntime(null);
        return b;
    }
}
