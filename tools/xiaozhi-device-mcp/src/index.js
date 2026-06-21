#!/usr/bin/env node

import { createInterface } from "node:readline";
import { readFileSync } from "node:fs";
import { lookup } from "node:dns/promises";

const baseUrl = process.env.XIAOZHI_MCP_BASE_URL || "http://device_gateway:8766/api/hermes/xiaozhi/mcp";
const timeoutMs = Number(process.env.XIAOZHI_MCP_TIMEOUT_MS || 15000);
let resolvedBaseUrl;

const tools = [
  {
    name: "get_volume",
    description:
      "读取当前小智设备扬声器音量。用户问“现在音量是多少”“当前音量”“声音多大”时必须调用本工具。返回 volume，范围 0-100。",
    inputSchema: {
      type: "object",
      properties: {
        deviceId: {
          type: "string",
          description: "可选。在线小智设备 ID。只有多个设备在线时才需要传。",
        },
      },
      additionalProperties: false,
    },
  },
  {
    name: "set_volume",
    description:
      "设置当前小智设备扬声器音量。用户说“把音量调到50”“音量调大/调小”“静音”时调用本工具。相对调节前如果不知道当前值，先调用 get_volume。",
    inputSchema: {
      type: "object",
      properties: {
        deviceId: {
          type: "string",
          description: "可选。在线小智设备 ID。只有多个设备在线时才需要传。",
        },
        volume: {
          type: "integer",
          minimum: 0,
          maximum: 100,
          description: "目标音量百分比，范围 0-100。静音传 0。",
        },
      },
      required: ["volume"],
      additionalProperties: false,
    },
  },
  {
    name: "create_reminder",
    description:
      "创建一次性小智提醒。用户说“1分钟后提醒我喝水”“下午三点叫我开会”时必须调用本工具。到点后设备会主动播报 message。",
    inputSchema: {
      type: "object",
      properties: {
        deviceId: {
          type: "string",
          description: "可选。在线小智设备 ID。只有多个设备在线时才需要传。",
        },
        message: {
          type: "string",
          description: "到点后需要播报的提醒内容，不要包含时间前缀。例如“喝水”。",
        },
        remindAt: {
          type: "string",
          description: "ISO-8601 到期时间，例如 2026-06-20T18:00:00+08:00。绝对时间提醒使用。",
        },
        delaySeconds: {
          type: "integer",
          minimum: 0,
          description: "从当前时间开始延迟的秒数。例如一分钟后传 60。",
        },
      },
      required: ["message"],
      additionalProperties: false,
    },
  },
];

const rl = createInterface({
  input: process.stdin,
  crlfDelay: Infinity,
});

let queue = Promise.resolve();

rl.on("line", (line) => {
  queue = queue.then(() => processLine(line));
});

async function processLine(line) {
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
      error: {
        code: -32603,
        message: error.message || "xiaozhi device MCP error",
      },
    });
  }
}

async function handleRequest(request) {
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
          serverInfo: {
            name: "xiaozhi-device-mcp",
            version: "0.1.0",
          },
        },
      };
    case "notifications/initialized":
      return null;
    case "tools/list":
      return {
        jsonrpc: "2.0",
        id: request.id,
        result: { tools },
      };
    case "tools/call":
      return callTool(request);
    default:
      return errorResponse(request.id, -32601, `Unsupported method: ${request.method}`);
  }
}

async function callTool(request) {
  const name = request.params?.name;
  const args = request.params?.arguments ?? {};

  try {
    let result;
    if (name === "get_volume") {
      result = await getVolume(args);
    } else if (name === "set_volume") {
      result = await setVolume(args);
    } else if (name === "create_reminder") {
      result = await createReminder(args);
    } else {
      return errorResponse(request.id, -32602, `Unknown tool: ${name}`);
    }

    return {
      jsonrpc: "2.0",
      id: request.id,
      result: {
        content: [
          {
            type: "text",
            text: JSON.stringify(result, null, 2),
          },
        ],
        isError: false,
      },
    };
  } catch (error) {
    return {
      jsonrpc: "2.0",
      id: request.id,
      result: {
        isError: true,
        content: [
          {
            type: "text",
            text: JSON.stringify({ error: error.message }, null, 2),
          },
        ],
      },
    };
  }
}

async function getVolume(args = {}) {
  const deviceId = await resolveDeviceId(args.deviceId);
  const status = await callDeviceTool(deviceId, "self.get_device_status", {});
  const volume = Number(status?.audio_speaker?.volume);
  if (!Number.isFinite(volume)) {
    throw new Error("设备状态中没有 audio_speaker.volume");
  }
  return {
    deviceId,
    volume,
    confirmation_text: `当前音量是 ${volume}`,
    status,
  };
}

