package work.huacai.yuelan.dao;

import java.util.List;

import work.huacai.yuelan.model.MenstrualRecord;

/**
 * 每日记录（menstrual_record 表）的数据访问接口。
 * <p>
 * 记录以日期字符串（yyyyMMdd）作为主键 id，因此按 id 排序等同于按日期排序。
 * 具体实现见 {@code dao.impl.MenstrualRecordDaoImpl}。
 */
public interface MenstrualRecordDao {

    /**
     * 查询所有Menstrual
     * @return Menstrual集合
     */
    List<MenstrualRecord> selectAll();

    /**
     * 只查询用户实际填写过的记录（数据库 is_prediction = 1）。
     * <p>
     * 统计、图表与周期切分必须使用该结果，避免把自动生成的预测占位记录当作真实数据。
     *
     * @return 实际记录列表（按日期倒序）；无数据或查询失败返回空列表
     */
    List<MenstrualRecord> selectActual();

    /**
     * 查询最后一次插入的MenstrualRecord
     *
     * @return 最后一次MenstrualRecord
     */
    MenstrualRecord selectLastId();

    /**
     * 新增一条每日记录（同 id 已存在时由数据库约束报错）。
     *
     * @param menstrualRecord 待插入的记录
     * @return 新行的 rowId；失败返回 -1
     */
    long insert(MenstrualRecord menstrualRecord);

    /**
     * 更新建议
     * @param id 记录ID
     * @param suggestion 建议内容
     * @return 更新的行数
     */
    int updateSuggestion(String id, String suggestion);

    /**
     * 根据ID查询记录
     * @param id 记录ID（日期格式，如20260326）
     * @return MenstrualRecord，不存在返回null
     */
    MenstrualRecord selectById(String id);

    /**
     * 用整条记录覆盖更新数据库中的对应行（按 id 定位），并刷新 updated_time。
     *
     * @param record 待更新的记录
     * @return 受影响行数；记录或 id 为空、更新失败返回 0
     */
    int updateRecord(MenstrualRecord record);

    /**
     * 查询指定范围内的记录
     * @param startDate 开始日期ID
     * @param endDate 结束日期ID
     * @return 记录列表
     */
    List<MenstrualRecord> selectByDateRange(String startDate, String endDate);

    /**
     * 按日期 id 删除一条记录。
     * <p>
     * 删除后调用方需要重新同步预测基准日并重排经期提醒，否则该日期会留空，
     * 或被旧的预测数据继续占用。
     *
     * @param id 记录 id（yyyyMMdd）
     * @return 实际删除的行数；id 为空、记录不存在或失败返回 0
     */
    int deleteById(String id);
}
