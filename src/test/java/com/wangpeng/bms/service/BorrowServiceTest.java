package com.wangpeng.bms.service;

import com.wangpeng.bms.exception.NotEnoughException;
import com.wangpeng.bms.exception.OperationFailureException;
import com.wangpeng.bms.mapper.BorrowMapper;
import com.wangpeng.bms.model.BookInfo;
import com.wangpeng.bms.model.Borrow;
import com.wangpeng.bms.model.Reservation;
import com.wangpeng.bms.service.impl.BorrowServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BorrowServiceImpl 单元测试
 * <p>
 * 通过 Mockito 模拟 Mapper 和 Service 层，验证借书、还书、续借的
 * 并发安全、幂等性和事务保护逻辑。
 * <p>
 * 覆盖场景：
 * 1. 并发借阅（库存原子递减）
 * 2. 重复归还（幂等返回 0）
 * 3. 库存为 0 时借书失败
 * 4. 读者借阅上限
 * 5. 预约保留检查
 * 6. 续借成功/失败
 */
@ExtendWith(MockitoExtension.class)
class BorrowServiceTest {

    @InjectMocks
    private BorrowServiceImpl borrowService;

    @Mock
    private BorrowMapper borrowMapper;

    @Mock
    private BookInfoService bookInfoService;

    @Mock
    private ReservationService reservationService;

    private BookInfo availableBook;
    private BookInfo borrowedBook;
    private Borrow activeBorrow;
    private Borrow returnedBorrow;

    @BeforeEach
    void setUp() {
        availableBook = new BookInfo();
        availableBook.setBookid(200);
        availableBook.setIsborrowed((byte) 0);
        availableBook.setStock(3);
        availableBook.setAvailablestock(2);
        availableBook.setBookname("测试图书");

        borrowedBook = new BookInfo();
        borrowedBook.setBookid(200);
        borrowedBook.setIsborrowed((byte) 1);
        borrowedBook.setStock(1);
        borrowedBook.setAvailablestock(0);
        borrowedBook.setBookname("测试图书");

        activeBorrow = makeBorrow(1, 100, 200, Borrow.STATUS_ACTIVE);

        returnedBorrow = makeBorrow(2, 100, 200, Borrow.STATUS_RETURNED);
        returnedBorrow.setReturntime(new Date());
    }

    // ========================= 借书 =========================

    @Test
    @DisplayName("借书成功 - 库存充足时正常借出")
    void borrowBook_success() {
        when(bookInfoService.queryBookInfoForUpdate(200)).thenReturn(availableBook);
        when(reservationService.getActiveReservation(eq(200), isNull())).thenReturn(null);
        when(borrowMapper.countActiveBorrowsByReader(100)).thenReturn(0);
        when(bookInfoService.decrementAvailableStock(200)).thenReturn(1);
        when(borrowMapper.insertSelective(any(Borrow.class))).thenReturn(1);

        Integer result = borrowService.borrowBook(100, 200);

        assertEquals(1, result);
        verify(bookInfoService).decrementAvailableStock(200);
        verify(borrowMapper).insertSelective(argThat(b ->
                b.getStatus() != null && b.getStatus() == Borrow.STATUS_ACTIVE));
    }

    @Test
    @DisplayName("借书失败 - 库存为 0 时拒绝借阅")
    void borrowBook_noStock_fails() {
        when(bookInfoService.queryBookInfoForUpdate(200)).thenReturn(borrowedBook);
        when(reservationService.getActiveReservation(eq(200), isNull())).thenReturn(null);
        when(reservationService.getActiveReservation(200, 100)).thenReturn(null);

        assertThrows(NotEnoughException.class, () -> borrowService.borrowBook(100, 200));

        verify(bookInfoService, never()).decrementAvailableStock(anyInt());
        verify(borrowMapper, never()).insertSelective(any());
    }

    @Test
    @DisplayName("借书失败 - 图书不存在")
    void borrowBook_bookNotFound() {
        when(bookInfoService.queryBookInfoForUpdate(999)).thenReturn(null);

        assertThrows(NullPointerException.class, () -> borrowService.borrowBook(100, 999));

        verify(borrowMapper, never()).insertSelective(any());
    }

