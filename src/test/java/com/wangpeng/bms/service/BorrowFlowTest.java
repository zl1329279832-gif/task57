package com.wangpeng.bms.service;

import com.wangpeng.bms.exception.NotEnoughException;
import com.wangpeng.bms.exception.OperationFailureException;
import com.wangpeng.bms.web.BorrowController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BorrowController 流程测试
 * <p>
 * 验证 Controller 层正确委托给 BorrowService 并处理返回值/异常。
 * 使用 Mockito 模拟 BorrowService。
 */
@ExtendWith(MockitoExtension.class)
class BorrowFlowTest {

    @InjectMocks
    private BorrowController borrowController;

    @Mock
    private BorrowService borrowService;

    // ========================= 借书 =========================

    @Test
    @DisplayName("Controller 借书成功 - 返回 1")
    void borrowBook_success() {
        when(borrowService.borrowBook(100, 200)).thenReturn(1);

        Integer result = borrowController.borrowBook(100, 200);

        assertEquals(1, result);
        verify(borrowService).borrowBook(100, 200);
    }

    @Test
    @DisplayName("Controller 借书失败 - Service 抛出 NotEnoughException，返回 0")
    void borrowBook_notEnough() {
        when(borrowService.borrowBook(100, 200))
                .thenThrow(new NotEnoughException("库存不足"));

        Integer result = borrowController.borrowBook(100, 200);

        assertEquals(0, result);
    }

    @Test
    @DisplayName("Controller 借书失败 - Service 抛出 NullPointerException，返回 0")
    void borrowBook_bookNotFound() {
        when(borrowService.borrowBook(100, 999))
                .thenThrow(new NullPointerException("图书不存在"));

        Integer result = borrowController.borrowBook(100, 999);

        assertEquals(0, result);
    }

    // ========================= 还书 =========================

    @Test
    @DisplayName("Controller 还书成功 - 返回 1")
    void returnBook_success() {
        when(borrowService.returnBook(1, 200)).thenReturn(1);

        Integer result = borrowController.returnBook(1, 200);

        assertEquals(1, result);
        verify(borrowService).returnBook(1, 200);
    }

    @Test
    @DisplayName("Controller 重复还书 - Service 返回 0（幂等）")
    void returnBook_alreadyReturned() {
        when(borrowService.returnBook(1, 200)).thenReturn(0);

        Integer result = borrowController.returnBook(1, 200);

        assertEquals(0, result);
    }

    // ========================= 续借 =========================

    @Test
    @DisplayName("Controller 续借成功 - 返回 1")
    void renewBook_success() {
        when(borrowService.renewBorrow(1)).thenReturn(1);

        Integer result = borrowController.renewBook(1);

        assertEquals(1, result);
        verify(borrowService).renewBorrow(1);
    }

    @Test
    @DisplayName("Controller 续借失败 - Service 抛出 OperationFailureException，返回 0")
    void renewBook_failure() {
        when(borrowService.renewBorrow(999))
                .thenThrow(new OperationFailureException("状态异常"));

        Integer result = borrowController.renewBook(999);

        assertEquals(0, result);
    }
}
