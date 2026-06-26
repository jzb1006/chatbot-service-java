#!/usr/bin/env node

import { createInterface } from "node:readline";
import { realpathSync } from "node:fs";

const OPEN_METEO_GEOCODING_URL =
  process.env.WEATHER_MCP_GEOCODING_URL || "https://geocoding-api.open-meteo.com/v1/search";
const OPEN_METEO_FORECAST_URL =
  process.env.WEATHER_MCP_FORECAST_URL || "https://api.open-meteo.com/v1/forecast";
const DEFAULT_COUNTRY_CODE = process.env.WEATHER_MCP_COUNTRY_CODE || "CN";
const DEFAULT_LANGUAGE = process.env.WEATHER_MCP_LANGUAGE || "zh";
const DEFAULT_TIMEZONE = process.env.WEATHER_MCP_TIMEZONE || "Asia/Shanghai";
const DEFAULT_TIMEOUT_MS = Number(process.env.WEATHER_MCP_TIMEOUT_MS || 30000);

const tools = [
  {
    name: "get_current_weather",
    description:
      "查询指定城市的当前天气。用户问“天气怎么样”“今天热不热”“上海天气”等天气问题时调用本工具。默认按中国城市和北京时间查询，返回适合 TTS 播报的 tts_text。",
    inputSchema: {
      type: "object",
      properties: {
        city: {
          type: "string",
          description: "城市名，支持中文，例如“上海”“北京”“深圳”。",
        },
        countryCode: {
          type: "string",
          description: "ISO 国家代码，默认 CN。只有用户明确询问海外城市时才传其他国家代码。",
        },
        timezone: {
          type: "string",
          description: "IANA 时区名，默认 Asia/Shanghai。",
        },
      },
      required: ["city"],
      additionalProperties: false,
    },
  },
  {
    name: "get_weather_forecast",
    description:
      "查询指定城市未来天气预报。用户问“未来几天天气”“明后天天气”“这周天气”“要不要带伞”等未来天气问题时调用本工具。默认返回未来 3 天，最多 16 天，返回适合 TTS 播报的 tts_text。",
    inputSchema: {
      type: "object",
      properties: {
        city: {
          type: "string",
          description: "城市名，支持中文，例如“上海”“北京”“深圳”。",
        },
        days: {
          type: "integer",
          description: "要查询的天数，默认 3，范围 1 到 16。",
          minimum: 1,
          maximum: 16,
        },
        countryCode: {
          type: "string",
          description: "ISO 国家代码，默认 CN。只有用户明确询问海外城市时才传其他国家代码。",
        },
        timezone: {
          type: "string",
          description: "IANA 时区名，默认 Asia/Shanghai。",
        },
      },
      required: ["city"],
      additionalProperties: false,
    },
  },
  {
    name: "get_daily_forecast",
    description:
      "查询指定城市某一天的天气预报。用户问“明天”“后天”“周末”“6月27日”等单日天气时调用本工具。date 用 YYYY-MM-DD；不传 date 时可传 dayOffset，0 是今天，1 是明天。",
    inputSchema: {
      type: "object",
      properties: {
        city: {
          type: "string",
          description: "城市名，支持中文，例如“上海”“北京”“深圳”。",
        },
        date: {
          type: "string",
          description: "目标日期，格式 YYYY-MM-DD。",
        },
        dayOffset: {
          type: "integer",
          description: "相对今天的天数偏移，0 是今天，1 是明天，2 是后天。",
          minimum: 0,
          maximum: 15,
        },
        countryCode: {
          type: "string",
          description: "ISO 国家代码，默认 CN。只有用户明确询问海外城市时才传其他国家代码。",
        },
        timezone: {
          type: "string",
          description: "IANA 时区名，默认 Asia/Shanghai。",
        },
      },
      required: ["city"],
      additionalProperties: false,
    },
  },
  {
    name: "get_tomorrow_weather",
    description:
      "查询指定城市明天天气。用户问“明天天气怎么样”“明天会下雨吗”“明天要不要带伞”“明天冷不冷/热不热”时调用本工具。",
    inputSchema: {
      type: "object",
      properties: {
        city: {
          type: "string",
          description: "城市名，支持中文，例如“上海”“北京”“深圳”。",
        },
        countryCode: {
          type: "string",
          description: "ISO 国家代码，默认 CN。只有用户明确询问海外城市时才传其他国家代码。",
        },
        timezone: {
          type: "string",
          description: "IANA 时区名，默认 Asia/Shanghai。",
        },
      },
      required: ["city"],
      additionalProperties: false,
    },
  },
];

