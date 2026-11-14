package work.huacai.yuelan.dao;

import java.util.List;

import work.huacai.yuelan.model.Reminder;

/**
 * 自定义提醒的数据访问接口。
 */
public interface ReminderDao {

    /**
     * 查询全部提醒，按目标日期倒序。
     *
     * @return 提醒列表；无数据或查询失败返回空列表
     */
    List<Reminder> selectAll();

    /**
     * 查询指定日期的提醒。
     *
     * @param targetDay 目标日期（yyyyMMdd 整数）
     * @return 该日期的提醒列表；无数据返回空列表
     */
    List<Reminder> selectByDay(int targetDay);

    /**
     * 按主键查询。
     *
     * @param id 提醒主键
     * @return 提醒对象；不存在返回 null
     */
    Reminder selectById(long id);

    /**
     * 插入一条提醒。
     *
     * @param reminder 待插入的提醒
     * @return 新行 id；失败返回 -1
     */
    long insert(Reminder reminder);

    /**
     * 更新一条提醒。
     *
     * @param reminder 待更新的提醒（必须带 id）
     * @return 受影响行数
     */
    int update(Reminder reminder);

    /**
     * 删除一条提醒。
     *
     * @param id 提醒主键
     * @return 受影响行数
     */
    int delete(long id);

    /**
     * 修改提醒的启用状态。
     *
     * @param id      提醒主键
     * @param enabled 是否启用
     * @return 受影响行数
     */
    int setEnabled(long id, boolean enabled);
}
