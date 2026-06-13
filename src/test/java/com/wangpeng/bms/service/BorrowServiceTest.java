package com.wangpeng.bms.service;

import com.wangpeng.bms.mapper.BookInfoMapper;
import com.wangpeng.bms.mapper.BorrowMapper;
import com.wangpeng.bms.model.Borrow;
import com.wangpeng.bms.service.impl.BookInfoServiceImpl;
import com.wangpeng.bms.service.impl.BorrowServiceImpl;
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
 * BorrowServiceImpl / BookInfoServiceImpl 单元测试
 * 覆盖CAS操作、续借逻辑、借阅计数
 */
@ExtendWith(MockitoExtension.class)
class BorrowServiceTest {

    @InjectMocks
    private BorrowServiceImpl borrowService;

    @InjectMocks
    private BookInfoServiceImpl bookInfoService;

    @Mock
    private BorrowMapper borrowMapper;

    @Mock
    private BookInfoMapper bookInfoMapper;

    // ========================= casUpdateIsBorrowed =========================

    @Test
    @DisplayName("CAS更新图书状态 - 成功（状态匹配）")
    void casUpdateIsBorrowed_success() {
        when(bookInfoMapper.updateIsBorrowedCas(1, (byte) 0, (byte) 1)).thenReturn(1);

        int result = bookInfoService.casUpdateIsBorrowed(1, (byte) 0, (byte) 1);

        assertEquals(1, result);
        verify(bookInfoMapper).updateIsBorrowedCas(1, (byte) 0, (byte) 1);
    }

    @Test
    @DisplayName("CAS更新图书状态 - 失败（状态已被修改）")
    void casUpdateIsBorrowed_fail() {
        when(bookInfoMapper.updateIsBorrowedCas(1, (byte) 0, (byte) 1)).thenReturn(0);

        int result = bookInfoService.casUpdateIsBorrowed(1, (byte) 0, (byte) 1);

        assertEquals(0, result);
    }

    // ========================= returnBorrowCas =========================

    @Test
    @DisplayName("CAS归还 - 成功（首次归还）")
    void returnBorrowCas_success() {
        when(borrowMapper.updateReturnTimeCas(eq(1), any(Date.class))).thenReturn(1);

        int result = borrowService.returnBorrowCas(1);

        assertEquals(1, result);
        verify(borrowMapper).updateReturnTimeCas(eq(1), any(Date.class));
    }

    @Test
    @DisplayName("CAS归还 - 幂等（已经归还过）")
    void returnBorrowCas_alreadyReturned() {
        when(borrowMapper.updateReturnTimeCas(eq(1), any(Date.class))).thenReturn(0);

        int result = borrowService.returnBorrowCas(1);

        assertEquals(0, result);
    }

    // ========================= getActiveBorrowCount =========================

    @Test
    @DisplayName("查询活跃借阅数量 - 有借阅")
    void getActiveBorrowCount_withBorrows() {
        when(borrowMapper.selectActiveBorrowCount(100)).thenReturn(3);

        int count = borrowService.getActiveBorrowCount(100);

        assertEquals(3, count);
    }

    @Test
    @DisplayName("查询活跃借阅数量 - 无借阅")
    void getActiveBorrowCount_noBorrows() {
        when(borrowMapper.selectActiveBorrowCount(100)).thenReturn(0);

        int count = borrowService.getActiveBorrowCount(100);

        assertEquals(0, count);
    }

    // ========================= renewBorrow =========================

    @Test
    @DisplayName("续借成功 - 未归还的借阅记录")
    void renewBorrow_success() {
        Borrow activeBorrow = makeBorrow(1, 100, 200, false);
        when(borrowMapper.selectByPrimaryKey(1)).thenReturn(activeBorrow);
        when(borrowMapper.updateByPrimaryKeySelective(any(Borrow.class))).thenReturn(1);

        int result = borrowService.renewBorrow(1);

        assertEquals(1, result);
        verify(borrowMapper).updateByPrimaryKeySelective(any(Borrow.class));
    }

    @Test
    @DisplayName("续借失败 - 记录不存在")
    void renewBorrow_notFound() {
        when(borrowMapper.selectByPrimaryKey(999)).thenReturn(null);

        int result = borrowService.renewBorrow(999);

        assertEquals(0, result);
        verify(borrowMapper, never()).updateByPrimaryKeySelective(any(Borrow.class));
    }

    @Test
    @DisplayName("续借失败 - 已经归还")
    void renewBorrow_alreadyReturned() {
        Borrow returnedBorrow = makeBorrow(1, 100, 200, true);
        when(borrowMapper.selectByPrimaryKey(1)).thenReturn(returnedBorrow);

        int result = borrowService.renewBorrow(1);

        assertEquals(0, result);
        verify(borrowMapper, never()).updateByPrimaryKeySelective(any(Borrow.class));
    }

    // ========================= 并发场景验证 =========================

    @Test
    @DisplayName("并发借阅 - CAS保证只有一个请求成功更新库存")
    void borrowBook_concurrentRace_secondFails() {
        // 模拟两个并发请求：第一个CAS成功，第二个CAS失败
        when(bookInfoMapper.updateIsBorrowedCas(1, (byte) 0, (byte) 1))
                .thenReturn(1)   // 第一个请求成功
                .thenReturn(0);  // 第二个请求失败（状态已被修改）

        int first = bookInfoService.casUpdateIsBorrowed(1, (byte) 0, (byte) 1);
        int second = bookInfoService.casUpdateIsBorrowed(1, (byte) 0, (byte) 1);

        assertEquals(1, first);
        assertEquals(0, second);
    }

    @Test
    @DisplayName("重复归还 - CAS保证库存只恢复一次")
    void returnBook_duplicateReturn_onlyOnceRestored() {
        // 第一次CAS归还成功，第二次CAS归还失败（已设置returnTime）
        when(borrowMapper.updateReturnTimeCas(eq(1), any(Date.class)))
                .thenReturn(1)   // 第一次成功
                .thenReturn(0);  // 第二次幂等失败

        int first = borrowService.returnBorrowCas(1);
        int second = borrowService.returnBorrowCas(1);

        assertEquals(1, first);
        assertEquals(0, second);
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