export function validateCity(city) {
  const normalized = String(city ?? "").trim();
  if (!normalized) {
    throw new Error("city 不能为空");
  }
  if (normalized.length > 80) {
    throw new Error("city 太长");
  }
  return normalized;
}

export function mapWeatherCode(code) {
  const numericCode = Number(code);
  const labels = {
    0: "晴",
    1: "大部晴朗",
    2: "局部多云",
    3: "阴",
    45: "雾",
    48: "雾凇",
    51: "小毛毛雨",
    53: "毛毛雨",
    55: "大毛毛雨",
    56: "冻毛毛雨",
    57: "强冻毛毛雨",
    61: "小雨",
    63: "中雨",
    65: "大雨",
    66: "冻雨",
    67: "强冻雨",
    71: "小雪",
    73: "中雪",
    75: "大雪",
    77: "米雪",
    80: "阵雨",
    81: "中等阵雨",
    82: "强阵雨",
    85: "阵雪",
    86: "强阵雪",
    95: "雷暴",
    96: "雷暴伴小冰雹",
    99: "雷暴伴强冰雹",
  };
  return labels[numericCode] || "未知天气";
}

export function formatWeatherTts({ city, condition, temperature, humidity, windSpeed }) {
  const parts = [`${city}现在${condition}`];
  if (Number.isFinite(Number(temperature))) {
    parts.push(`${Math.round(Number(temperature))} 度`);
  }
  if (Number.isFinite(Number(humidity))) {
    parts.push(`湿度 ${Math.round(Number(humidity))}%`);
  }
  if (Number.isFinite(Number(windSpeed))) {
    parts.push(`风速 ${Math.round(Number(windSpeed))} 公里每小时`);
  }
  return `${parts.join("，")}。`;
}

export function formatDailyForecastTts({
  city,
  dateLabel,
  condition,
  minTemperature,
  maxTemperature,
  precipitationProbability,
  precipitationSum,
  windSpeed,
}) {
  const parts = [`${city}${dateLabel}${condition}`];
  if (Number.isFinite(Number(minTemperature)) && Number.isFinite(Number(maxTemperature))) {
    parts.push(`${Math.round(Number(minTemperature))} 到 ${Math.round(Number(maxTemperature))} 度`);
  }
  if (Number.isFinite(Number(precipitationProbability))) {
    parts.push(`降雨概率 ${Math.round(Number(precipitationProbability))}%`);
  } else if (Number.isFinite(Number(precipitationSum)) && Number(precipitationSum) > 0) {
    parts.push(`预计降水 ${Number(precipitationSum).toFixed(1)} 毫米`);
  }
  if (Number.isFinite(Number(windSpeed))) {
    parts.push(`最大风速 ${Math.round(Number(windSpeed))} 公里每小时`);
  }
  return `${parts.join("，")}。`;
}

function normalizeLocation(location) {
  return {
    name: location.name,
    country: location.country,
    admin1: location.admin1,
    timezone: location.timezone,
    latitude: location.latitude,
    longitude: location.longitude,
  };
}

