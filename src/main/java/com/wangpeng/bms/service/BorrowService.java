package com.wangpeng.bms.service;

import com.wangpeng.bms.model.Borrow;

import java.util.List;
import java.util.Map;

public interface BorrowService {
    Integer getCount();

    Integer getSearchCount(Map<String, Object> params);

    List<Borrow> searchBorrowsByPage(Map<String, Object> params);

    Integer addBorrow(Borrow borrow);

    Integer addBorrow2(Borrow borrow);

    Integer deleteBorrow(Borrow borrow);

    Integer deleteBorrows(List<Borrow> borrows);

    Integer updateBorrow(Borrow borrow);

    Integer updateBorrow2(Borrow borrow);

    Borrow queryBorrowsById(Integer borrowid);

    /**
     * CAS归还：仅当未归还时才设置归还时间（幂等）
     * @return 受影响行数，0表示已归还
     */
    int returnBorrowCas(Integer borrowid);

    /**
     * 查询用户当前未归还的借阅数量
     */
    int getActiveBorrowCount(Integer userid);

    /**
     * 续借：重置借阅时间（仅限未归还的记录）
     * @return 1成功，0失败
     */
    int renewBorrow(Integer borrowid);
}
