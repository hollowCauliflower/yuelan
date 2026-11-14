package work.huacai.yuelan.dao.impl;

import android.content.ContentValues;
import android.database.Cursor;
import android.util.Log;

import java.util.Date;

import work.huacai.yuelan.dao.PhaseAnchorDao;
import work.huacai.yuelan.database.DatabaseHelper;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.model.PhaseAnchor;

/**
 * {@link PhaseAnchorDao} 的实现，管理 phase_anchor 表。
 * <p>
 * 表结构：anchor_date（TEXT 主键，yyyyMMdd）+ phase_index（INTEGER）。
 * 预测引擎（生成预测记录）与日历渲染都只读取最近一条锚点；
 * 锚点日期早于当前分段起点时自然失效，无需即时清理。
 */
public class PhaseAnchorDaoImpl implements PhaseAnchorDao {

    private static final String TAG = "PhaseAnchorDaoImpl";

    private final DatabaseHelper databaseHelper = DatabaseHelper.getDatabaseHelper();

    /**
     * 写入或覆盖锚点；同一 anchor_date 已存在时更新其阶段下标。
     *
     * @param anchor 锚点（日期 + 阶段下标）
     * @return 写入成功返回 true
     */
    @Override
    public boolean upsert(PhaseAnchor anchor) {
        if (anchor == null || anchor.getAnchorDate() == null) {
            Log.e(TAG, "upsert: anchor 或日期为空");
            return false;
        }
        String dateId = CycleCalculator.formatDateId(anchor.getAnchorDate());
        ContentValues values = new ContentValues();
        values.put("phase_index", anchor.getPhaseIndex());

        int updated = databaseHelper.update("phase_anchor", values,
                "anchor_date = ?", new String[]{dateId});
        if (updated > 0) {
            return true;
        }
        values.put("anchor_date", dateId);
        return databaseHelper.insert("phase_anchor", values) != -1;
    }

    /**
     * 查询最近一条锚点（按 anchor_date 字典序最大者，yyyyMMdd 字典序即时间序）。
     *
     * @return 最近锚点；无锚点返回 null
     */
    @Override
    public PhaseAnchor selectLatest() {
        Cursor cursor = null;
        try {
            cursor = databaseHelper.querySQL(
                    "SELECT anchor_date, phase_index FROM phase_anchor ORDER BY anchor_date DESC LIMIT 1",
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                Date anchorDate = CycleCalculator.parseDateId(cursor.getString(0));
                int phaseIndex = cursor.getInt(1);
                if (anchorDate != null) {
                    return new PhaseAnchor(anchorDate, phaseIndex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "selectLatest error", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * 删除指定日期 id 之前（不含）的所有锚点，用于新分段形成后清理旧分段锚点。
     *
     * @param anchorDateId 日期 id（yyyyMMdd）
     */
    @Override
    public void deleteBefore(String anchorDateId) {
        if (anchorDateId == null) {
            return;
        }
        databaseHelper.delete("phase_anchor", "anchor_date < ?", new String[]{anchorDateId});
    }

    /** 清空全部锚点 */
    @Override
    public void clear() {
        databaseHelper.delete("phase_anchor", null, null);
    }
}
