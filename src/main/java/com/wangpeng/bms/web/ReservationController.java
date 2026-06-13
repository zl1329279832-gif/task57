package com.wangpeng.bms.web;

import com.wangpeng.bms.model.BookInfo;
import com.wangpeng.bms.model.Reservation;
import com.wangpeng.bms.service.BookInfoService;
import com.wangpeng.bms.service.BorrowService;
import com.wangpeng.bms.service.ReservationService;
import com.wangpeng.bms.utils.MyResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/reservation")
public class ReservationController {

    @Autowired
    ReservationService reservationService;
    @Autowired
    BookInfoService bookInfoService;
    @Autowired
    BorrowService borrowService;

    // ==================== 预约 CRUD ====================

    /**
     * 读者创建预约（无库存时排队）
     */
    @RequestMapping(value = {"/create", "/reader/create"})
    public Map<String, Object> createReservation(Integer userid, Integer bookid) {
        BookInfo book = bookInfoService.queryBookInfoById(bookid);
        if (book == null) {
            return MyResult.getResultMap(0, "图书不存在");
        }
        if (book.getIsborrowed() == 0) {
            return MyResult.getResultMap(0, "该图书当前可借，无需预约");
        }
        try {
            Reservation reservation = reservationService.createReservation(userid, bookid);
            return MyResult.getResultMap(1, "预约成功，当前排队位置：" + reservation.getQueueposition(), reservation);
        } catch (RuntimeException e) {
            return MyResult.getResultMap(0, e.getMessage());
        }
    }

    /**
     * 读者取消预约
     */
    @RequestMapping(value = {"/cancel", "/reader/cancel"})
    public Map<String, Object> cancelReservation(Integer reservationid) {
        Integer result = reservationService.cancelReservation(reservationid);
        if (result == 0) {
            return MyResult.getResultMap(0, "取消失败，预约不存在或状态不允许取消");
        }
        return MyResult.getResultMap(1, "取消成功");
    }

    /**
     * 读者查看自己的预约列表
     */
    @RequestMapping(value = {"/myList", "/reader/myList"})
    public Map<String, Object> getUserReservations(Integer userid) {
        List<Reservation> list = reservationService.getUserReservations(userid);
        return MyResult.getResultMap(1, "success", list);
    }

    // ==================== 管理员操作 ====================

    /**
     * 管理员查看某本书的预约队列
     */
    @RequestMapping(value = "/queue")
    public Map<String, Object> getQueueForBook(Integer bookid) {
        List<Reservation> queue = reservationService.getQueueForBook(bookid);
        return MyResult.getResultMap(1, "success", queue);
    }

    /**
     * 管理员取消预约
     */
    @RequestMapping(value = "/admin/cancel")
    public Map<String, Object> adminCancelReservation(Integer reservationid) {
        Integer result = reservationService.cancelReservation(reservationid);
        if (result == 0) {
            return MyResult.getResultMap(0, "取消失败");
        }
        return MyResult.getResultMap(1, "管理员取消成功");
    }

    /**
     * 管理员调整排队位置
     */
    @RequestMapping(value = "/admin/adjustPosition")
    public Map<String, Object> adjustPosition(Integer reservationid, Integer newPosition) {
        Integer result = reservationService.adjustPosition(reservationid, newPosition);
        if (result == 0) {
            return MyResult.getResultMap(0, "调整失败");
        }
        return MyResult.getResultMap(1, "调整成功");
    }

    // ==================== 增强版借书（委托给 BorrowService） ====================

    /**
     * 借书（增强版，支持预约保留检查）
     * 事务保护、幂等、并发安全逻辑在 BorrowService.borrowBook 中
     */
    @RequestMapping(value = {"/borrowBook", "/reader/borrowBook"})
    public Integer borrowBook(Integer userid, Integer bookid) {
        try {
            return borrowService.borrowBook(userid, bookid);
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    // ==================== 增强版还书（委托给 BorrowService） ====================

    /**
     * 还书（增强版，触发预约保留）
     * 事务保护、幂等、并发安全逻辑在 BorrowService.returnBook 中
     */
    @RequestMapping(value = {"/returnBook", "/reader/returnBook"})
    public Integer returnBook(Integer borrowid, Integer bookid) {
        try {
            return borrowService.returnBook(borrowid, bookid);
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }
}
