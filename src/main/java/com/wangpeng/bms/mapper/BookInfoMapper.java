package com.wangpeng.bms.mapper;

import com.wangpeng.bms.model.BookInfo;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface BookInfoMapper {
    int deleteByPrimaryKey(Integer bookid);

    int insert(BookInfo record);

    int insertSelective(BookInfo record);

    BookInfo selectByPrimaryKey(Integer bookid);

    int updateByPrimaryKeySelective(BookInfo record);

    int updateByPrimaryKey(BookInfo record);

    List<BookInfo> selectAllByLimit(@Param("begin") Integer begin, @Param("size") Integer size);

    Integer selectCount();

    int selectCountBySearch(Map<String, Object> searchParam);

    List<BookInfo> selectBySearch(Map<String, Object> searchParam);

    List<BookInfo> selectAll();

    int selectCountByType(Map<String, Object> map);

    List<BookInfo> selectByType(Map<String, Object> map);

    /**
     * CAS更新isBorrowed：仅当当前值等于expectedStatus时才更新为newStatus
     * @return 受影响行数，0表示状态已被其他事务修改（并发竞争失败）
     */
    int updateIsBorrowedCas(@Param("bookid") Integer bookid,
                            @Param("expectedStatus") Byte expectedStatus,
                            @Param("newStatus") Byte newStatus);
}
