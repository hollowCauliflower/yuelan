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
    /** 相对湿度（%），无数据时为 null */
    private Integer humidityPercent;
    /** 紫外线指数最大值，无数据时为 null */
    private Double uvIndex;
    /** 空气质量指数，无数据时为 null */
    private Integer airQualityIndex;
    /** 花粉指数，无数据时为 null */
    private Integer pollenIndex;

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

    public Integer getHumidityPercent() {
        return humidityPercent;
    }

    public void setHumidityPercent(Integer humidityPercent) {
        this.humidityPercent = humidityPercent;
    }

    public Double getUvIndex() {
        return uvIndex;
    }

    public void setUvIndex(Double uvIndex) {
        this.uvIndex = uvIndex;
    }

    public Integer getAirQualityIndex() {
        return airQualityIndex;
    }

    public void setAirQualityIndex(Integer airQualityIndex) {
        this.airQualityIndex = airQualityIndex;
    }

    public Integer getPollenIndex() {
        return pollenIndex;
    }

    public void setPollenIndex(Integer pollenIndex) {
        this.pollenIndex = pollenIndex;
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
