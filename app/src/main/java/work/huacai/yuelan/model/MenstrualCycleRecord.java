package work.huacai.yuelan.model;

import androidx.annotation.NonNull;

import java.util.Date;

/**
 * 用户的周期配置记录（对应数据库中的周期表）。
 * <p>
 * 只保存用户设置的三项关键参数：最近一次月经开始日、周期总长度、月经持续天数，
 * 四个阶段的长度由 {@code CycleCalculator.calculatePhaseDays} 推导，不落库。
 */
public class MenstrualCycleRecord {

    /** 主键 */
    private int id;

    /**
     * 最后一次月经开始时间
     */
    private Date startTime;

    /**
     * 月经周期天数（两次月经开始之间的间隔）
     */
    private int totalCycleDay;

    /**
     * 月经期天数（每次月经持续的天数）
     */
    private int menstrualPeriodDay;

    /** 记录创建时间 */
    private Date createdTime;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public Date getStartTime() {
        return startTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public int getTotalCycleDay() {
        return totalCycleDay;
    }

    public void setTotalCycleDay(int totalCycleDay) {
        this.totalCycleDay = totalCycleDay;
    }

    public int getMenstrualPeriodDay() {
        return menstrualPeriodDay;
    }

    public void setMenstrualPeriodDay(int menstrualPeriodDay) {
        this.menstrualPeriodDay = menstrualPeriodDay;
    }

    public Date getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(Date createdTime) {
        this.createdTime = createdTime;
    }

    @NonNull
    @Override
    public String toString() {
        return "MenstrualCycleRecord{" +
                "id=" + id +
                ", startTime=" + startTime +
                ", totalCycleDay=" + totalCycleDay +
                ", menstrualPeriodDay=" + menstrualPeriodDay +
                ", createdTime=" + createdTime +
                '}';
    }
}
