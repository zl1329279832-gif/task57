package com.wangpeng.bms.web;

import com.wangpeng.bms.exception.NotEnoughException;
import com.wangpeng.bms.exception.OperationFailureException;
import com.wangpeng.bms.model.BookInfo;
import com.wangpeng.bms.model.Borrow;
import com.wangpeng.bms.model.Reservation;
import com.wangpeng.bms.service.BookInfoService;
import com.wangpeng.bms.service.BorrowService;
import com.wangpeng.bms.service.ReservationService;
import com.wangpeng.bms.utils.MyResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/reservation")
public class ReservationController {

    @Autowired
    private ReservationService reservationService;
    @Autowired
    private BookInfoService bookInfoService;
    @Autowired
    private BorrowService borrowService;

    // 读者预约排队
    @RequestMapping(value = {"/createReservation", "/reader/createReservation"})
    public Map<String, Object> createReservation(Integer userid, Integer bookid) {
        // 检查图书是否存在
        BookInfo book = bookInfoService.queryBookInfoById(bookid);
        if (book == null) {
            return MyResult.getResultMap(420, "图书不存在");
        }
        // 检查图书是否已被借出（只有无库存才能预约）
        if (book.getIsborrowed() == 0) {
            return MyResult.getResultMap(420, "图书当前可借，无需预约");
        }

        Reservation reservation = reservationService.createReservation(userid, bookid);
        if (reservation == null) {
            return MyResult.getResultMap(420, "您已有该书的预约记录，不能重复预约");
        }
        return MyResult.getResultMap(200, "预约成功", reservation);
    }

    // 读者取消预约
    @RequestMapping(value = {"/cancelReservation", "/reader/cancelReservation"})
    public Map<String, Object> cancelReservation(Integer reservationid) {
        Reservation reservation = reservationService.cancelReservation(reservationid);
        if (reservation == null) {
            return MyResult.getResultMap(420, "预约不存在或无法取消");
        }
        return MyResult.getResultMap(200, "取消预约成功", reservation);
    }

    // 读者查看自己的预约
    @RequestMapping(value = {"/getUserReservations", "/reader/getUserReservations"})
    public Map<String, Object> getUserReservations(Integer userid) {
        List<Reservation> reservations = reservationService.getUserReservations(userid);
        return MyResult.getResultMap(200, "success", reservations);
    }

    // 管理员查看某本书的预约队列
    @RequestMapping(value = "/getQueueForBook")
    public Map<String, Object> getQueueForBook(Integer bookid) {
        List<Reservation> queue = reservationService.getQueueForBook(bookid);
        return MyResult.getResultMap(200, "success", queue);
    }

    // 管理员取消预约
    @RequestMapping(value = "/adminCancelReservation")
    public Map<String, Object> adminCancelReservation(Integer reservationid) {
        Reservation reservation = reservationService.cancelReservation(reservationid);
        if (reservation == null) {
            return MyResult.getResultMap(420, "预约不存在或无法取消");
        }
        return MyResult.getResultMap(200, "管理员取消预约成功", reservation);
    }

    // 管理员调整预约队列位置
    @RequestMapping(value = "/adjustPosition")
    public Map<String, Object> adjustPosition(Integer reservationid, Integer newPosition) {
        Reservation reservation = reservationService.adjustPosition(reservationid, newPosition);
        if (reservation == null) {
            return MyResult.getResultMap(420, "预约不存在或不在等待状态");
        }
        return MyResult.getResultMap(200, "调整位置成功", reservation);
    }

    // 借书（预约感知）
    @RequestMapping(value = {"/borrowBook", "/reader/borrowBook"})
    @Transactional
    public Integer borrowBook(Integer userid, Integer bookid) {
        try {
            BookInfo theBook = bookInfoService.queryBookInfoById(bookid);
            if (theBook == null) {
                throw new NullPointerException("图书" + bookid + "不存在");
            }
            if (theBook.getIsborrowed() == 1) {
                throw new NotEnoughException("图书" + bookid + "库存不足（已经被借走）");
            }

            // 检查是否有保留名额
            Reservation active = reservationService.getActiveReservation(bookid);
            if (active != null) {
                // 检查过期
                active = reservationService.expireAndAdvance(bookid);
                if (active != null && !active.getUserid().equals(userid)) {
                    throw new NotEnoughException("图书" + bookid + "已被其他读者保留");
                }
            }

            // 更新图书状态
            BookInfo bookInfo = new BookInfo();
            bookInfo.setBookid(bookid);
            bookInfo.setIsborrowed((byte) 1);
            Integer res2 = bookInfoService.updateBookInfo(bookInfo);
            if (res2 == 0) throw new OperationFailureException("图书" + bookid + "更新被借信息失败");

            // 添加借阅记录
            Borrow borrow = new Borrow();
            borrow.setUserid(userid);
            borrow.setBookid(bookid);
            borrow.setBorrowtime(new Date(System.currentTimeMillis()));
            Integer res1 = borrowService.addBorrow2(borrow);
            if (res1 == 0) throw new OperationFailureException("图书" + bookid + "添加借阅记录失败");

            // 消费预约名额
            if (active != null && active.getUserid().equals(userid)) {
                reservationService.consumeReservation(active.getReservationid());
            }

        } catch (Exception e) {
            System.out.println("发生异常，进行手动回滚");
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (Exception ignored) {
            }
            e.printStackTrace();
            return 0;
        }
        return 1;
    }

    // 还书（触发预约保留）
    @RequestMapping(value = {"/returnBook", "/reader/returnBook"})
    @Transactional
    public Integer returnBook(Integer borrowid, Integer bookid) {
        try {
            BookInfo theBook = bookInfoService.queryBookInfoById(bookid);
            Borrow theBorrow = borrowService.queryBorrowsById(borrowid);

            if (theBook == null) {
                throw new NullPointerException("图书" + bookid + "不存在");
            } else if (theBorrow == null) {
                throw new NullPointerException("借书记录" + borrowid + "不存在");
            } else if (theBorrow.getReturntime() != null) {
                throw new NotEnoughException("图书" + bookid + "已经还过了");
            }

            // 更新图书状态
            BookInfo bookInfo = new BookInfo();
            bookInfo.setBookid(bookid);
            bookInfo.setIsborrowed((byte) 0);
            Integer res2 = bookInfoService.updateBookInfo(bookInfo);
            if (res2 == 0) throw new OperationFailureException("图书" + bookid + "更新被借信息失败");

            // 更新借阅记录
            Borrow borrow = new Borrow();
            borrow.setBorrowid(borrowid);
            borrow.setReturntime(new Date(System.currentTimeMillis()));
            Integer res1 = borrowService.updateBorrow2(borrow);
            if (res1 == 0) throw new OperationFailureException("图书" + bookid + "更新借阅记录失败");

            // 还书后触发预约保留
            reservationService.triggerReservation(bookid);

        } catch (Exception e) {
            System.out.println("发生异常，进行手动回滚");
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (Exception ignored) {
            }
            e.printStackTrace();
            return 0;
        }
        return 1;
    }
}