export function buildCurrentWeather({ requestedCity, location, forecast }) {
  const current = forecast?.current;
  if (!current) {
    throw new Error("Open-Meteo 未返回 current 天气数据");
  }

  const condition = mapWeatherCode(current.weather_code);
  return {
    provider: "open-meteo",
    requested_city: requestedCity,
    location: normalizeLocation(location),
    current: {
      time: current.time,
      condition,
      weather_code: current.weather_code,
      temperature_celsius: current.temperature_2m,
      humidity_percent: current.relative_humidity_2m,
      wind_speed_kmh: current.wind_speed_10m,
    },
    tts_text: formatWeatherTts({
      city: location.name || requestedCity,
      condition,
      temperature: current.temperature_2m,
      humidity: current.relative_humidity_2m,
      windSpeed: current.wind_speed_10m,
    }),
  };
}

export function buildDailyForecast({ requestedCity, location, forecast, targetDate, dateLabel }) {
  const daily = forecast?.daily;
  if (!daily?.time?.length) {
    throw new Error("Open-Meteo 未返回 daily 天气预报数据");
  }

  const index = daily.time.indexOf(targetDate);
  if (index < 0) {
    throw new Error(`Open-Meteo 未返回日期 ${targetDate} 的天气预报`);
  }

  const condition = mapWeatherCode(daily.weather_code?.[index]);
  const day = {
    date: daily.time[index],
    label: dateLabel,
    condition,
    weather_code: daily.weather_code?.[index],
    temperature_min_celsius: daily.temperature_2m_min?.[index],
    temperature_max_celsius: daily.temperature_2m_max?.[index],
    precipitation_probability_percent: daily.precipitation_probability_max?.[index],
    precipitation_sum_mm: daily.precipitation_sum?.[index],
    wind_speed_max_kmh: daily.wind_speed_10m_max?.[index],
  };

  return {
    provider: "open-meteo",
    requested_city: requestedCity,
    location: normalizeLocation(location),
    forecast: day,
    tts_text: formatDailyForecastTts({
      city: location.name || requestedCity,
      dateLabel,
      condition,
      minTemperature: day.temperature_min_celsius,
      maxTemperature: day.temperature_max_celsius,
      precipitationProbability: day.precipitation_probability_percent,
      precipitationSum: day.precipitation_sum_mm,
      windSpeed: day.wind_speed_max_kmh,
    }),
  };
}

export function buildWeatherForecast({ requestedCity, location, forecast, days }) {
  const daily = forecast?.daily;
  if (!daily?.time?.length) {
    throw new Error("Open-Meteo 未返回 daily 天气预报数据");
  }

  const forecastDays = daily.time.slice(0, days).map((date, index) => {
    const condition = mapWeatherCode(daily.weather_code?.[index]);
    return {
      date,
      label: dateLabelForOffset(index),
      condition,
      weather_code: daily.weather_code?.[index],
      temperature_min_celsius: daily.temperature_2m_min?.[index],
      temperature_max_celsius: daily.temperature_2m_max?.[index],
      precipitation_probability_percent: daily.precipitation_probability_max?.[index],
      precipitation_sum_mm: daily.precipitation_sum?.[index],
      wind_speed_max_kmh: daily.wind_speed_10m_max?.[index],
    };
  });

  return {
    provider: "open-meteo",
    requested_city: requestedCity,
    location: normalizeLocation(location),
    forecast_days: forecastDays,
    tts_text: forecastDays.map((day) => formatDailyForecastTts({
      city: location.name || requestedCity,
      dateLabel: day.label,
      condition: day.condition,
      minTemperature: day.temperature_min_celsius,
      maxTemperature: day.temperature_max_celsius,
      precipitationProbability: day.precipitation_probability_percent,
      precipitationSum: day.precipitation_sum_mm,
      windSpeed: day.wind_speed_max_kmh,
    })).join(" "),
  };
}

export async function getCurrentWeather(args = {}) {
  const city = validateCity(args.city);
  const countryCode = String(args.countryCode || DEFAULT_COUNTRY_CODE).trim().toUpperCase();
  const timezone = String(args.timezone || DEFAULT_TIMEZONE).trim();
  const location = await geocodeCity({ city, countryCode });
  const forecast = await fetchForecast({
    latitude: location.latitude,
    longitude: location.longitude,
    timezone,
  });
  return buildCurrentWeather({ requestedCity: city, location, forecast });
}

