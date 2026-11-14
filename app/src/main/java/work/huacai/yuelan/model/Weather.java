package work.huacai.yuelan.model;

import androidx.annotation.NonNull;

/**
 * 天气数据模型。
 * <p>
 * 数据来源为 Open-Meteo 接口：{@code weatherText} 是中文天气描述（如“晴朗”“雨”），
 * {@code temperature} 是带单位字符串（如“25.0℃”），便于直接展示。
 */
public class Weather {
    /** 日期（yyyy-MM-dd） */
    private String date;
    /** 天气描述（中文） */
    private String weatherText;
    /** 温度（带“℃”单位） */
    private String temperature;

    /** 供 Gson 反序列化使用的无参构造 */
    public Weather() {
    }

    /**
     * @param date        日期（yyyy-MM-dd）
     * @param weatherText 天气描述
     * @param temperature 带单位温度
     */
    public Weather(String date, String weatherText, String temperature) {
        this.date = date;
        this.weatherText = weatherText;
        this.temperature = temperature;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getWeatherText() {
        return weatherText;
    }

    public void setWeatherText(String weatherText) {
        this.weatherText = weatherText;
    }

    public String getTemperature() {
        return temperature;
    }

    public void setTemperature(String temperature) {
        this.temperature = temperature;
    }

    @NonNull
    @Override
    public String toString() {
        return "Weather{" +
                "date='" + date + '\'' +
                ", weatherText='" + weatherText + '\'' +
                ", temperature='" + temperature + '\'' +
                '}';
    }
}
