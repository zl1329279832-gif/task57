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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/reservation")
public class ReservationController {

    /** 每位读者最多同时借阅数量 */
    private static final int MAX_BORROW_LIMIT = 5;

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

    // ==================== 增强版借书（带保留名额检查） ====================

    /**
     * 借书（增强版，支持预约保留检查）
     * 替代 /borrow/borrowBook，识别保留名额
     * 使用CAS更新防止并发双借
     */
    @RequestMapping(value = {"/borrowBook", "/reader/borrowBook"})
    @Transactional
    public Integer borrowBook(Integer userid, Integer bookid) {
        try {
            // 查询图书状态
            BookInfo theBook = bookInfoService.queryBookInfoById(bookid);
            if (theBook == null) {
                throw new NullPointerException("图书" + bookid + "不存在");
            }

            // 检查读者借阅上限
            int activeCount = borrowService.getActiveBorrowCount(userid);
            if (activeCount >= MAX_BORROW_LIMIT) {
                throw new NotEnoughException("您当前借阅数量已达上限（" + MAX_BORROW_LIMIT + "本）");
            }

            // 记录原始状态，用于CAS更新
            Byte originalStatus = theBook.getIsborrowed();

            // 查询该书是否有活跃保留
            Reservation activeReservation = reservationService.getActiveReservation(bookid, null);

            if (theBook.getIsborrowed() == 0) {
                // 书可借 —— 检查是否有 RESERVED 保留（书刚被还回但保留尚未领取）
                if (activeReservation != null && activeReservation.getStatus() == Reservation.STATUS_RESERVED) {
                    // 检查保留是否过期
                    if (activeReservation.getExpirytime() != null
                            && activeReservation.getExpirytime().before(new Date(System.currentTimeMillis()))) {
                        // 保留已过期，顺延并重新检查
                        reservationService.expireAndAdvance(activeReservation.getReservationid());
                        activeReservation = reservationService.getActiveReservation(bookid, null);
                    }
                    // 再次检查：仍然有活跃保留
                    if (activeReservation != null && activeReservation.getStatus() == Reservation.STATUS_RESERVED) {
                        if (!activeReservation.getUserid().equals(userid)) {
                            throw new NotEnoughException("该书已被保留给其他读者，您暂不可借");
                        }
                        // 就是保留人本人，消费保留
                        reservationService.consumeReservation(activeReservation.getReservationid());
                    }
                }
                // 无保留或保留已消费 → 正常借出
            } else if (theBook.getIsborrowed() == 1 || theBook.getIsborrowed() == 2) {
                // 书已被借 / 已保留
                Reservation myReservation = reservationService.getActiveReservation(bookid, userid);
                if (myReservation == null) {
                    throw new NotEnoughException("图书" + bookid + "库存不足，且您没有该书的预约保留");
                }
                if (myReservation.getStatus() != Reservation.STATUS_RESERVED) {
                    throw new NotEnoughException("图书" + bookid + "库存不足，您的预约尚未轮到您");
                }
                // 检查保留是否过期
                if (myReservation.getExpirytime() != null
                        && myReservation.getExpirytime().before(new Date(System.currentTimeMillis()))) {
                    reservationService.expireAndAdvance(myReservation.getReservationid());
                    throw new NotEnoughException("图书" + bookid + "保留已过期，已顺延给下一位");
                }
                // 有效保留，消费
                reservationService.consumeReservation(myReservation.getReservationid());
            }

            // CAS更新图书状态：从原始状态→1（原子操作，防止并发双借）
            int casResult = bookInfoService.casUpdateIsBorrowed(bookid, originalStatus, (byte) 1);
            if (casResult == 0) {
                throw new NotEnoughException("图书" + bookid + "状态已变更，请重试");
            }

            // 添加借阅记录
            Borrow borrow = new Borrow();
            borrow.setUserid(userid);
            borrow.setBookid(bookid);
            borrow.setBorrowtime(new Date(System.currentTimeMillis()));
            Integer res1 = borrowService.addBorrow2(borrow);
            if (res1 == 0) throw new OperationFailureException("图书" + bookid + "添加借阅记录失败");

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

    // ==================== 增强版还书（触发保留生成） ====================

    /**
     * 还书（增强版，触发预约保留）
     * 替代 /borrow/returnBook，还书后自动检查预约队列
     * 使用CAS归还防止重复还书导致库存重复加回
     */
    @RequestMapping(value = {"/returnBook", "/reader/returnBook"})
    @Transactional
    public Integer returnBook(Integer borrowid, Integer bookid) {
        try {
            BookInfo theBook = bookInfoService.queryBookInfoById(bookid);
            if (theBook == null) {
                throw new NullPointerException("图书" + bookid + "不存在");
            }

            Borrow theBorrow = borrowService.queryBorrowsById(borrowid);
            if (theBorrow == null) {
                throw new NullPointerException("借书记录" + borrowid + "不存在");
            }

            // CAS归还：仅当returnTime为NULL时才设置归还时间（幂等保护）
            int casResult = borrowService.returnBorrowCas(borrowid);
            if (casResult == 0) {
                // 已经还过了，幂等返回成功
                return 1;
            }

            // CAS归还成功，检查预约队列
            Reservation triggered = reservationService.triggerReservation(bookid);

            if (triggered != null) {
                // 有人排队 → 设为保留状态（isBorrowed: 1→2），等待预约人来取
                int bookCas = bookInfoService.casUpdateIsBorrowed(bookid, (byte) 1, (byte) 2);
                if (bookCas == 0) {
                    throw new OperationFailureException("图书" + bookid + "更新库存状态失败");
                }
            } else {
                // 无人排队 → 正常归还（isBorrowed: 1→0）
                int bookCas = bookInfoService.casUpdateIsBorrowed(bookid, (byte) 1, (byte) 0);
                if (bookCas == 0) {
                    throw new OperationFailureException("图书" + bookid + "更新库存状态失败");
                }
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
