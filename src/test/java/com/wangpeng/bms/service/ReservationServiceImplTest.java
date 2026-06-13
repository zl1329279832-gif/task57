package com.wangpeng.bms.service;

import com.wangpeng.bms.mapper.ReservationMapper;
import com.wangpeng.bms.model.Reservation;
import com.wangpeng.bms.service.impl.ReservationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReservationServiceImpl 单元测试
 * 使用 Mockito 模拟 ReservationMapper，验证全部预约业务逻辑
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {

    @InjectMocks
    private ReservationServiceImpl reservationService;

    @Mock
    private ReservationMapper reservationMapper;

    private Reservation sampleWaiting;
    private Reservation sampleReserved;

    @BeforeEach
    void setUp() {
        sampleWaiting = makeReservation(1, 100, 200, Reservation.STATUS_WAITING, 1);
        sampleReserved = makeReservation(2, 100, 200, Reservation.STATUS_RESERVED, 1);
        sampleReserved.setExpirytime(new Date(System.currentTimeMillis() + 72 * 3600 * 1000));
    }

    // ========================= createReservation =========================

    @Test
    @DisplayName("创建预约 - 成功（队列为空时位置为1）")
    void createReservation_success_emptyQueue() {
        when(reservationMapper.selectActiveByBookAndUser(200, 100)).thenReturn(null);
        when(reservationMapper.getMaxQueuePosition(200)).thenReturn(null);
        when(reservationMapper.insertSelective(any(Reservation.class))).thenReturn(1);

        Reservation result = reservationService.createReservation(100, 200);

        assertNotNull(result);
        assertEquals(100, result.getUserid());
        assertEquals(200, result.getBookid());
        assertEquals(Reservation.STATUS_WAITING, result.getStatus());
        assertEquals(1, result.getQueueposition());
        assertNotNull(result.getCreatetime());

        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationMapper).insertSelective(captor.capture());
        assertEquals(1, captor.getValue().getQueueposition());
    }

    @Test
    @DisplayName("创建预约 - 成功（队列非空时位置递增）")
    void createReservation_success_withExistingQueue() {
        when(reservationMapper.selectActiveByBookAndUser(200, 100)).thenReturn(null);
        when(reservationMapper.getMaxQueuePosition(200)).thenReturn(3);
        when(reservationMapper.insertSelective(any(Reservation.class))).thenReturn(1);

        Reservation result = reservationService.createReservation(100, 200);

        assertEquals(4, result.getQueueposition());
    }

    @Test
    @DisplayName("创建预约 - 重复预约抛出异常")
    void createReservation_duplicate_throwsException() {
        when(reservationMapper.selectActiveByBookAndUser(200, 100)).thenReturn(sampleWaiting);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> reservationService.createReservation(100, 200));
        assertTrue(ex.getMessage().contains("不可重复预约"));
    }

    // ========================= cancelReservation =========================

    @Test
    @DisplayName("取消 WAITING 预约 - 成功")
    void cancelReservation_waiting_success() {
        when(reservationMapper.selectByPrimaryKey(1)).thenReturn(sampleWaiting);
        when(reservationMapper.updateByPrimaryKeySelective(any(Reservation.class))).thenReturn(1);

        Integer result = reservationService.cancelReservation(1);

        assertEquals(1, result);
        verify(reservationMapper, never()).selectNextWaiting(anyInt());
    }

    @Test
    @DisplayName("取消 RESERVED 预约 - 成功后触发顺延")
    void cancelReservation_reserved_triggersNext() {
        when(reservationMapper.selectByPrimaryKey(2)).thenReturn(sampleReserved);
        when(reservationMapper.updateByPrimaryKeySelective(any(Reservation.class))).thenReturn(1);
        when(reservationMapper.selectNextWaiting(200)).thenReturn(null);

        Integer result = reservationService.cancelReservation(2);

        assertEquals(1, result);
        verify(reservationMapper).selectNextWaiting(200);
    }

    @Test
    @DisplayName("取消不存在的预约 - 返回0")
    void cancelReservation_notFound_returnsZero() {
        when(reservationMapper.selectByPrimaryKey(999)).thenReturn(null);

        Integer result = reservationService.cancelReservation(999);

        assertEquals(0, result);
    }

    @Test
    @DisplayName("取消已完成预约 - 返回0")
    void cancelReservation_fulfilled_returnsZero() {
        Reservation fulfilled = makeReservation(10, 100, 200, Reservation.STATUS_FULFILLED, 1);
        when(reservationMapper.selectByPrimaryKey(10)).thenReturn(fulfilled);

        Integer result = reservationService.cancelReservation(10);

        assertEquals(0, result);
        verify(reservationMapper, never()).updateByPrimaryKeySelective(any());
    }

    // ========================= getQueueForBook =========================

    @Test
    @DisplayName("获取预约队列 - 返回有序列表")
    void getQueueForBook_returnsList() {
        List<Reservation> queue = Arrays.asList(sampleWaiting, makeReservation(3, 101, 200, Reservation.STATUS_WAITING, 2));
        when(reservationMapper.selectWaitingByBook(200)).thenReturn(queue);

        List<Reservation> result = reservationService.getQueueForBook(200);

        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getQueueposition());
        assertEquals(2, result.get(1).getQueueposition());
    }

    @Test
    @DisplayName("获取空队列 - 返回空列表")
    void getQueueForBook_empty() {
        when(reservationMapper.selectWaitingByBook(200)).thenReturn(Collections.emptyList());

        List<Reservation> result = reservationService.getQueueForBook(200);

        assertTrue(result.isEmpty());
    }

    // ========================= consumeReservation =========================

    @Test
    @DisplayName("消费预约 - 状态更新为已完成")
    void consumeReservation_success() {
        when(reservationMapper.updateByPrimaryKeySelective(any(Reservation.class))).thenReturn(1);

        Integer result = reservationService.consumeReservation(1);

        assertEquals(1, result);
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationMapper).updateByPrimaryKeySelective(captor.capture());
        assertEquals(Reservation.STATUS_FULFILLED, captor.getValue().getStatus());
    }

    // ========================= triggerReservation =========================

    @Test
    @DisplayName("触发保留 - 有排队者时生成保留名额")
    void triggerReservation_withWaiting_success() {
        when(reservationMapper.selectNextWaiting(200)).thenReturn(sampleWaiting);
        when(reservationMapper.updateByPrimaryKeySelective(any(Reservation.class))).thenReturn(1);

        Reservation result = reservationService.triggerReservation(200);

        assertNotNull(result);
        assertEquals(Reservation.STATUS_RESERVED, result.getStatus());
        assertNotNull(result.getExpirytime());
        assertTrue(result.getExpirytime().getTime() > System.currentTimeMillis());
    }

    @Test
    @DisplayName("触发保留 - 无排队者时返回null")
    void triggerReservation_noWaiting_returnsNull() {
        when(reservationMapper.selectNextWaiting(200)).thenReturn(null);

        Reservation result = reservationService.triggerReservation(200);

        assertNull(result);
        verify(reservationMapper, never()).updateByPrimaryKeySelective(any());
    }

    @Test
    @DisplayName("触发保留 - 保留窗口为72小时")
    void triggerReservation_holdWindow72Hours() {
        when(reservationMapper.selectNextWaiting(200)).thenReturn(sampleWaiting);
        when(reservationMapper.updateByPrimaryKeySelective(any(Reservation.class))).thenReturn(1);

        Reservation result = reservationService.triggerReservation(200);

        long holdMs = result.getExpirytime().getTime() - result.getReservetime().getTime();
        assertEquals(72L * 60 * 60 * 1000, holdMs);
    }

    // ========================= expireAndAdvance =========================

    @Test
    @DisplayName("过期顺延 - 有下一位时触发新保留")
    void expireAndAdvance_withNext_success() {
        Reservation next = makeReservation(3, 101, 200, Reservation.STATUS_WAITING, 2);
        when(reservationMapper.selectByPrimaryKey(1)).thenReturn(sampleReserved);
        when(reservationMapper.updateByPrimaryKeySelective(any(Reservation.class))).thenReturn(1);
        when(reservationMapper.selectNextWaiting(200)).thenReturn(next);

        Reservation result = reservationService.expireAndAdvance(1);

        assertNotNull(result);
        assertEquals(Reservation.STATUS_RESERVED, result.getStatus());
        // 验证原预约被标记为过期
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationMapper, atLeastOnce()).updateByPrimaryKeySelective(captor.capture());
        boolean hasExpired = captor.getAllValues().stream()
                .anyMatch(r -> r.getReservationid() != null && r.getReservationid() == 1
                        && r.getStatus() != null && r.getStatus() == Reservation.STATUS_EXPIRED);
        assertTrue(hasExpired);
    }

    @Test
    @DisplayName("过期顺延 - 无下一位时返回null")
    void expireAndAdvance_noNext_returnsNull() {
        when(reservationMapper.selectByPrimaryKey(1)).thenReturn(sampleReserved);
        when(reservationMapper.updateByPrimaryKeySelective(any(Reservation.class))).thenReturn(1);
        when(reservationMapper.selectNextWaiting(200)).thenReturn(null);

        Reservation result = reservationService.expireAndAdvance(1);

        assertNull(result);
    }

    @Test
    @DisplayName("过期顺延 - 预约不存在时返回null")
    void expireAndAdvance_notFound_returnsNull() {
        when(reservationMapper.selectByPrimaryKey(999)).thenReturn(null);

        Reservation result = reservationService.expireAndAdvance(999);

        assertNull(result);
        verify(reservationMapper, never()).selectNextWaiting(anyInt());
    }

    // ========================= getActiveReservation =========================

    @Test
    @DisplayName("获取活跃预约 - 存在时返回")
    void getActiveReservation_exists() {
        when(reservationMapper.selectActiveByBookAndUser(200, 100)).thenReturn(sampleWaiting);

        Reservation result = reservationService.getActiveReservation(200, 100);

        assertNotNull(result);
        assertEquals(100, result.getUserid());
    }

    @Test
    @DisplayName("获取活跃预约 - 不存在时返回null")
    void getActiveReservation_notExists() {
        when(reservationMapper.selectActiveByBookAndUser(200, 100)).thenReturn(null);

        Reservation result = reservationService.getActiveReservation(200, 100);

        assertNull(result);
    }

    // ========================= adjustPosition =========================

    @Test
    @DisplayName("调整位置 - 目标位置有人时交换")
    void adjustPosition_swap() {
        Reservation target = makeReservation(3, 101, 200, Reservation.STATUS_WAITING, 2);
        when(reservationMapper.selectByPrimaryKey(1)).thenReturn(sampleWaiting);
        when(reservationMapper.selectWaitingByBook(200)).thenReturn(Arrays.asList(sampleWaiting, target));
        when(reservationMapper.updateByPrimaryKeySelective(any(Reservation.class))).thenReturn(1);

        Integer result = reservationService.adjustPosition(1, 2);

        assertEquals(1, result);
        // 应该更新 3 次：临时值、交换、最终值
        verify(reservationMapper, times(3)).updateByPrimaryKeySelective(any(Reservation.class));
    }

    @Test
    @DisplayName("调整位置 - 目标位置无人时直接更新")
    void adjustPosition_directUpdate() {
        when(reservationMapper.selectByPrimaryKey(1)).thenReturn(sampleWaiting);
        when(reservationMapper.selectWaitingByBook(200)).thenReturn(Collections.singletonList(sampleWaiting));
        when(reservationMapper.updateByPrimaryKeySelective(any(Reservation.class))).thenReturn(1);

        Integer result = reservationService.adjustPosition(1, 5);

        assertEquals(1, result);
        verify(reservationMapper, times(1)).updateByPrimaryKeySelective(any(Reservation.class));
    }

    @Test
    @DisplayName("调整位置 - 位置不变时直接返回成功")
    void adjustPosition_samePosition_noop() {
        when(reservationMapper.selectByPrimaryKey(1)).thenReturn(sampleWaiting);

        Integer result = reservationService.adjustPosition(1, 1);

        assertEquals(1, result);
        verify(reservationMapper, never()).updateByPrimaryKeySelective(any());
    }

    @Test
    @DisplayName("调整位置 - 预约不存在时返回0")
    void adjustPosition_notFound() {
        when(reservationMapper.selectByPrimaryKey(999)).thenReturn(null);

        Integer result = reservationService.adjustPosition(999, 2);

        assertEquals(0, result);
    }

    @Test
    @DisplayName("调整位置 - 非WAITING状态时返回0")
    void adjustPosition_notWaiting_returnsZero() {
        when(reservationMapper.selectByPrimaryKey(2)).thenReturn(sampleReserved);

        Integer result = reservationService.adjustPosition(2, 3);

        assertEquals(0, result);
    }

    // ========================= getUserReservations =========================

    @Test
    @DisplayName("获取用户预约列表")
    void getUserReservations_returnsList() {
        when(reservationMapper.selectByUser(100)).thenReturn(Arrays.asList(sampleWaiting));

        List<Reservation> result = reservationService.getUserReservations(100);

        assertEquals(1, result.size());
    }

    // ========================= 辅助方法 =========================

    private Reservation makeReservation(Integer id, Integer userid, Integer bookid, byte status, int queuePos) {
        Reservation r = new Reservation();
        r.setReservationid(id);
        r.setUserid(userid);
        r.setBookid(bookid);
        r.setStatus(status);
        r.setQueueposition(queuePos);
        r.setCreatetime(new Date());
        return r;
    }
}
