package com.wangpeng.bms.service.impl;

import com.wangpeng.bms.mapper.ReservationMapper;
import com.wangpeng.bms.model.Reservation;
import com.wangpeng.bms.service.ReservationService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

@Service
public class ReservationServiceImpl implements ReservationService {

    /** 保留窗口：72 小时 */
    private static final long HOLD_PERIOD_MS = 72L * 60 * 60 * 1000;

    @Resource
    private ReservationMapper reservationMapper;

    @Override
    public Reservation createReservation(Integer userid, Integer bookid) {
        // 1. 检查是否已有活跃预约（重复预约检测）
        Reservation existing = reservationMapper.selectActiveByBookAndUser(bookid, userid);
        if (existing != null) {
            throw new RuntimeException("您已存在该图书的预约记录，不可重复预约");
        }

        // 2. 计算队列位置
        Integer maxPos = reservationMapper.getMaxQueuePosition(bookid);
        int newPosition = (maxPos != null) ? maxPos + 1 : 1;

        // 3. 创建预约
        Reservation reservation = new Reservation();
        reservation.setUserid(userid);
        reservation.setBookid(bookid);
        reservation.setStatus(Reservation.STATUS_WAITING);
        reservation.setQueueposition(newPosition);
        reservation.setReservetime(new Date(System.currentTimeMillis()));
        reservation.setCreatetime(new Date(System.currentTimeMillis()));

        reservationMapper.insertSelective(reservation);
        return reservation;
    }

    @Override
    public Integer cancelReservation(Integer reservationid) {
        Reservation reservation = reservationMapper.selectByPrimaryKey(reservationid);
        if (reservation == null) {
            return 0;
        }
        // 只允许取消 WAITING 或 RESERVED 状态的预约
        if (reservation.getStatus() != Reservation.STATUS_WAITING
                && reservation.getStatus() != Reservation.STATUS_RESERVED) {
            return 0;
        }

        // 如果是 RESERVED 状态（当前保留人取消），需要顺延
        boolean wasReserved = reservation.getStatus() == Reservation.STATUS_RESERVED;

        Reservation update = new Reservation();
        update.setReservationid(reservationid);
        update.setStatus(Reservation.STATUS_CANCELLED);
        int result = reservationMapper.updateByPrimaryKeySelective(update);

        if (wasReserved && result > 0) {
            triggerReservation(reservation.getBookid());
        }

        return result;
    }

    @Override
    public List<Reservation> getQueueForBook(Integer bookid) {
        return reservationMapper.selectWaitingByBook(bookid);
    }

    @Override
    public List<Reservation> getUserReservations(Integer userid) {
        return reservationMapper.selectByUser(userid);
    }

    @Override
    public Integer consumeReservation(Integer reservationid) {
        Reservation update = new Reservation();
        update.setReservationid(reservationid);
        update.setStatus(Reservation.STATUS_FULFILLED);
        return reservationMapper.updateByPrimaryKeySelective(update);
    }

    @Override
    public Reservation triggerReservation(Integer bookid) {
        Reservation next = reservationMapper.selectNextWaiting(bookid);
        if (next == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        Reservation update = new Reservation();
        update.setReservationid(next.getReservationid());
        update.setStatus(Reservation.STATUS_RESERVED);
        update.setReservetime(new Date(now));
        update.setExpirytime(new Date(now + HOLD_PERIOD_MS));

        reservationMapper.updateByPrimaryKeySelective(update);

        next.setStatus(Reservation.STATUS_RESERVED);
        next.setReservetime(new Date(now));
        next.setExpirytime(new Date(now + HOLD_PERIOD_MS));
        return next;
    }

    @Override
    public Reservation expireAndAdvance(Integer reservationid) {
        Reservation reservation = reservationMapper.selectByPrimaryKey(reservationid);
        if (reservation == null) {
            return null;
        }

        // 标记当前预约为过期
        Reservation update = new Reservation();
        update.setReservationid(reservationid);
        update.setStatus(Reservation.STATUS_EXPIRED);
        reservationMapper.updateByPrimaryKeySelective(update);

        // 顺延到下一位
        return triggerReservation(reservation.getBookid());
    }

    @Override
    public Reservation getActiveReservation(Integer bookid, Integer userid) {
        return reservationMapper.selectActiveByBookAndUser(bookid, userid);
    }

    @Override
    public Integer adjustPosition(Integer reservationid, Integer newPosition) {
        Reservation reservation = reservationMapper.selectByPrimaryKey(reservationid);
        if (reservation == null || reservation.getStatus() != Reservation.STATUS_WAITING) {
            return 0;
        }

        Integer oldPosition = reservation.getQueueposition();
        if (oldPosition.equals(newPosition)) {
            return 1;
        }

        // 查找目标位置上原来的预约
        List<Reservation> waiting = reservationMapper.selectWaitingByBook(reservation.getBookid());
        Reservation targetReservation = null;
        for (Reservation r : waiting) {
            if (r.getQueueposition().equals(newPosition)) {
                targetReservation = r;
                break;
            }
        }

        if (targetReservation == null) {
            // 目标位置没有预约，直接更新
            Reservation update = new Reservation();
            update.setReservationid(reservationid);
            update.setQueueposition(newPosition);
            return reservationMapper.updateByPrimaryKeySelective(update);
        }

        // 交换位置：用临时值避免唯一约束冲突
        int tempPosition = -reservationid;

        Reservation temp = new Reservation();
        temp.setReservationid(reservationid);
        temp.setQueueposition(tempPosition);
        reservationMapper.updateByPrimaryKeySelective(temp);

        Reservation swap = new Reservation();
        swap.setReservationid(targetReservation.getReservationid());
        swap.setQueueposition(oldPosition);
        reservationMapper.updateByPrimaryKeySelective(swap);

        Reservation fin = new Reservation();
        fin.setReservationid(reservationid);
        fin.setQueueposition(newPosition);
        reservationMapper.updateByPrimaryKeySelective(fin);

        return 1;
    }
}