async function setVolume(args = {}) {
  const volume = Number(args.volume);
  if (!Number.isInteger(volume) || volume < 0 || volume > 100) {
    throw new Error("volume 必须是 0-100 的整数");
  }
  const deviceId = await resolveDeviceId(args.deviceId);
  await callDeviceTool(deviceId, "self.audio_speaker.set_volume", { volume });
  const status = await callDeviceTool(deviceId, "self.get_device_status", {});
  const appliedVolume = Number(status?.audio_speaker?.volume);
  return {
    deviceId,
    volume: appliedVolume,
    requested_volume: volume,
    applied: appliedVolume === volume,
    confirmation_text: `已把音量调到 ${appliedVolume}`,
    status,
  };
}

async function createReminder(args = {}) {
  const message = requiredText(args.message, "message");
  const payload = { message };
  if (typeof args.deviceId === "string" && args.deviceId.trim()) {
    payload.deviceId = args.deviceId.trim();
  }
  if (Number.isInteger(args.delaySeconds)) {
    payload.delaySeconds = args.delaySeconds;
  } else if (typeof args.remindAt === "string" && args.remindAt.trim()) {
    payload.remindAt = args.remindAt.trim();
  } else {
    throw new Error("delaySeconds 或 remindAt 至少需要一个");
  }

  const reminder = await callGatewayTool("xiaozhi_create_reminder", payload);
  return {
    ...reminder,
    confirmation_text: buildReminderConfirmation(reminder, payload),
  };
}

async function resolveDeviceId(deviceId) {
  if (typeof deviceId === "string" && deviceId.trim()) {
    return deviceId.trim();
  }

  const devices = await callGatewayTool("xiaozhi_list_online_devices", {});
  const online = Array.isArray(devices.devices) ? devices.devices : [];
  if (online.length === 1) {
    return online[0];
  }
  if (online.length === 0) {
    throw new Error("当前没有在线小智设备");
  }
  throw new Error(`当前有多个在线小智设备，请指定 deviceId：${online.join(", ")}`);
}

async function callDeviceTool(deviceId, name, args) {
  const result = await callGatewayTool("xiaozhi_call_device_tool", {
    deviceId,
    name,
    arguments: args,
  });
  if (result.isError) {
    throw new Error(readFirstText(result) || `设备工具调用失败：${name}`);
  }
  const text = readFirstText(result);
  if (!text) {
    throw new Error(`设备工具没有返回文本：${name}`);
  }
  return JSON.parse(text);
}

async function callGatewayTool(name, args) {
  const response = await postJsonRpc({
    jsonrpc: "2.0",
    id: Date.now(),
    method: "tools/call",
    params: {
      name,
      arguments: args,
    },
  });
  if (response.error) {
    throw new Error(response.error.message || `网关工具调用失败：${name}`);
  }
  const result = response.result ?? {};
  if (result.isError) {
    throw new Error(readFirstText(result) || `网关工具返回错误：${name}`);
  }
  const text = readFirstText(result);
  return text ? JSON.parse(text) : result;
}

async function postJsonRpc(payload) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const targetUrl = await resolveBaseUrl();
    const response = await fetch(targetUrl.url, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${readToken()}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`小智 MCP 网关 HTTP ${response.status}: ${text}`);
    }
    return JSON.parse(text);
  } finally {
    clearTimeout(timeout);
  }
}

async function resolveBaseUrl() {
  if (resolvedBaseUrl) {
    return resolvedBaseUrl;
  }

  const url = new URL(baseUrl);
  if (url.hostname.includes("_")) {
    const address = await lookup(url.hostname);
    url.hostname = address.address;
  }
  resolvedBaseUrl = {
    url: url.toString(),
  };
  return resolvedBaseUrl;
}

function readToken() {
  if (process.env.XIAOZHI_MCP_HERMES_TOKEN) {
    return process.env.XIAOZHI_MCP_HERMES_TOKEN;
  }
  if (process.env.XIAOZHI_MCP_HERMES_TOKEN_FILE) {
    return readFileSync(process.env.XIAOZHI_MCP_HERMES_TOKEN_FILE, "utf8").trim();
  }
  throw new Error("缺少 XIAOZHI_MCP_HERMES_TOKEN 或 XIAOZHI_MCP_HERMES_TOKEN_FILE");
}

function readFirstText(result) {
  const content = result?.content;
  if (!Array.isArray(content)) {
    return "";
  }
  const item = content.find((entry) => entry?.type === "text" && typeof entry.text === "string");
  return item?.text || "";
}

function requiredText(value, field) {
  if (typeof value !== "string" || !value.trim()) {
    throw new Error(`${field} 是必填项`);
  }
  return value.trim();
}

function buildReminderConfirmation(reminder, payload) {
  if (Number.isInteger(payload.delaySeconds)) {
    const minutes = Math.round(payload.delaySeconds / 60);
    if (payload.delaySeconds > 0 && payload.delaySeconds % 60 === 0) {
      return `${minutes} 分钟后提醒你${reminder.message}`;
    }
    return `${payload.delaySeconds} 秒后提醒你${reminder.message}`;
  }
  return `已设置提醒：${reminder.message}`;
}

function errorResponse(id, code, message) {
  return {
    jsonrpc: "2.0",
    id,
    error: { code, message },
  };
}

function writeJson(payload) {
  process.stdout.write(`${JSON.stringify(payload)}\n`);
}