    @Test
    @DisplayName("借书失败 - 读者已达借阅上限")
    void borrowBook_readerLimitExceeded() {
        when(bookInfoService.queryBookInfoForUpdate(200)).thenReturn(availableBook);
        when(reservationService.getActiveReservation(eq(200), isNull())).thenReturn(null);
        when(borrowMapper.countActiveBorrowsByReader(100)).thenReturn(5);

        assertThrows(NotEnoughException.class, () -> borrowService.borrowBook(100, 200));

        verify(bookInfoService, never()).decrementAvailableStock(anyInt());
    }

    @Test
    @DisplayName("借书失败 - 并发借阅时库存原子递减为 0，第二次失败")
    void borrowBook_concurrentRace_secondFails() {
        // 模拟第二次借阅：书已被锁且库存为 0
        BookInfo zeroStock = new BookInfo();
        zeroStock.setBookid(200);
        zeroStock.setIsborrowed((byte) 1);
        zeroStock.setStock(1);
        zeroStock.setAvailablestock(0);

        when(bookInfoService.queryBookInfoForUpdate(200)).thenReturn(zeroStock);
        when(reservationService.getActiveReservation(eq(200), isNull())).thenReturn(null);
        when(reservationService.getActiveReservation(200, 100)).thenReturn(null);

        assertThrows(NotEnoughException.class, () -> borrowService.borrowBook(100, 200));

        verify(bookInfoService, never()).decrementAvailableStock(anyInt());
    }

    @Test
    @DisplayName("借书失败 - 该书已被保留给其他读者")
    void borrowBook_reservedForOtherUser() {
        Reservation reserved = makeReservation(1, 200, 200, Reservation.STATUS_RESERVED, 1);
        reserved.setExpirytime(new Date(System.currentTimeMillis() + 72 * 3600 * 1000));

        when(bookInfoService.queryBookInfoForUpdate(200)).thenReturn(availableBook);
        when(reservationService.getActiveReservation(eq(200), isNull())).thenReturn(reserved);

        assertThrows(NotEnoughException.class, () -> borrowService.borrowBook(100, 200));

        verify(reservationService, never()).consumeReservation(anyInt());
    }

    @Test
    @DisplayName("借书成功 - 保留人本人借书，消费保留")
    void borrowBook_reservedHolderBorrows() {
        Reservation reserved = makeReservation(1, 100, 200, Reservation.STATUS_RESERVED, 1);
        reserved.setExpirytime(new Date(System.currentTimeMillis() + 72 * 3600 * 1000));

        when(bookInfoService.queryBookInfoForUpdate(200)).thenReturn(availableBook);
        when(reservationService.getActiveReservation(eq(200), isNull())).thenReturn(reserved);
        when(reservationService.consumeReservation(1)).thenReturn(1);
        when(borrowMapper.countActiveBorrowsByReader(100)).thenReturn(0);
        when(bookInfoService.decrementAvailableStock(200)).thenReturn(1);
        when(borrowMapper.insertSelective(any(Borrow.class))).thenReturn(1);

        Integer result = borrowService.borrowBook(100, 200);

        assertEquals(1, result);
        verify(reservationService).consumeReservation(1);
    }

    @Test
    @DisplayName("借书 - 过期保留自动顺延时继续借阅流程")
    void borrowBook_expiredReservation_autoAdvance() {
        Reservation expired = makeReservation(1, 200, 200, Reservation.STATUS_RESERVED, 1);
        expired.setExpirytime(new Date(System.currentTimeMillis() - 1000));

        when(bookInfoService.queryBookInfoForUpdate(200)).thenReturn(availableBook);
        when(reservationService.getActiveReservation(eq(200), isNull())).thenReturn(expired, null);
        when(reservationService.expireAndAdvance(1)).thenReturn(null);
        when(borrowMapper.countActiveBorrowsByReader(100)).thenReturn(0);
        when(bookInfoService.decrementAvailableStock(200)).thenReturn(1);
        when(borrowMapper.insertSelective(any(Borrow.class))).thenReturn(1);

        Integer result = borrowService.borrowBook(100, 200);

        assertEquals(1, result);
        verify(reservationService).expireAndAdvance(1);
    }

    // ========================= 还书 =========================

