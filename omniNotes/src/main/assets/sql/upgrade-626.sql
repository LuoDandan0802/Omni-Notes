/*
 * 第 10 周维护：notes 表新增置顶标记列 is_pinned
 * 0 = 未置顶（历史笔记升级后的默认值），1 = 置顶（列表排序时优先展示）
 * 只追加列，不改动既有列、主键与索引，升级前后数据保持一致
 */
ALTER TABLE notes ADD COLUMN is_pinned INTEGER DEFAULT 0;
