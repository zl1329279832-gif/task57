package com.wangpeng.bms.web;

import com.wangpeng.bms.model.Borrow;
import com.wangpeng.bms.service.BorrowService;
import com.wangpeng.bms.utils.MyResult;
import com.wangpeng.bms.utils.MyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/borrow")
public class BorrowController {

    @Autowired
    BorrowService borrowService;

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

    // 借书（委托给 Service 层，事务由 Service 管理）
    @RequestMapping(value = {"/borrowBook", "/reader/borrowBook"})
    public Integer borrowBook(Integer userid, Integer bookid){
        try {
            return borrowService.borrowBook(userid, bookid);
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    // 还书（委托给 Service 层，幂等处理）
    @RequestMapping(value = {"/returnBook", "/reader/returnBook"})
    public Integer returnBook(Integer borrowid, Integer bookid){
        try {
            return borrowService.returnBook(borrowid, bookid);
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    // 续借
    @RequestMapping(value = {"/renewBook", "/reader/renewBook"})
    public Integer renewBook(Integer borrowid){
        try {
            return borrowService.renewBorrow(borrowid);
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

}
