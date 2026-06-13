package com.wangpeng.bms.service.impl;

import com.wangpeng.bms.exception.NotEnoughException;
import com.wangpeng.bms.exception.OperationFailureException;
import com.wangpeng.bms.mapper.BorrowMapper;
import com.wangpeng.bms.model.BookInfo;
import com.wangpeng.bms.model.Borrow;
import com.wangpeng.bms.model.Reservation;
import com.wangpeng.bms.service.BookInfoService;
import com.wangpeng.bms.service.BorrowService;
import com.wangpeng.bms.service.ReservationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class BorrowServiceImpl implements BorrowService {

    private static final int MAX_BORROW_COUNT = 5;
    private static final long RENEW_MILLIS = 30L * 24 * 60 * 60 * 1000;

    @Resource
    private BorrowMapper borrowMapper;

    @Autowired
    private BookInfoService bookInfoService;

    @Autowired
    private ReservationService reservationService;

    @Override
    public Integer getCount() {
        return borrowMapper.selectCount();
    }

    @Override
    public Integer getSearchCount(Map<String, Object> params) {
        return borrowMapper.selectCountBySearch(params);
    }

    @Override
    public List<Borrow> searchBorrowsByPage(Map<String, Object> params) {
        List<Borrow> borrows = borrowMapper.selectBySearch(params);
        // 添加string类型的时间显示
        for(Borrow borrow : borrows) {
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            if(borrow.getBorrowtime() != null) borrow.setBorrowtimestr(simpleDateFormat.format(borrow.getBorrowtime()));
            if(borrow.getReturntime() != null) borrow.setReturntimestr(simpleDateFormat.format(borrow.getReturntime()));
        }
        return borrows;
    }

    @Override
    public Integer addBorrow(Borrow borrow) {
        // 将string类型的时间重新调整
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
        try {
            borrow.setBorrowtime(simpleDateFormat.parse(borrow.getBorrowtimestr()));
            borrow.setReturntime(simpleDateFormat.parse(borrow.getReturntimestr()));
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return borrowMapper.insertSelective(borrow);
    }

    // 不会调整时间格式的add
    @Override
    public Integer addBorrow2(Borrow borrow) {
        return borrowMapper.insertSelective(borrow);
    }

    @Override
    public Integer deleteBorrow(Borrow borrow) {
        // 先查询有没有还书
        Borrow borrow1 = borrowMapper.selectByPrimaryKey(borrow.getBorrowid());
        if(borrow1.getReturntime() == null) return 0;
        return borrowMapper.deleteByPrimaryKey(borrow.getBorrowid());
    }

    @Override
    public Integer deleteBorrows(List<Borrow> borrows) {
        int count = 0;
        for(Borrow borrow : borrows) {
            count += deleteBorrow(borrow);
        }
        return count;
    }

    @Override
    public Integer updateBorrow(Borrow borrow) {
        // 将string类型的时间重新调整
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
        try {
            borrow.setBorrowtime(simpleDateFormat.parse(borrow.getBorrowtimestr()));
            borrow.setReturntime(simpleDateFormat.parse(borrow.getReturntimestr()));
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return borrowMapper.updateByPrimaryKeySelective(borrow);
    }

    // 不调整时间格式的更新
    @Override
    public Integer updateBorrow2(Borrow borrow) {
        return borrowMapper.updateByPrimaryKeySelective(borrow);
    }

    @Override
    public Borrow queryBorrowsById(Integer borrowid) {
        return borrowMapper.selectByPrimaryKey(borrowid);
    }

    // ==================== 事务保护的借还续方法 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer borrowBook(Integer userid, Integer bookid) {
        // 1. SELECT FOR UPDATE 锁住图书行，防止并发借阅
        BookInfo theBook = bookInfoService.queryBookInfoForUpdate(bookid);
        if (theBook == null) {
            throw new NullPointerException("图书" + bookid + "不存在");
        }

        // 2. 预约保留检查（逻辑从 ReservationController 迁入）
        Reservation activeReservation = reservationService.getActiveReservation(bookid, null);

        if (theBook.getIsborrowed() == 0 || (theBook.getAvailablestock() != null && theBook.getAvailablestock() > 0)) {
            // 书可借 —— 检查是否有 RESERVED 保留
            if (activeReservation != null && activeReservation.getStatus() == Reservation.STATUS_RESERVED) {
                // 检查保留是否过期
                if (activeReservation.getExpirytime() != null
                        && activeReservation.getExpirytime().before(new Date())) {
                    reservationService.expireAndAdvance(activeReservation.getReservationid());
                    activeReservation = reservationService.getActiveReservation(bookid, null);
                }
                // 再次检查：仍然有活跃保留
                if (activeReservation != null && activeReservation.getStatus() == Reservation.STATUS_RESERVED) {
                    if (!activeReservation.getUserid().equals(userid)) {
                        throw new NotEnoughException("该书已被保留给其他读者，您暂不可借");
                    }
                    // 保留人本人，消费保留
                    reservationService.consumeReservation(activeReservation.getReservationid());
                }
            }
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
                    && myReservation.getExpirytime().before(new Date())) {
                reservationService.expireAndAdvance(myReservation.getReservationid());
                throw new NotEnoughException("图书" + bookid + "保留已过期，已顺延给下一位");
            }
            reservationService.consumeReservation(myReservation.getReservationid());
        }

        // 3. 读者借阅上限检查
        int activeCount = borrowMapper.countActiveBorrowsByReader(userid);
        if (activeCount >= MAX_BORROW_COUNT) {
            throw new NotEnoughException("读者" + userid + "已达借阅上限（" + MAX_BORROW_COUNT + "本）");
        }

        // 4. 原子减库存
        int rows = bookInfoService.decrementAvailableStock(bookid);
        if (rows == 0) {
            throw new NotEnoughException("图书" + bookid + "库存不足");
        }

        // 5. 插入借阅记录
        Borrow borrow = new Borrow();
        borrow.setUserid(userid);
        borrow.setBookid(bookid);
        borrow.setBorrowtime(new Date());
        borrow.setStatus(Borrow.STATUS_ACTIVE);
        int inserted = borrowMapper.insertSelective(borrow);
        if (inserted == 0) {
            throw new OperationFailureException("图书" + bookid + "添加借阅记录失败");
        }

        return 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer returnBook(Integer borrowid, Integer bookid) {
        // 1. SELECT FOR UPDATE 锁住借阅记录
        Borrow theBorrow = borrowMapper.selectForUpdate(borrowid);
        if (theBorrow == null) {
            throw new NullPointerException("借书记录" + borrowid + "不存在");
        }

        // 2. 幂等归还：条件 UPDATE WHERE status=0 AND returnTime IS NULL
        int rows = borrowMapper.updateReturnTimeIfActive(borrowid, new Date());
        if (rows == 0) {
            // 已归还，幂等返回 0
            return 0;
        }

        // 3. 原子加库存
        BookInfo theBook = bookInfoService.queryBookInfoForUpdate(bookid);
        if (theBook == null) {
            throw new NullPointerException("图书" + bookid + "不存在");
        }
        bookInfoService.incrementAvailableStock(bookid);

        // 4. 触发预约队列
        Reservation triggered = reservationService.triggerReservation(bookid);
        if (triggered != null) {
            // 有人排队 → 设为保留状态
            BookInfo bookInfo = new BookInfo();
            bookInfo.setBookid(bookid);
            bookInfo.setIsborrowed((byte) 2);
            bookInfoService.updateBookInfo(bookInfo);
        }

        return 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer renewBorrow(Integer borrowid) {
        // 1. SELECT FOR UPDATE 锁住借阅记录
        Borrow theBorrow = borrowMapper.selectForUpdate(borrowid);
        if (theBorrow == null) {
            throw new NullPointerException("借书记录" + borrowid + "不存在");
        }

        // 2. 检查状态：只有活跃状态可续借
        if (theBorrow.getStatus() == null || theBorrow.getStatus() != Borrow.STATUS_ACTIVE) {
            throw new OperationFailureException("借书记录" + borrowid + "状态异常，无法续借");
        }

        // 3. 续借：延长借阅时间 30 天，状态标记为续借
        Borrow update = new Borrow();
        update.setBorrowid(borrowid);
        update.setBorrowtime(new Date(System.currentTimeMillis() + RENEW_MILLIS));
        update.setStatus(Borrow.STATUS_RENEWED);
        int rows = borrowMapper.updateByPrimaryKeySelective(update);
        if (rows == 0) {
            throw new OperationFailureException("续借更新失败");
        }

        return 1;
    }

}
