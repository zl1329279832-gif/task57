package com.wangpeng.bms.mapper;

import com.wangpeng.bms.model.Reservation;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ReservationMapper {

    int insertSelective(Reservation record);

    Reservation selectByPrimaryKey(Integer reservationid);

    int updateByPrimaryKeySelective(Reservation record);

    int deleteByPrimaryKey(Integer reservationid);

    /**
     * 查询某本书是否有指定用户的活跃预约（WAITING 或 RESERVED）
     */
    Reservation selectActiveByBookAndUser(@Param("bookid") Integer bookid, @Param("userid") Integer userid);

    /**
     * 查询某本书所有 WAITING 状态的预约，按队列位置排序
     */
    List<Reservation> selectWaitingByBook(@Param("bookid") Integer bookid);

    /**
     * 查询某本书队列位置最小的 WAITING 预约（队首）
     */
    Reservation selectNextWaiting(@Param("bookid") Integer bookid);

    /**
     * 查询某用户所有预约（含关联的用户名和书名）
     */
    List<Reservation> selectByUser(@Param("userid") Integer userid);

    /**
     * 统计某用户所有预约数量
     */
    int selectCountByUser(@Param("userid") Integer userid);

    /**
     * 获取某本书 WAITING 预约中的最大队列位置
     */
    Integer getMaxQueuePosition(@Param("bookid") Integer bookid);

    /**
     * 统计某本书的 WAITING 预约数量
     */
    int selectWaitingCountByBook(@Param("bookid") Integer bookid);
}