export async function getWeatherForecast(args = {}) {
  const city = validateCity(args.city);
  const countryCode = String(args.countryCode || DEFAULT_COUNTRY_CODE).trim().toUpperCase();
  const timezone = String(args.timezone || DEFAULT_TIMEZONE).trim();
  const days = normalizeForecastDays(args.days ?? 3);
  const location = await geocodeCity({ city, countryCode });
  const forecast = await fetchForecast({
    latitude: location.latitude,
    longitude: location.longitude,
    timezone,
    daily: true,
    forecastDays: days,
  });
  return buildWeatherForecast({ requestedCity: city, location, forecast, days });
}

export async function getDailyForecast(args = {}) {
  const city = validateCity(args.city);
  const countryCode = String(args.countryCode || DEFAULT_COUNTRY_CODE).trim().toUpperCase();
  const timezone = String(args.timezone || DEFAULT_TIMEZONE).trim();
  const dayOffset = normalizeDayOffset(args.dayOffset ?? 0);
  const targetDate = normalizeForecastDate(args.date, timezone, dayOffset);
  const forecastDays = daysFromToday(targetDate, timezone) + 1;
  const location = await geocodeCity({ city, countryCode });
  const forecast = await fetchForecast({
    latitude: location.latitude,
    longitude: location.longitude,
    timezone,
    daily: true,
    forecastDays,
  });
  return buildDailyForecast({
    requestedCity: city,
    location,
    forecast,
    targetDate,
    dateLabel: dateLabelForOffset(forecastDays - 1),
  });
}

export async function getTomorrowWeather(args = {}) {
  return getDailyForecast({ ...args, dayOffset: 1 });
}

export function normalizeForecastDays(days) {
  const parsed = Number(days);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 16) {
    throw new Error("days 必须是 1 到 16 之间的整数");
  }
  return parsed;
}

export function normalizeDayOffset(dayOffset) {
  const parsed = Number(dayOffset);
  if (!Number.isInteger(parsed) || parsed < 0 || parsed > 15) {
    throw new Error("dayOffset 必须是 0 到 15 之间的整数");
  }
  return parsed;
}

export function normalizeForecastDate(date, timezone, dayOffset = 0) {
  if (date !== undefined && date !== null && String(date).trim()) {
    const normalized = String(date).trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(normalized)) {
      throw new Error("date 必须使用 YYYY-MM-DD 格式");
    }
    daysFromToday(normalized, timezone);
    return normalized;
  }
  return localDateForOffset(timezone, normalizeDayOffset(dayOffset));
}

export function daysFromToday(date, timezone) {
  const today = localDateForOffset(timezone, 0);
  const diff = ymdToUtcDay(date) - ymdToUtcDay(today);
  if (diff < 0 || diff > 15) {
    throw new Error("date 必须在今天到未来 15 天内");
  }
  return diff;
}

export function dateLabelForOffset(offset) {
  if (offset === 0) {
    return "今天";
  }
  if (offset === 1) {
    return "明天";
  }
  if (offset === 2) {
    return "后天";
  }
  return `${offset} 天后`;
}

export async function handleRequest(request) {
  if (request.jsonrpc !== "2.0") {
    return errorResponse(request.id ?? null, -32600, "Invalid JSON-RPC version.");
  }

  switch (request.method) {
    case "initialize":
      return {
        jsonrpc: "2.0",
        id: request.id,
        result: {
          protocolVersion: request.params?.protocolVersion || "2024-11-05",
          capabilities: { tools: {} },
          serverInfo: { name: "weather-mcp", version: "0.1.0" },
        },
      };
    case "notifications/initialized":
      return null;
    case "tools/list":
      return { jsonrpc: "2.0", id: request.id, result: { tools } };
    case "tools/call":
      return callTool(request);
    default:
      return errorResponse(request.id, -32601, `Unsupported method: ${request.method}`);
  }
}

