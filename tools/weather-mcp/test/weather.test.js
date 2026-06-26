import test from "node:test";
import assert from "node:assert/strict";

import {
  buildDailyForecast,
  buildCurrentWeather,
  buildWeatherForecast,
  formatDailyForecastTts,
  formatWeatherTts,
  handleRequest,
  mapWeatherCode,
  validateCity,
} from "../src/index.js";

test("validateCity trims a Chinese city name", () => {
  assert.equal(validateCity(" 上海 "), "上海");
});

test("validateCity rejects empty city", () => {
  assert.throws(() => validateCity("  "), /city 不能为空/);
});

test("mapWeatherCode converts WMO weather codes to Chinese text", () => {
  assert.equal(mapWeatherCode(95), "雷暴");
  assert.equal(mapWeatherCode(999), "未知天气");
});

test("formatWeatherTts returns a short TTS friendly sentence", () => {
  const text = formatWeatherTts({
    city: "上海",
    condition: "雷暴",
    temperature: 23.8,
    humidity: 94,
    windSpeed: 9.9,
  });

  assert.equal(text, "上海现在雷暴，24 度，湿度 94%，风速 10 公里每小时。");
});

test("formatDailyForecastTts returns a forecast sentence", () => {
  const text = formatDailyForecastTts({
    city: "广州",
    dateLabel: "明天",
    condition: "小雨",
    minTemperature: 26.2,
    maxTemperature: 32.1,
    precipitationProbability: 78,
    windSpeed: 15.4,
  });

  assert.equal(text, "广州明天小雨，26 到 32 度，降雨概率 78%，最大风速 15 公里每小时。");
});

test("buildCurrentWeather normalizes Open-Meteo payload", () => {
  const result = buildCurrentWeather({
    requestedCity: "上海",
    location: {
      name: "上海",
      latitude: 31.22222,
      longitude: 121.45806,
      country: "中国",
      admin1: "上海市",
      timezone: "Asia/Shanghai",
    },
    forecast: {
      current: {
        time: "2026-06-21T18:15",
        temperature_2m: 23.8,
        relative_humidity_2m: 94,
        weather_code: 95,
        wind_speed_10m: 9.9,
      },
    },
  });

  assert.deepEqual(result, {
    provider: "open-meteo",
    requested_city: "上海",
    location: {
      name: "上海",
      country: "中国",
      admin1: "上海市",
      timezone: "Asia/Shanghai",
      latitude: 31.22222,
      longitude: 121.45806,
    },
    current: {
      time: "2026-06-21T18:15",
      condition: "雷暴",
      weather_code: 95,
      temperature_celsius: 23.8,
      humidity_percent: 94,
      wind_speed_kmh: 9.9,
    },
    tts_text: "上海现在雷暴，24 度，湿度 94%，风速 10 公里每小时。",
  });
});

test("buildDailyForecast normalizes Open-Meteo daily payload", () => {
  const result = buildDailyForecast({
    requestedCity: "广州",
    location: {
      name: "广州",
      latitude: 23.11667,
      longitude: 113.25,
      country: "中国",
      admin1: "广东省",
      timezone: "Asia/Shanghai",
    },
    targetDate: "2026-06-27",
    dateLabel: "明天",
    forecast: {
      daily: {
        time: ["2026-06-26", "2026-06-27"],
        weather_code: [3, 61],
        temperature_2m_min: [27.1, 26.2],
        temperature_2m_max: [33.4, 32.1],
        precipitation_probability_max: [40, 78],
        precipitation_sum: [0.2, 4.8],
        wind_speed_10m_max: [12.5, 15.4],
      },
    },
  });

  assert.deepEqual(result, {
    provider: "open-meteo",
    requested_city: "广州",
    location: {
      name: "广州",
      country: "中国",
      admin1: "广东省",
      timezone: "Asia/Shanghai",
      latitude: 23.11667,
      longitude: 113.25,
    },
    forecast: {
      date: "2026-06-27",
      label: "明天",
      condition: "小雨",
      weather_code: 61,
      temperature_min_celsius: 26.2,
      temperature_max_celsius: 32.1,
      precipitation_probability_percent: 78,
      precipitation_sum_mm: 4.8,
      wind_speed_max_kmh: 15.4,
    },
    tts_text: "广州明天小雨，26 到 32 度，降雨概率 78%，最大风速 15 公里每小时。",
  });
});

test("buildWeatherForecast returns multiple forecast days", () => {
  const result = buildWeatherForecast({
    requestedCity: "广州",
    location: {
      name: "广州",
      latitude: 23.11667,
      longitude: 113.25,
      country: "中国",
      admin1: "广东省",
      timezone: "Asia/Shanghai",
    },
    days: 2,
    forecast: {
      daily: {
        time: ["2026-06-26", "2026-06-27", "2026-06-28"],
        weather_code: [3, 61, 80],
        temperature_2m_min: [27.1, 26.2, 25.8],
        temperature_2m_max: [33.4, 32.1, 31.5],
        precipitation_probability_max: [40, 78, 86],
        precipitation_sum: [0.2, 4.8, 7.1],
        wind_speed_10m_max: [12.5, 15.4, 16.1],
      },
    },
  });

  assert.equal(result.forecast_days.length, 2);
  assert.equal(
    result.tts_text,
    "广州今天阴，27 到 33 度，降雨概率 40%，最大风速 13 公里每小时。 广州明天小雨，26 到 32 度，降雨概率 78%，最大风速 15 公里每小时。",
  );
});

test("tools/list exposes current and forecast weather tools", async () => {
  const response = await handleRequest({ jsonrpc: "2.0", id: 1, method: "tools/list" });
  assert.deepEqual(
    response.result.tools.map((tool) => tool.name),
    [
      "get_current_weather",
      "get_weather_forecast",
      "get_daily_forecast",
      "get_tomorrow_weather",
    ],
  );
});