    @Test
    @DisplayName("还书成功 - 正常归还并加库存")
    void returnBook_success() {
        when(borrowMapper.selectForUpdate(1)).thenReturn(activeBorrow);
        when(borrowMapper.updateReturnTimeIfActive(eq(1), any(Date.class))).thenReturn(1);
        when(bookInfoService.queryBookInfoForUpdate(200)).thenReturn(borrowedBook);
        when(bookInfoService.incrementAvailableStock(200)).thenReturn(1);
        when(reservationService.triggerReservation(200)).thenReturn(null);

        Integer result = borrowService.returnBook(1, 200);

        assertEquals(1, result);
        verify(bookInfoService).incrementAvailableStock(200);
    }

    @Test
    @DisplayName("重复归还 - 幂等返回 0，不加库存")
    void returnBook_duplicateIsNoop() {
        when(borrowMapper.selectForUpdate(1)).thenReturn(returnedBorrow);
        when(borrowMapper.updateReturnTimeIfActive(eq(1), any(Date.class))).thenReturn(0);

        Integer result = borrowService.returnBook(1, 200);

        assertEquals(0, result);
        verify(bookInfoService, never()).incrementAvailableStock(anyInt());
        verify(bookInfoService, never()).queryBookInfoForUpdate(anyInt());
    }

    @Test
    @DisplayName("还书失败 - 借阅记录不存在")
    void returnBook_borrowNotFound() {
        when(borrowMapper.selectForUpdate(999)).thenReturn(null);

        assertThrows(NullPointerException.class, () -> borrowService.returnBook(999, 200));

        verify(borrowMapper, never()).updateReturnTimeIfActive(anyInt(), any(Date.class));
    }

    @Test
    @DisplayName("还书成功 - 触发预约保留（isBorrowed=2）")
    void returnBook_triggersReservation() {
        Reservation triggered = makeReservation(3, 300, 200, Reservation.STATUS_RESERVED, 1);

        when(borrowMapper.selectForUpdate(1)).thenReturn(activeBorrow);
        when(borrowMapper.updateReturnTimeIfActive(eq(1), any(Date.class))).thenReturn(1);
        when(bookInfoService.queryBookInfoForUpdate(200)).thenReturn(borrowedBook);
        when(bookInfoService.incrementAvailableStock(200)).thenReturn(1);
        when(reservationService.triggerReservation(200)).thenReturn(triggered);
        when(bookInfoService.updateBookInfo(any())).thenReturn(1);

        Integer result = borrowService.returnBook(1, 200);

        assertEquals(1, result);
        verify(reservationService).triggerReservation(200);
        verify(bookInfoService).updateBookInfo(argThat(b ->
                b.getIsborrowed() != null && b.getIsborrowed() == 2));
    }

    // ========================= 续借 =========================

    @Test
    @DisplayName("续借成功 - 活跃状态可续借")
    void renewBorrow_success() {
        when(borrowMapper.selectForUpdate(1)).thenReturn(activeBorrow);
        when(borrowMapper.updateByPrimaryKeySelective(any(Borrow.class))).thenReturn(1);

        Integer result = borrowService.renewBorrow(1);

        assertEquals(1, result);
        verify(borrowMapper).updateByPrimaryKeySelective(argThat(b ->
                b.getStatus() != null && b.getStatus() == Borrow.STATUS_RENEWED));
    }

    @Test
    @DisplayName("续借失败 - 已归还的记录不可续借")
    void renewBorrow_alreadyReturned() {
        when(borrowMapper.selectForUpdate(2)).thenReturn(returnedBorrow);

        assertThrows(OperationFailureException.class, () -> borrowService.renewBorrow(2));

        verify(borrowMapper, never()).updateByPrimaryKeySelective(any());
    }

    @Test
    @DisplayName("续借失败 - 记录不存在")
    void renewBorrow_notFound() {
        when(borrowMapper.selectForUpdate(999)).thenReturn(null);

        assertThrows(NullPointerException.class, () -> borrowService.renewBorrow(999));
    }

    // ========================= 辅助方法 =========================

    private Borrow makeBorrow(Integer id, Integer userid, Integer bookid, byte status) {
        Borrow b = new Borrow();
        b.setBorrowid(id);
        b.setUserid(userid);
        b.setBookid(bookid);
        b.setBorrowtime(new Date(System.currentTimeMillis() - 7 * 24 * 3600 * 1000));
        b.setReturntime(null);
        b.setStatus(status);
        return b;
    }

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
}
