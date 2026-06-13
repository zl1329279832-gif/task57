package com.wangpeng.bms.mapper;

import com.wangpeng.bms.model.Borrow;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface BorrowMapper {
    int deleteByPrimaryKey(Integer borrowid);

    int insert(Borrow record);

    int insertSelective(Borrow record);

    Borrow selectByPrimaryKey(Integer borrowid);

    int updateByPrimaryKeySelective(Borrow record);

    int updateByPrimaryKey(Borrow record);

    List<Borrow> selectAllByLimit(@Param("begin") Integer begin, @Param("size") Integer size);

    Integer selectCount();

    int selectCountBySearch(Map<String, Object> searchParam);

    List<Borrow> selectBySearch(Map<String, Object> searchParam);

    Integer selectCountByReader(Integer userid);

    List<Borrow> selectAllByLimitByReader(@Param("begin") Integer begin, @Param("size") Integer size, @Param("userid") Integer userid);

    /**
     * CAS归还：仅当returnTime为NULL时才设置归还时间
     * @return 受影响行数，0表示已经归还过（幂等保护）
     */
    int updateReturnTimeCas(@Param("borrowid") Integer borrowid,
                            @Param("returntime") java.util.Date returntime);

    /**
     * 查询用户当前未归还的借阅数量（用于借阅上限判断）
     */
    int selectActiveBorrowCount(@Param("userid") Integer userid);
}