package com.wangpeng.bms.web;

import com.wangpeng.bms.exception.NotEnoughException;
import com.wangpeng.bms.exception.OperationFailureException;
import com.wangpeng.bms.model.BookInfo;
import com.wangpeng.bms.model.Borrow;
import com.wangpeng.bms.service.BookInfoService;
import com.wangpeng.bms.service.BorrowService;
import com.wangpeng.bms.utils.MyResult;
import com.wangpeng.bms.utils.MyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/borrow")
public class BorrowController {

    /** 每位读者最多同时借阅数量 */
    private static final int MAX_BORROW_LIMIT = 5;

    @Autowired
    BorrowService borrowService;
    @Autowired
    BookInfoService bookInfoService;

    // 分页查询借阅 params: {page, limit, userid, bookid}
    @RequestMapping(value = "/queryBorrowsByPage")
    public Map<String, Object> queryBorrowsByPage(@RequestParam Map<String, Object> params){
        MyUtils.parsePageParams(params);
        int count = borrowService.getSearchCount(params);
        List<Borrow> borrows = borrowService.searchBorrowsByPage(params);
        return MyResult.getListResultMap(0, "success", count, borrows);
    }

    // 添加借阅
    @RequestMapping(value = "/addBorrow")
    public Integer addBorrow(@RequestBody Borrow borrow){
        return borrowService.addBorrow(borrow);
    }

    // 获得数量
    @RequestMapping(value = "/getCount")
    public Integer getCount(){
        return borrowService.getCount();
    }

    // 删除借阅
    @RequestMapping(value = "/deleteBorrow")
    public Integer deleteBorrow(@RequestBody Borrow borrow){
        return borrowService.deleteBorrow(borrow);
    }

    // 删除一些借阅
    @RequestMapping(value = "/deleteBorrows")
    public Integer deleteBorrows(@RequestBody List<Borrow> borrows){
        return borrowService.deleteBorrows(borrows);
    }

    // 更新借阅
    @RequestMapping(value = "/updateBorrow")
    public Integer updateBorrow(@RequestBody Borrow borrow){
        return borrowService.updateBorrow(borrow);
    }

    // 借书
    @RequestMapping(value = {"/borrowBook", "/reader/borrowBook"})
    @Transactional
    public Integer borrowBook(Integer userid, Integer bookid){
        try{
            // 查询该书的情况
            BookInfo theBook = bookInfoService.queryBookInfoById(bookid);

            if(theBook == null) {
                throw new NullPointerException("图书" + bookid + "不存在");
            } else if(theBook.getIsborrowed() != 0) {
                throw new NotEnoughException("图书" + bookid + "库存不足（已经被借走）");
            }

            // 检查读者借阅上限
            int activeCount = borrowService.getActiveBorrowCount(userid);
            if (activeCount >= MAX_BORROW_LIMIT) {
                throw new NotEnoughException("您当前借阅数量已达上限（" + MAX_BORROW_LIMIT + "本）");
            }

            // CAS更新图书状态：仅当isBorrowed==0时才设为1（原子操作，防止并发双借）
            int casResult = bookInfoService.casUpdateIsBorrowed(bookid, (byte) 0, (byte) 1);
            if(casResult == 0) {
                throw new NotEnoughException("图书" + bookid + "库存不足（已被其他读者借走）");
            }

            // 添加一条记录到borrow表
            Borrow borrow = new Borrow();
            borrow.setUserid(userid);
            borrow.setBookid(bookid);
            borrow.setBorrowtime(new Date(System.currentTimeMillis()));
            Integer res1 = borrowService.addBorrow2(borrow);
            if(res1 == 0) throw new OperationFailureException("图书" + bookid + "添加借阅记录失败");

        } catch (Exception e) {
            System.out.println("发生异常，进行手动回滚");
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            e.printStackTrace();
            return 0;
        }
        return 1;
    }

    // 还书
    @RequestMapping(value = {"/returnBook", "/reader/returnBook"})
    @Transactional
    public Integer returnBook(Integer borrowid, Integer bookid){
        try {
            // 查询该书的情况
            BookInfo theBook = bookInfoService.queryBookInfoById(bookid);
            if(theBook == null) {
                throw new NullPointerException("图书" + bookid + "不存在");
            }

            // 查询借书的情况
            Borrow theBorrow = borrowService.queryBorrowsById(borrowid);
            if(theBorrow == null) {
                throw new NullPointerException("借书记录" + borrowid + "不存在");
            }

            // CAS归还：仅当returnTime为NULL时才设置归还时间（幂等保护）
            int casResult = borrowService.returnBorrowCas(borrowid);
            if(casResult == 0) {
                // 已经还过了，幂等返回成功
                return 1;
            }

            // CAS归还成功，更新图书表的isBorrowed（从1→0）
            int bookCas = bookInfoService.casUpdateIsBorrowed(bookid, (byte) 1, (byte) 0);
            if(bookCas == 0) {
                throw new OperationFailureException("图书" + bookid + "更新库存状态失败");
            }

        } catch (Exception e) {
            System.out.println("发生异常，进行手动回滚");
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            e.printStackTrace();
            return 0;
        }
        return 1;
    }

    // 续借
    @RequestMapping(value = {"/renewBook", "/reader/renewBook"})
    @Transactional
    public Integer renewBook(Integer borrowid){
        try {
            int result = borrowService.renewBorrow(borrowid);
            if(result == 0) {
                throw new OperationFailureException("续借失败，记录不存在或已归还");
            }
        } catch (Exception e) {
            System.out.println("发生异常，进行手动回滚");
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            e.printStackTrace();
            return 0;
        }
        return 1;
    }

}
