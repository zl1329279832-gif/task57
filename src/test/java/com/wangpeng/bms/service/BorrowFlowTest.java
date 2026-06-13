package com.wangpeng.bms.service;

import com.wangpeng.bms.model.BookInfo;
import com.wangpeng.bms.model.Borrow;
import com.wangpeng.bms.web.BorrowController;
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
 * BorrowController 借还流程回归测试
 * 覆盖场景：并发借阅、重复归还、库存为0、读者借阅上限、续借
 */
@ExtendWith(MockitoExtension.class)
class BorrowFlowTest {

    @InjectMocks
    private BorrowController borrowController;

    @Mock
    private BorrowService borrowService;

    @Mock
    private BookInfoService bookInfoService;

    private BookInfo availableBook;
    private BookInfo borrowedBook;

    @BeforeEach
    void setUp() {
        availableBook = new BookInfo();
        availableBook.setBookid(1);
        availableBook.setIsborrowed((byte) 0);
        availableBook.setBookname("测试图书");

        borrowedBook = new BookInfo();
        borrowedBook.setBookid(1);
        borrowedBook.setIsborrowed((byte) 1);
        borrowedBook.setBookname("测试图书");
    }

    // ========================= 借书 =========================

    @Test
    @DisplayName("借书成功 - 正常流程")
    void borrowBook_success() {
        when(bookInfoService.queryBookInfoById(1)).thenReturn(availableBook);
        when(borrowService.getActiveBorrowCount(100)).thenReturn(0);
        when(bookInfoService.casUpdateIsBorrowed(1, (byte) 0, (byte) 1)).thenReturn(1);
        when(borrowService.addBorrow2(any(Borrow.class))).thenReturn(1);

        Integer result = borrowController.borrowBook(100, 1);

        assertEquals(1, result);
        verify(bookInfoService).casUpdateIsBorrowed(1, (byte) 0, (byte) 1);
        verify(borrowService).addBorrow2(any(Borrow.class));
    }

    @Test
    @DisplayName("借书失败 - 图书不存在")
    void borrowBook_bookNotFound() {
        when(bookInfoService.queryBookInfoById(999)).thenReturn(null);

        Integer result = borrowController.borrowBook(100, 999);

        assertEquals(0, result);
        verify(bookInfoService, never()).casUpdateIsBorrowed(anyInt(), anyByte(), anyByte());
    }

    @Test
    @DisplayName("借书失败 - 库存为0（已被借走）")
    void borrowBook_notEnough() {
        when(bookInfoService.queryBookInfoById(1)).thenReturn(borrowedBook);

        Integer result = borrowController.borrowBook(100, 1);

        assertEquals(0, result);
        verify(bookInfoService, never()).casUpdateIsBorrowed(anyInt(), anyByte(), anyByte());
    }

    @Test
    @DisplayName("借书失败 - 并发竞争（CAS失败，书已被其他读者借走）")
    void borrowBook_concurrentRace_secondFails() {
        // 两个请求同时读到 isBorrowed=0，但CAS只有一个能成功
        when(bookInfoService.queryBookInfoById(1)).thenReturn(availableBook);
        when(borrowService.getActiveBorrowCount(100)).thenReturn(0);
        // CAS返回0表示状态已被其他事务修改
        when(bookInfoService.casUpdateIsBorrowed(1, (byte) 0, (byte) 1)).thenReturn(0);

        Integer result = borrowController.borrowBook(100, 1);

        assertEquals(0, result);
        // CAS失败，不应插入借阅记录
        verify(borrowService, never()).addBorrow2(any(Borrow.class));
    }

    @Test
    @DisplayName("借书失败 - 读者借阅上限（已借5本）")
    void borrowBook_readerLimitExceeded() {
        when(bookInfoService.queryBookInfoById(1)).thenReturn(availableBook);
        when(borrowService.getActiveBorrowCount(100)).thenReturn(5);

        Integer result = borrowController.borrowBook(100, 1);

        assertEquals(0, result);
        verify(bookInfoService, never()).casUpdateIsBorrowed(anyInt(), anyByte(), anyByte());
        verify(borrowService, never()).addBorrow2(any(Borrow.class));
    }

