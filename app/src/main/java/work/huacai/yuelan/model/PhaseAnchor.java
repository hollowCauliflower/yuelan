package work.huacai.yuelan.model;

import java.util.Date;

/**
 * 阶段锚点：用户手动修改某天的非经期阶段并选择"顺延后续预测"时写入。
 * <p>
 * 从锚点日期起，该分段内的预测阶段按锚点阶段顺序重排（例如把卵泡期中的某天
 * 标记为排卵期后，该日之后依次进入黄体期、下一经期……），进入新一轮实际经期
 * 分段后锚点自动失效。只保留最近一条作为当前生效锚点。
 */
public class PhaseAnchor {
    /** 锚点日期（预测从该日起按锚点阶段起算，锚点日当天视为锚点阶段第 1 天） */
    private Date anchorDate;
    /** 锚点阶段下标（0=经期，1=卵泡期，2=排卵期，3=黄体期） */
    private int phaseIndex;

    public PhaseAnchor() {
    }

    public PhaseAnchor(Date anchorDate, int phaseIndex) {
        this.anchorDate = anchorDate;
        this.phaseIndex = phaseIndex;
    }

    public Date getAnchorDate() {
        return anchorDate;
    }

    public void setAnchorDate(Date anchorDate) {
        this.anchorDate = anchorDate;
    }

    public int getPhaseIndex() {
        return phaseIndex;
    }

    public void setPhaseIndex(int phaseIndex) {
        this.phaseIndex = phaseIndex;
    }
}
