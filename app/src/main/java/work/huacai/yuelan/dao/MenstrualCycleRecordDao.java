package work.huacai.yuelan.dao;

import android.content.Context;

import work.huacai.yuelan.model.MenstrualCycleRecord;

/**
 * 月经周期设置（menstrual_cycle_record 表）的数据访问接口。
 * <p>
 * 该表按"每轮实际周期一行"保存分段参数（周期长度 / 经期长度 / 分段起始日）：
 * 新一轮实际经期开始时追加新分段并沿用当前设置；设置页修改只更新最新分段，
 * 历史分段保持原参数不变，日历渲染与预测生成均按日期所属分段计算。
 * 具体实现见 {@code dao.impl.MenstrualCycleRecordDaoImpl}。
 */
public interface MenstrualCycleRecordDao {

    /**
     * 插入一条周期设置记录。
     *
     * @param menstrualCycleRecord 周期设置
     * @return 新行的 rowId；参数为空或插入失败返回 -1
     */
    long insert(MenstrualCycleRecord menstrualCycleRecord);

    /**
     * 查询最近一次保存的周期设置（按分段起始日倒序，即当前周期所在分段）。
     *
     * @return 最新记录；表为空或读取失败返回 null
     */
    MenstrualCycleRecord selectLast();

    /**
     * 查询全部周期分段（按分段起始日升序）。
     *
     * @return 分段列表；表为空或读取失败返回空列表
     */
    java.util.List<MenstrualCycleRecord> selectAll();

    /**
     * 更新已有的周期设置（按 id 定位），不存在时退化为插入。
     * <p>
     * 设置页修改周期/经期/起始日时都应调用本方法：只更新当前（最新）分段，
     * 历史分段保持原参数，实现"上个周期的设置不随这个周期变化"。
     *
     * @param menstrualCycleRecord 待保存的周期设置（应带有从 selectLast 取得的 id）
     * @return 更新或插入成功返回对应行的 id；失败返回 -1
     */
    long updateLatest(MenstrualCycleRecord menstrualCycleRecord);

    /**
     * 用户手动指定经期起始日：把该日落为实际经期记录，并确保存在以该日开头的分段，
     * 避免随后的基准日对齐把用户的选择回滚。
     *
     * @param startDate          用户选择的经期起始日
     * @param totalCycleDay      周期总天数
     * @param menstrualPeriodDay 经期天数
     * @param context            用于生成建议文案
     */
    void applyManualCycleStart(java.util.Date startDate, int totalCycleDay,
                               int menstrualPeriodDay, Context context);

    /**
     * 保存用户设置并在事务中初始化每日记录。
     *
     * @param menstrualCycleRecord 周期设置
     * @param context              用于生成建议文案
     * @return 保存成功返回 true；任一步骤失败则回滚并返回 false
     */
    boolean saveUserSettingsAndInitData(MenstrualCycleRecord menstrualCycleRecord, Context context);

    /**
     * 从周期起始日一直生成每日记录到今天。
     * <p>
     * 已存在的预测记录会被更新为最新阶段，用户实际记录过的日期保持原样；缺失日期则插入。
     *
     * @param menstrualCycleRecord 周期设置
     * @param context              用于生成建议文案与定位/天气
     */
    void insertToToday(MenstrualCycleRecord menstrualCycleRecord, Context context);

    /**
     * 把预测基准日对齐到"最近一段连续实际经期的首日"，并按最新设置重算已有预测记录。
     * <p>
     * 没有任何实际经期记录时保留设置中的起始日。新一轮实际经期开始时追加新分段
     * （沿用当前设置参数），历史分段保持原参数不变，不再原地覆盖。
     *
     * @param context 用于生成建议文案
     */
    void syncActualStartAndRegenerate(Context context);
}