async function callTool(request) {
  const name = request.params?.name;
  const args = request.params?.arguments ?? {};
  const toolHandlers = {
    get_current_weather: getCurrentWeather,
    get_weather_forecast: getWeatherForecast,
    get_daily_forecast: getDailyForecast,
    get_tomorrow_weather: getTomorrowWeather,
  };
  const handler = toolHandlers[name];
  if (!handler) {
    return errorResponse(request.id, -32602, `Unknown tool: ${name}`);
  }

  try {
    const result = await handler(args);
    return {
      jsonrpc: "2.0",
      id: request.id,
      result: {
        content: [{ type: "text", text: JSON.stringify(result, null, 2) }],
        isError: false,
      },
    };
  } catch (error) {
    return {
      jsonrpc: "2.0",
      id: request.id,
      result: {
        content: [{ type: "text", text: JSON.stringify({ error: error.message }, null, 2) }],
        isError: true,
      },
    };
  }
}

async function geocodeCity({ city, countryCode }) {
  const url = new URL(OPEN_METEO_GEOCODING_URL);
  url.searchParams.set("name", city);
  url.searchParams.set("count", "1");
  url.searchParams.set("language", DEFAULT_LANGUAGE);
  url.searchParams.set("format", "json");
  if (countryCode) {
    url.searchParams.set("countryCode", countryCode);
  }

  const payload = await fetchJson(url);
  const location = payload?.results?.[0];
  if (!location) {
    throw new Error(`没有找到城市：${city}`);
  }
  return location;
}

async function fetchForecast({ latitude, longitude, timezone, daily = false, forecastDays }) {
  const url = new URL(OPEN_METEO_FORECAST_URL);
  url.searchParams.set("latitude", String(latitude));
  url.searchParams.set("longitude", String(longitude));
  url.searchParams.set("current", "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m");
  if (daily) {
    url.searchParams.set(
      "daily",
      "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max",
    );
    url.searchParams.set("forecast_days", String(normalizeForecastDays(forecastDays ?? 3)));
  }
  url.searchParams.set("timezone", timezone);
  return fetchJson(url);
}

function localDateForOffset(timezone, offset) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: timezone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  const date = new Date(Date.UTC(Number(values.year), Number(values.month) - 1, Number(values.day) + offset));
  return date.toISOString().slice(0, 10);
}

function ymdToUtcDay(date) {
  const [year, month, day] = date.split("-").map(Number);
  if (!year || !month || !day) {
    throw new Error("date 必须使用 YYYY-MM-DD 格式");
  }
  return Math.floor(Date.UTC(year, month - 1, day) / 86400000);
}

async function fetchJson(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), DEFAULT_TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      signal: controller.signal,
      headers: { "user-agent": "weather-mcp/0.1" },
    });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }
    return response.json();
  } finally {
    clearTimeout(timer);
  }
}

function errorResponse(id, code, message) {
  return { jsonrpc: "2.0", id, error: { code, message } };
}

function writeJson(payload) {
  process.stdout.write(`${JSON.stringify(payload)}\n`);
}

if (isMainModule()) {
  const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });
  let queue = Promise.resolve();
  rl.on("line", (line) => {
    queue = queue.then(async () => {
      if (!line.trim()) {
        return;
      }
      let request;
      try {
        request = JSON.parse(line);
        const response = await handleRequest(request);
        if (response !== null) {
          writeJson(response);
        }
      } catch (error) {
        writeJson({
          jsonrpc: "2.0",
          id: request?.id ?? null,
          error: { code: -32603, message: error.message || "weather MCP error" },
        });
      }
    });
  });
}

function isMainModule() {
  if (!process.argv[1]) {
    return false;
  }
  const modulePath = new URL(import.meta.url);
  return realpathSync(modulePath) === realpathSync(process.argv[1]);
}