    @Test
    @DisplayName("借书成功 - 借阅数量在上限内（已借4本）")
    void borrowBook_withinLimit() {
        when(bookInfoService.queryBookInfoById(1)).thenReturn(availableBook);
        when(borrowService.getActiveBorrowCount(100)).thenReturn(4);
        when(bookInfoService.casUpdateIsBorrowed(1, (byte) 0, (byte) 1)).thenReturn(1);
        when(borrowService.addBorrow2(any(Borrow.class))).thenReturn(1);

        Integer result = borrowController.borrowBook(100, 1);

        assertEquals(1, result);
    }

    // ========================= 还书 =========================

    @Test
    @DisplayName("还书成功 - 正常流程")
    void returnBook_success() {
        Borrow activeBorrow = makeBorrow(1, 100, 1, false);
        when(bookInfoService.queryBookInfoById(1)).thenReturn(borrowedBook);
        when(borrowService.queryBorrowsById(1)).thenReturn(activeBorrow);
        when(borrowService.returnBorrowCas(1)).thenReturn(1);
        when(bookInfoService.casUpdateIsBorrowed(1, (byte) 1, (byte) 0)).thenReturn(1);

        Integer result = borrowController.returnBook(1, 1);

        assertEquals(1, result);
        verify(borrowService).returnBorrowCas(1);
        verify(bookInfoService).casUpdateIsBorrowed(1, (byte) 1, (byte) 0);
    }

    @Test
    @DisplayName("还书幂等 - 重复归还返回成功但不重复操作库存")
    void returnBook_alreadyReturned() {
        Borrow returnedBorrow = makeBorrow(1, 100, 1, true);
        when(bookInfoService.queryBookInfoById(1)).thenReturn(availableBook);
        when(borrowService.queryBorrowsById(1)).thenReturn(returnedBorrow);
        // CAS返回0：已经还过了
        when(borrowService.returnBorrowCas(1)).thenReturn(0);

        Integer result = borrowController.returnBook(1, 1);

        // 幂等：返回成功
        assertEquals(1, result);
        // 不应更新图书库存状态
        verify(bookInfoService, never()).casUpdateIsBorrowed(anyInt(), anyByte(), anyByte());
    }

    @Test
    @DisplayName("还书失败 - 借阅记录不存在")
    void returnBook_borrowNotFound() {
        when(bookInfoService.queryBookInfoById(1)).thenReturn(borrowedBook);
        when(borrowService.queryBorrowsById(999)).thenReturn(null);

        Integer result = borrowController.returnBook(999, 1);

        assertEquals(0, result);
        verify(borrowService, never()).returnBorrowCas(anyInt());
    }

    @Test
    @DisplayName("还书失败 - 图书不存在")
    void returnBook_bookNotFound() {
        when(bookInfoService.queryBookInfoById(999)).thenReturn(null);

        Integer result = borrowController.returnBook(1, 999);

        assertEquals(0, result);
    }

    // ========================= 续借 =========================

    @Test
    @DisplayName("续借成功")
    void renewBook_success() {
        when(borrowService.renewBorrow(1)).thenReturn(1);

        Integer result = borrowController.renewBook(1);

        assertEquals(1, result);
        verify(borrowService).renewBorrow(1);
    }

    @Test
    @DisplayName("续借失败 - 记录不存在或已归还")
    void renewBook_failure() {
        when(borrowService.renewBorrow(999)).thenReturn(0);

        Integer result = borrowController.renewBook(999);

        assertEquals(0, result);
    }

    // ========================= 辅助方法 =========================

    private Borrow makeBorrow(Integer id, Integer userid, Integer bookid, boolean returned) {
        Borrow b = new Borrow();
        b.setBorrowid(id);
        b.setUserid(userid);
        b.setBookid(bookid);
        b.setBorrowtime(new Date(System.currentTimeMillis() - 7 * 24 * 3600 * 1000));
        b.setReturntime(returned ? new Date() : null);
        return b;
    }
}
