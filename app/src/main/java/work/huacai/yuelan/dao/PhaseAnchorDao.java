package work.huacai.yuelan.dao;

import work.huacai.yuelan.model.PhaseAnchor;

/**
 * 阶段锚点（phase_anchor 表）的数据访问接口。
 * <p>
 * 锚点用于"用户手动修改非经期阶段并选择顺延后续预测"的场景：
 * 预测引擎与日历渲染都读取最近一条锚点，从锚点日期起重排阶段顺序。
 */
public interface PhaseAnchorDao {

    /**
     * 写入或覆盖锚点（同一日期只保留一条，重复写入覆盖阶段下标）。
     *
     * @param anchor 锚点（日期 + 阶段下标）
     * @return 写入成功返回 true
     */
    boolean upsert(PhaseAnchor anchor);

    /**
     * 查询最近一条锚点（按锚点日期最大者）。
     *
     * @return 最近锚点；无锚点返回 null
     */
    PhaseAnchor selectLatest();

    /**
     * 删除指定日期之前（不含）的所有锚点。
     * <p>
     * 新一次实际经期开始形成新分段后，旧分段内的锚点不再参与计算，
     * 可在同步周期时顺手清理，避免表无限增长。
     *
     * @param anchorDateId 日期 id（yyyyMMdd）
     */
    void deleteBefore(String anchorDateId);

    /** 清空全部锚点 */
    void clear();
}
