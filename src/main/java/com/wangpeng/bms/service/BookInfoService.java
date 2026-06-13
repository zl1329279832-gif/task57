package com.wangpeng.bms.service;

import com.wangpeng.bms.model.BookInfo;

import java.util.List;
import java.util.Map;

public interface BookInfoService {
    Integer getCount();

    List<BookInfo> queryBookInfos();

    BookInfo queryBookInfoById(Integer bookid);

    Integer getSearchCount(Map<String, Object> params);

    List<BookInfo> searchBookInfosByPage(Map<String, Object> params);

    Integer addBookInfo(BookInfo bookInfo);

    Integer deleteBookInfo(BookInfo bookInfo);

    Integer deleteBookInfos(List<BookInfo> bookInfos);

    Integer updateBookInfo(BookInfo bookInfo);

    /**
     * CAS更新图书借阅状态（原子操作，防并发）
     * @return 受影响行数，0表示并发竞争失败
     */
    int casUpdateIsBorrowed(Integer bookid, Byte expectedStatus, Byte newStatus);
}
