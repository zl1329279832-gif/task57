package com.wangpeng.bms.mapper;

import com.wangpeng.bms.model.Reservation;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ReservationMapper {

    int insertSelective(Reservation record);

    Reservation selectByPrimaryKey(Integer reservationid);

    int updateByPrimaryKeySelective(Reservation record);

    int deleteByPrimaryKey(Integer reservationid);

    Reservation selectActiveByBookAndUser(@Param("bookid") Integer bookid, @Param("userid") Integer userid);

    List<Reservation> selectWaitingByBook(@Param("bookid") Integer bookid);

    Reservation selectNextWaiting(@Param("bookid") Integer bookid);

    List<Reservation> selectByUser(@Param("userid") Integer userid);

    int selectCountByUser(@Param("userid") Integer userid);

    Integer getMaxQueuePosition(@Param("bookid") Integer bookid);

    int selectWaitingCountByBook(@Param("bookid") Integer bookid);
}
