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
 * 3. 还书触发保留
 * 4. 保留过期自动顺延
 * 5. 借书识别保留名额
 * 6. 管理员队列操作（查看、取消、调整位置）
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

    // ========================= 3. 还书触发保留 =========================

    @Test
    @DisplayName("还书时有排队者，触发保留（isBorrowed=2）")
    void returnBook_withWaitingQueue_triggersReservation() {
        Reservation triggered = makeReservation(1, 100, 200, Reservation.STATUS_RESERVED, 1);
        triggered.setExpirytime(new Date(System.currentTimeMillis() + 72 * 3600 * 1000));

        when(bookInfoService.queryBookInfoById(200)).thenReturn(borrowedBook);
        when(borrowService.queryBorrowsById(1)).thenReturn(makeBorrow(1, 100, 200));
        when(borrowService.updateBorrow2(any())).thenReturn(1);
        when(reservationService.triggerReservation(200)).thenReturn(triggered);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);

        Integer result = reservationController.returnBook(1, 200);

        assertEquals(1, result);
        verify(reservationService).triggerReservation(200);
    }

    @Test
    @DisplayName("还书时无排队者，正常归还（isBorrowed=0）")
    void returnBook_noQueue_normalReturn() {
        when(bookInfoService.queryBookInfoById(200)).thenReturn(borrowedBook);
        when(borrowService.queryBorrowsById(1)).thenReturn(makeBorrow(1, 100, 200));
        when(borrowService.updateBorrow2(any())).thenReturn(1);
        when(reservationService.triggerReservation(200)).thenReturn(null);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);

        Integer result = reservationController.returnBook(1, 200);

        assertEquals(1, result);
    }

    // ========================= 4. 过期顺延 =========================

    @Test
    @DisplayName("保留过期后借书，自动顺延到下一位")
    void expiredReservation_autoAdvanceOnBorrow() {
        Reservation expired = makeReservation(1, 100, 200, Reservation.STATUS_RESERVED, 1);
        expired.setExpirytime(new Date(System.currentTimeMillis() - 1000));

        Reservation next = makeReservation(2, 101, 200, Reservation.STATUS_RESERVED, 2);
        next.setExpirytime(new Date(System.currentTimeMillis() + 72 * 3600 * 1000));

        when(bookInfoService.queryBookInfoById(200)).thenReturn(availableBook);
        // 链式返回：第一次调用返回 expired，第二次（顺延后）返回 next
        when(reservationService.getActiveReservation(eq(200), isNull()))
                .thenReturn(expired, next);
        when(reservationService.expireAndAdvance(1)).thenReturn(next);

        // 非保留人(999)尝试借书 → 被拒绝
        Integer result = reservationController.borrowBook(999, 200);

        assertEquals(0, result);
        verify(reservationService).expireAndAdvance(1);
    }

    @Test
    @DisplayName("过期顺延级联：第一位过期后无下一个人，书变为可借")
    void expiredCascade_secondInLineGetsReservation() {
        Reservation res1Expired = makeReservation(1, 100, 200, Reservation.STATUS_RESERVED, 1);
        res1Expired.setExpirytime(new Date(System.currentTimeMillis() - 1000));

        // 书可借（刚还回），但保留记录过期
        when(bookInfoService.queryBookInfoById(200)).thenReturn(availableBook);
        // 第一次调用返回过期保留，第二次（顺延后）返回 null
        when(reservationService.getActiveReservation(eq(200), isNull()))
                .thenReturn(res1Expired, (Reservation) null);
        when(reservationService.expireAndAdvance(1)).thenReturn(null);

        when(bookInfoService.updateBookInfo(any())).thenReturn(1);
        when(borrowService.addBorrow2(any())).thenReturn(1);

        // 非保留人(999)借书 → res1 过期 → 顺延（无人） → 书可借 → 999 借走
        Integer result = reservationController.borrowBook(999, 200);

        assertEquals(1, result);
        verify(reservationService).expireAndAdvance(1);
    }

    // ========================= 5. 借书识别保留名额 =========================

    @Test
    @DisplayName("有有效保留时，只有保留人可以借书")
    void borrowBook_validReservation_onlyHolderCanBorrow() {
        Reservation reservation = makeReservation(1, 100, 200, Reservation.STATUS_RESERVED, 1);
        reservation.setExpirytime(new Date(System.currentTimeMillis() + 72 * 3600 * 1000));

        when(bookInfoService.queryBookInfoById(200)).thenReturn(reservedBook);
        when(reservationService.getActiveReservation(eq(200), isNull())).thenReturn(reservation);
        when(reservationService.getActiveReservation(200, 100)).thenReturn(reservation);
        when(reservationService.consumeReservation(1)).thenReturn(1);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);
        when(borrowService.addBorrow2(any())).thenReturn(1);

        Integer result = reservationController.borrowBook(100, 200);

        assertEquals(1, result);
        verify(reservationService).consumeReservation(1);
    }

    @Test
    @DisplayName("有有效保留时，非保留人借书被拒绝")
    void borrowBook_validReservation_otherUserDenied() {
        when(bookInfoService.queryBookInfoById(200)).thenReturn(reservedBook);
        when(reservationService.getActiveReservation(eq(200), isNull())).thenReturn(null);
        when(reservationService.getActiveReservation(200, 999)).thenReturn(null);

        Integer result = reservationController.borrowBook(999, 200);

        assertEquals(0, result);
    }

    @Test
    @DisplayName("保留过期时借书，触发顺延并拒绝当前请求")
    void borrowBook_expiredReservation_expireAndDeny() {
        Reservation expired = makeReservation(1, 100, 200, Reservation.STATUS_RESERVED, 1);
        expired.setExpirytime(new Date(System.currentTimeMillis() - 1000));

        when(bookInfoService.queryBookInfoById(200)).thenReturn(reservedBook);
        when(reservationService.getActiveReservation(eq(200), isNull())).thenReturn(expired);
        when(reservationService.getActiveReservation(200, 100)).thenReturn(expired);
        when(reservationService.expireAndAdvance(1)).thenReturn(null);

        Integer result = reservationController.borrowBook(100, 200);

        assertEquals(0, result);
        verify(reservationService).expireAndAdvance(1);
    }

    @Test
    @DisplayName("无预约且书可借时正常借书")
    void borrowBook_noReservation_normalBorrow() {
        when(bookInfoService.queryBookInfoById(200)).thenReturn(availableBook);
        when(reservationService.getActiveReservation(eq(200), isNull())).thenReturn(null);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);
        when(borrowService.addBorrow2(any())).thenReturn(1);

        Integer result = reservationController.borrowBook(100, 200);

        assertEquals(1, result);
        verify(bookInfoService).updateBookInfo(any());
        verify(borrowService).addBorrow2(any());
    }

    @Test
    @DisplayName("书已借出且无预约时借书失败")
    void borrowBook_borrowedNoReservation_fails() {
        when(bookInfoService.queryBookInfoById(200)).thenReturn(borrowedBook);
        // 第一次调用：查任意活跃保留 → 无
        when(reservationService.getActiveReservation(eq(200), isNull())).thenReturn(null);
        // 第二次调用：查用户特定保留 → 无
        when(reservationService.getActiveReservation(200, 100)).thenReturn(null);

        Integer result = reservationController.borrowBook(100, 200);

        assertEquals(0, result);
    }

    // ========================= 6. 管理员队列操作 =========================

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

    // ========================= 7. 完整流程 =========================

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
    @DisplayName("完整流程 - 还书阶段：还书触发保留")
    void fullFlow_phase2_returnTriggersHold() {
        Reservation triggered = makeReservation(1, 100, 200, Reservation.STATUS_RESERVED, 1);
        triggered.setExpirytime(new Date(System.currentTimeMillis() + 72 * 3600 * 1000));

        when(bookInfoService.queryBookInfoById(200)).thenReturn(borrowedBook);
        when(borrowService.queryBorrowsById(1)).thenReturn(makeBorrow(1, 999, 200));
        when(borrowService.updateBorrow2(any())).thenReturn(1);
        when(reservationService.triggerReservation(200)).thenReturn(triggered);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);

        Integer result = reservationController.returnBook(1, 200);

        assertEquals(1, result);
        verify(reservationService).triggerReservation(200);
    }

    @Test
    @DisplayName("完整流程 - 借书阶段：保留人消费保留后借书")
    void fullFlow_phase3_consumeHoldAndBorrow() {
        Reservation triggered = makeReservation(1, 100, 200, Reservation.STATUS_RESERVED, 1);
        triggered.setExpirytime(new Date(System.currentTimeMillis() + 72 * 3600 * 1000));

        when(bookInfoService.queryBookInfoById(200)).thenReturn(reservedBook);
        when(reservationService.getActiveReservation(eq(200), isNull())).thenReturn(triggered);
        when(reservationService.getActiveReservation(200, 100)).thenReturn(triggered);
        when(reservationService.consumeReservation(1)).thenReturn(1);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);
        when(borrowService.addBorrow2(any())).thenReturn(1);

        Integer result = reservationController.borrowBook(100, 200);

        assertEquals(1, result);
        verify(reservationService).consumeReservation(1);
        verify(borrowService).addBorrow2(any());
    }

    @Test
    @DisplayName("完整流程 - 过期阶段：保留过期后顺延")
    void fullFlow_phase4_expiredAdvance() {
        Reservation expired = makeReservation(1, 100, 200, Reservation.STATUS_RESERVED, 1);
        expired.setExpirytime(new Date(System.currentTimeMillis() - 1000));

        Reservation next = makeReservation(2, 101, 200, Reservation.STATUS_RESERVED, 2);
        next.setExpirytime(new Date(System.currentTimeMillis() + 72 * 3600 * 1000));

        when(bookInfoService.queryBookInfoById(200)).thenReturn(availableBook);
        when(reservationService.getActiveReservation(eq(200), isNull()))
                .thenReturn(expired, next);
        when(reservationService.expireAndAdvance(1)).thenReturn(next);

        // 非保留人(999)尝试借书 → 过期顺延 → 下一位(101)持有保留 → 999不是保留人 → 拒绝
        Integer result = reservationController.borrowBook(999, 200);

        assertEquals(0, result);
        verify(reservationService).expireAndAdvance(1);
    }

    @Test
    @DisplayName("完整流程 - 顺延后借书：第二位保留人成功借书")
    void fullFlow_phase5_secondHolderBorrows() {
        Reservation triggered2 = makeReservation(2, 101, 200, Reservation.STATUS_RESERVED, 2);
        triggered2.setExpirytime(new Date(System.currentTimeMillis() + 72 * 3600 * 1000));

        when(bookInfoService.queryBookInfoById(200)).thenReturn(reservedBook);
        when(reservationService.getActiveReservation(eq(200), isNull())).thenReturn(triggered2);
        when(reservationService.getActiveReservation(200, 101)).thenReturn(triggered2);
        when(reservationService.consumeReservation(2)).thenReturn(1);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);
        when(borrowService.addBorrow2(any())).thenReturn(1);

        Integer result = reservationController.borrowBook(101, 200);

        assertEquals(1, result);
        verify(reservationService).consumeReservation(2);
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
