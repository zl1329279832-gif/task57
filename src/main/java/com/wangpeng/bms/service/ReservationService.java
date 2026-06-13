package com.wangpeng.bms.service;

import com.wangpeng.bms.model.Reservation;

import java.util.List;

public interface ReservationService {

    /**
     * 创建预约（无库存时排队）
     */
    Reservation createReservation(Integer userid, Integer bookid);

    /**
     * 取消预约
     */
    Integer cancelReservation(Integer reservationid);

    /**
     * 获取某本书的预约队列
     */
    List<Reservation> getQueueForBook(Integer bookid);

    /**
     * 获取某用户的预约列表
     */
    List<Reservation> getUserReservations(Integer userid);

    /**
     * 消费预约（读者借走保留的书）
     */
    Integer consumeReservation(Integer reservationid);

    /**
     * 还书时触发保留（给排队队首生成保留名额）
     */
    Reservation triggerReservation(Integer bookid);

    /**
     * 过期当前保留并顺延到下一位
     */
    Reservation expireAndAdvance(Integer reservationid);

    /**
     * 查询某用户对某本书的活跃预约
     */
    Reservation getActiveReservation(Integer bookid, Integer userid);

    /**
     * 管理员调整队列位置
     */
    Integer adjustPosition(Integer reservationid, Integer newPosition);
}
