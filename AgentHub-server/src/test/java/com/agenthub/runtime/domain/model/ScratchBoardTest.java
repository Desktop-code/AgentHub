package com.agenthub.runtime.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享草稿区（ScratchBoard）键值纪律窄测。所属域：runtime。
 *
 * <p>只测纯内存逻辑，不起 Spring、不连库、不出站：
 * 首写成功 / 同键拒写且原值不变 / 非法 key / 超长 value / 键数顶 / 收尾拒写 / 读取语义。
 */
class ScratchBoardTest {

    @Test
    void firstWriteOkAndReadBack() {
        ScratchBoard board = new ScratchBoard();
        ScratchBoard.WriteResult put = board.put("plan/step_1", "售后结论：先退款", "after_sale");

        assertTrue(put.ok());
        assertEquals(ScratchBoard.CODE_OK, put.code());
        assertEquals(1, board.keyCount());
        assertEquals(0, board.conflictCount());

        ScratchBoard.ReadResult read = board.get("plan/step_1");
        assertTrue(read.found());
        assertEquals("售后结论：先退款", read.value());
        assertEquals("after_sale", read.ownerRouteKey());
    }

    @Test
    void secondWriteSameKeyRejectedAndValueKept() {
        ScratchBoard board = new ScratchBoard();
        board.put("k1", "首写值", "a");
        ScratchBoard.WriteResult second = board.put("k1", "覆盖值", "b");

        assertFalse(second.ok());
        assertEquals(ScratchBoard.CODE_KEY_CONFLICT, second.code());
        // 错误信息必须带首写者，模型才知道「换 key 还是改读」
        assertTrue(second.message().contains("a"), second.message());
        assertTrue(second.message().contains("首次写入"), second.message());
        assertEquals("a", second.ownerRouteKey());
        // M3：拒绝双写，不做 last-write-wins
        assertEquals("首写值", board.get("k1").value());
        assertEquals(1, board.keyCount());
        assertEquals(1, board.conflictCount());
    }

    @Test
    void invalidKeyRejectedWithReadableReason() {
        ScratchBoard board = new ScratchBoard();
        // 空 key
        assertEquals(ScratchBoard.CODE_KEY_INVALID, board.put("", "v", "a").code());
        // 数字开头
        assertEquals(ScratchBoard.CODE_KEY_INVALID, board.put("1abc", "v", "a").code());
        // 非法字符（空格 / 中文）
        assertEquals(ScratchBoard.CODE_KEY_INVALID, board.put("a b", "v", "a").code());
        assertEquals(ScratchBoard.CODE_KEY_INVALID, board.put("键", "v", "a").code());
        // 超长 key（65 字符）
        String longKey = "a" + "b".repeat(64);
        ScratchBoard.WriteResult tooLong = board.put(longKey, "v", "a");
        assertEquals(ScratchBoard.CODE_KEY_INVALID, tooLong.code());
        assertTrue(tooLong.message().contains("64"), tooLong.message());
        assertEquals(0, board.keyCount());
        // 读取非法 key 不抛异常，found=false
        assertFalse(board.get("a b").found());
        assertEquals(ScratchBoard.CODE_KEY_INVALID, board.get("a b").code());
    }

    @Test
    void undefinedKeyReadIsNotFoundNotError() {
        ScratchBoard board = new ScratchBoard();
        ScratchBoard.ReadResult read = board.get("missing");
        assertFalse(read.found());
        assertEquals(ScratchBoard.CODE_NOT_FOUND, read.code());
        assertNull(read.value());
    }

    @Test
    void tooLongValueFailsInsteadOfTruncating() {
        ScratchBoard board = new ScratchBoard();
        String value = "x".repeat(ScratchBoard.MAX_VALUE_LENGTH + 1);
        ScratchBoard.WriteResult result = board.put("k1", value, "a");

        assertFalse(result.ok());
        assertEquals(ScratchBoard.CODE_VALUE_TOO_LONG, result.code());
        assertTrue(result.message().contains("4000"), result.message());
        assertEquals(0, board.keyCount());
        // 边界值本身允许
        assertTrue(board.put("k2", "x".repeat(ScratchBoard.MAX_VALUE_LENGTH), "a").ok());
    }

    @Test
    void keyCountCappedAt32() {
        ScratchBoard board = new ScratchBoard();
        for (int i = 0; i < ScratchBoard.MAX_KEYS; i++) {
            assertTrue(board.put("key" + i, "v" + i, "a").ok(), "第 " + (i + 1) + " 个键应写入成功");
        }
        ScratchBoard.WriteResult overflow = board.put("key_extra", "v", "a");
        assertFalse(overflow.ok());
        assertEquals(ScratchBoard.CODE_KEY_LIMIT, overflow.code());
        assertEquals(ScratchBoard.MAX_KEYS, board.keyCount());
        // 已存在的键仍然可以读到，只是不能再写新键
        assertTrue(board.get("key0").found());
    }

    @Test
    void closedBoardRejectsNewWrites() {
        ScratchBoard board = new ScratchBoard();
        board.put("k1", "v", "a");
        board.close();

        ScratchBoard.WriteResult rejected = board.put("k2", "v", "a");
        assertFalse(rejected.ok());
        assertEquals(ScratchBoard.CODE_CLOSED, rejected.code());
        assertTrue(board.closed());
        // 已写入内容本轮内仍可读（板被丢弃是下一轮的事）
        assertEquals("v", board.get("k1").value());
    }
}
