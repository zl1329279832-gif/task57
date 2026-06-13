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

    public static final long HOLD_PERIOD_MS = 72L * 60 * 60 * 1000;

    @Resource
    private ReservationMapper reservationMapper;

    @Override
    public Reservation createReservation(Integer userid, Integer bookid) {
        // 检查是否已有活跃预约（WAITING 或 RESERVED）
        Reservation existing = reservationMapper.selectActiveByBookAndUser(bookid, userid);
        if (existing != null) {
            return null; // 重复预约
        }

        // 获取当前最大队列位置
        Integer maxPos = reservationMapper.getMaxQueuePosition(bookid);
        int nextPos = (maxPos == null) ? 1 : maxPos + 1;

        Reservation reservation = new Reservation();
        reservation.setUserid(userid);
        reservation.setBookid(bookid);
        reservation.setStatus(Reservation.STATUS_WAITING);
        reservation.setQueueposition(nextPos);
        reservation.setCreatetime(new Date());

        reservationMapper.insertSelective(reservation);
        return reservation;
    }

    @Override
    public Reservation cancelReservation(Integer reservationid) {
        Reservation reservation = reservationMapper.selectByPrimaryKey(reservationid);
        if (reservation == null) {
            return null;
        }
        // 已完成的预约不能取消
        if (reservation.getStatus() == Reservation.STATUS_FULFILLED) {
            return null;
        }

        Reservation update = new Reservation();
        update.setReservationid(reservationid);
        update.setStatus(Reservation.STATUS_CANCELLED);
        reservationMapper.updateByPrimaryKeySelective(update);

        reservation.setStatus(Reservation.STATUS_CANCELLED);
        return reservation;
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
    public Reservation consumeReservation(Integer reservationid) {
        Reservation reservation = reservationMapper.selectByPrimaryKey(reservationid);
        if (reservation == null) {
            return null;
        }

        Reservation update = new Reservation();
        update.setReservationid(reservationid);
        update.setStatus(Reservation.STATUS_FULFILLED);
        reservationMapper.updateByPrimaryKeySelective(update);

        reservation.setStatus(Reservation.STATUS_FULFILLED);
        return reservation;
    }

    @Override
    public Reservation triggerReservation(Integer bookid) {
        Reservation next = reservationMapper.selectNextWaiting(bookid);
        if (next == null) {
            return null; // 没有等待中的预约
        }

        Date now = new Date();
        Reservation update = new Reservation();
        update.setReservationid(next.getReservationid());
        update.setStatus(Reservation.STATUS_RESERVED);
        update.setReservetime(now);
        update.setExpirytime(new Date(now.getTime() + HOLD_PERIOD_MS));
        reservationMapper.updateByPrimaryKeySelective(update);

        next.setStatus(Reservation.STATUS_RESERVED);
        next.setReservetime(now);
        next.setExpirytime(new Date(now.getTime() + HOLD_PERIOD_MS));
        return next;
    }

    @Override
    public Reservation expireAndAdvance(Integer bookid) {
        Reservation active = getActiveReservation(bookid);
        if (active == null) {
            return null;
        }
        // 检查是否过期
        if (active.getExpirytime() != null && active.getExpirytime().before(new Date())) {
            // 标记为过期
            Reservation update = new Reservation();
            update.setReservationid(active.getReservationid());
            update.setStatus(Reservation.STATUS_EXPIRED);
            reservationMapper.updateByPrimaryKeySelective(update);

            // 触发下一位
            return triggerReservation(bookid);
        }
        return active;
    }

    @Override
    public Reservation getActiveReservation(Integer bookid) {
        // 查找 RESERVED 状态的预约（status=1）
        Reservation active = reservationMapper.selectActiveByBookAndUser(bookid, null);
        if (active != null && active.getStatus() == Reservation.STATUS_RESERVED) {
            return active;
        }
        return null;
    }

    @Override
    public Reservation adjustPosition(Integer reservationid, Integer newPosition) {
        Reservation reservation = reservationMapper.selectByPrimaryKey(reservationid);
        if (reservation == null) {
            return null;
        }
        if (reservation.getStatus() != Reservation.STATUS_WAITING) {
            return null; // 只能调整等待中的预约
        }

        int oldPosition = reservation.getQueueposition();
        if (oldPosition == newPosition) {
            return reservation; // 位置不变
        }

        // 获取同一本书的所有等待预约
        List<Reservation> queue = reservationMapper.selectWaitingByBook(reservation.getBookid());

        // 找到目标位置上的预约并交换
        for (Reservation other : queue) {
            if (other.getQueueposition() == newPosition) {
                Reservation updateOther = new Reservation();
                updateOther.setReservationid(other.getReservationid());
                updateOther.setQueueposition(oldPosition);
                reservationMapper.updateByPrimaryKeySelective(updateOther);
                break;
            }
        }

        // 更新当前预约的位置
        Reservation update = new Reservation();
        update.setReservationid(reservationid);
        update.setQueueposition(newPosition);
        reservationMapper.updateByPrimaryKeySelective(update);

        reservation.setQueueposition(newPosition);
        return reservation;
    }
}
