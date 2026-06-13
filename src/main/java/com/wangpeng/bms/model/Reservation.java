package com.wangpeng.bms.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 预约记录
 */
public class Reservation implements Serializable {

    /** 排队中 */
    public static final byte STATUS_WAITING = 0;
    /** 已保留（到书保留中） */
    public static final byte STATUS_RESERVED = 1;
    /** 已完成（已借走） */
    public static final byte STATUS_FULFILLED = 2;
    /** 已取消 */
    public static final byte STATUS_CANCELLED = 3;
    /** 已过期 */
    public static final byte STATUS_EXPIRED = 4;

    private Integer reservationid;
    private Integer userid;
    private Integer bookid;
    private Byte status;
    private Integer queueposition;
    private Date reservetime;
    private Date expirytime;
    private Date createtime;

    /* 非数据库字段，用于查询展示 */
    private String username;
    private String bookname;

    public Integer getReservationid() {
        return reservationid;
    }

    public void setReservationid(Integer reservationid) {
        this.reservationid = reservationid;
    }

    public Integer getUserid() {
        return userid;
    }

    public void setUserid(Integer userid) {
        this.userid = userid;
    }

    public Integer getBookid() {
        return bookid;
    }

    public void setBookid(Integer bookid) {
        this.bookid = bookid;
    }

    public Byte getStatus() {
        return status;
    }

    public void setStatus(Byte status) {
        this.status = status;
    }

    public Integer getQueueposition() {
        return queueposition;
    }

    public void setQueueposition(Integer queueposition) {
        this.queueposition = queueposition;
    }

    public Date getReservetime() {
        return reservetime;
    }

    public void setReservetime(Date reservetime) {
        this.reservetime = reservetime;
    }

    public Date getExpirytime() {
        return expirytime;
    }

    public void setExpirytime(Date expirytime) {
        this.expirytime = expirytime;
    }

    public Date getCreatetime() {
        return createtime;
    }

    public void setCreatetime(Date createtime) {
        this.createtime = createtime;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getBookname() {
        return bookname;
    }

    public void setBookname(String bookname) {
        this.bookname = bookname;
    }
}
