#!/usr/bin/env node

import { createInterface } from "node:readline";
import nodemailer from "nodemailer";
import { ImapFlow } from "imapflow";
import { simpleParser } from "mailparser";

export const ASSISTANT_SIGNATURE = "本邮件由志彬的 AI 助手代为发送。";
const DEFAULT_MAX_UNREAD_EMAILS = 5;
const DEFAULT_SUMMARY_CHARS = 90;
const DEFAULT_BODY_CHARS = 1200;
const DEFAULT_HTML_CHARS = 10000;
const SELF_CLOSING_HTML_TAGS = new Set(["br", "hr", "img"]);
const ALLOWED_HTML_TAGS = new Set([
  "a",
  "b",
  "br",
  "div",
  "em",
  "h1",
  "h2",
  "h3",
  "hr",
  "i",
  "img",
  "li",
  "ol",
  "p",
  "small",
  "span",
  "strong",
  "style",
  "table",
  "tbody",
  "td",
  "tfoot",
  "th",
  "thead",
  "tr",
  "ul",
]);
const ALLOWED_CSS_PROPERTIES = new Set([
  "background",
  "background-color",
  "border-collapse",
  "border-spacing",
  "box-shadow",
  "box-sizing",
  "color",
  "display",
  "font-size",
  "font-weight",
  "font-style",
  "font-family",
  "height",
  "line-height",
  "max-width",
  "min-width",
  "padding",
  "padding-top",
  "padding-right",
  "padding-bottom",
  "padding-left",
  "margin",
  "margin-top",
  "margin-right",
  "margin-bottom",
  "margin-left",
  "border",
  "border-top",
  "border-right",
  "border-bottom",
  "border-left",
  "border-radius",
  "text-align",
  "vertical-align",
  "width",
]);

const tools = [
  {
    name: "list_recipients",
    description:
      "列出 QQ 邮件 MCP 允许发送的收件人别名。发送邮件前如不确定别名，先调用本工具。只返回别名，不暴露完整邮箱。",
    inputSchema: {
      type: "object",
      properties: {},
      additionalProperties: false,
    },
  },
  {
    name: "send_email",
    description:
      "通过 QQ 邮箱发送邮件。Hermes 必须先润色 subject 和 text，并向用户复述收件人、标题、正文摘要，获得确认后才调用。recipientAlias 必须来自白名单；本工具会自动追加 AI 助手署名。",
    inputSchema: {
      type: "object",
      properties: {
        recipientAlias: {
          type: "string",
          description: "收件人别名，必须是 list_recipients 返回的白名单别名。",
        },
        subject: {
          type: "string",
          description: "AI 润色后的邮件标题。不能为空，建议简洁自然。",
        },
        text: {
          type: "string",
          description: "AI 润色后的纯文本邮件正文。不要包含 HTML 或 Markdown 表格。",
        },
      },
      required: ["recipientAlias", "subject", "text"],
      additionalProperties: false,
    },
  },
  {
    name: "send_html_email",
    description:
      "通过 QQ 邮箱发送视觉增强 HTML 邮件。Hermes 必须先润色 subject 和 html，并向用户复述收件人、标题、正文摘要，获得确认后才调用。recipientAlias 必须来自白名单；可使用常见邮件 HTML 标签、style 块、class、表格、按钮链接和 http/https 图片，工具会清洗脚本与危险资源、生成纯文本 fallback，并自动追加 AI 助手署名。",
    inputSchema: {
      type: "object",
      properties: {
        recipientAlias: {
          type: "string",
          description: "收件人别名，必须是 list_recipients 返回的白名单别名。",
        },
        subject: {
          type: "string",
          description: "AI 润色后的邮件标题。不能为空，建议简洁自然。",
        },
        html: {
          type: "string",
          description: "AI 润色后的 HTML 正文。优先做出清晰视觉层级，可使用 style 块、class、表格、分区、按钮链接和 http/https 图片；不要包含脚本、表单、data URL、javascript URL 或外链 CSS。",
        },
      },
      required: ["recipientAlias", "subject", "html"],
      additionalProperties: false,
    },
  },
  {
    name: "list_unread_emails",
    description:
      "查看 QQ 邮箱最新未读邮件摘要，最多返回 5 封。用户问“有没有新邮件”“查一下最新邮件”时调用。只返回摘要，避免直接朗读长正文。",
    inputSchema: {
      type: "object",
      properties: {},
      additionalProperties: false,
    },
  },
  {
    name: "get_email_text",
    description:
      "按 uid 读取指定邮件的纯文本内容。只有用户明确要求读某封邮件正文或读全文时调用。返回内容会去除 HTML 并截断，避免语音播报过长。",
    inputSchema: {
      type: "object",
      properties: {
        uid: {
          type: "integer",
          description: "邮件 UID，来自 list_unread_emails 返回值。",
        },
      },
      required: ["uid"],
      additionalProperties: false,
    },
  },
];

export function parseRecipientsConfig(rawConfig) {
  let parsed;
  try {
    parsed = JSON.parse(String(rawConfig ?? ""));
  } catch (error) {
    throw new Error("QQ_EMAIL_RECIPIENTS 必须是 JSON 对象");
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("QQ_EMAIL_RECIPIENTS 必须是 JSON 对象");
  }

  const recipients = {};
  for (const [alias, email] of Object.entries(parsed)) {
    const normalizedAlias = normalizeRequiredText(alias, "recipient alias");
    const normalizedEmail = normalizeRequiredText(email, `${normalizedAlias} email`);
    if (!isEmailLike(normalizedEmail)) {
      throw new Error(`收件人 ${normalizedAlias} 的邮箱格式不合法`);
    }
    recipients[normalizedAlias] = normalizedEmail;
  }

  if (Object.keys(recipients).length === 0) {
    throw new Error("QQ_EMAIL_RECIPIENTS 至少需要一个收件人");
  }
  return recipients;
}

export function loadRecipientConfig(env = process.env) {
  if (!env.QQ_EMAIL_RECIPIENTS) {
    throw new Error("缺少 QQ_EMAIL_RECIPIENTS 配置");
  }
  return parseRecipientsConfig(env.QQ_EMAIL_RECIPIENTS);
}

export function validateSendEmailArgs(args = {}, recipients) {
  const recipientAlias = normalizeRequiredText(args.recipientAlias, "recipientAlias");
  const subject = normalizeRequiredText(args.subject, "subject");
  const text = normalizeRequiredText(args.text, "text");
  if (!recipients || !Object.hasOwn(recipients, recipientAlias)) {
    throw new Error(`收件人别名 ${recipientAlias} 不在白名单`);
  }
  if (subject.length > 120) {
    throw new Error("subject 不能超过 120 个字符");
  }
  if (text.length > 5000) {
    throw new Error("text 不能超过 5000 个字符");
  }
  return {
    recipientAlias,
    to: recipients[recipientAlias],
    subject,
    text,
  };
}

export function validateSendHtmlEmailArgs(args = {}, recipients) {
  const recipientAlias = normalizeRequiredText(args.recipientAlias, "recipientAlias");
  const subject = normalizeRequiredText(args.subject, "subject");
  const html = normalizeRequiredText(args.html, "html");
  if (!recipients || !Object.hasOwn(recipients, recipientAlias)) {
    throw new Error(`收件人别名 ${recipientAlias} 不在白名单`);
  }
  if (subject.length > 120) {
    throw new Error("subject 不能超过 120 个字符");
  }
  if (html.length > DEFAULT_HTML_CHARS) {
    throw new Error(`html 不能超过 ${DEFAULT_HTML_CHARS} 个字符`);
  }
  return {
    recipientAlias,
    to: recipients[recipientAlias],
    subject,
    html,
  };
}

export function buildEmailText(text) {
  const normalized = normalizeRequiredText(text, "text");
  if (normalized.includes(ASSISTANT_SIGNATURE)) {
    return normalized;
  }
  return `${normalized}\n\n${ASSISTANT_SIGNATURE}`;
}

export function sanitizeEmailHtml(value) {
  const html = normalizeRequiredText(value, "html")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/<script[\s\S]*?<\/script>/gi, "")
    .replace(/<style\b[^>]*>([\s\S]*?)<\/style>/gi, (_match, css) => {
      const sanitizedCss = sanitizeStyleBlock(css);
      return sanitizedCss ? `<style>${sanitizedCss}</style>` : "";
    });

  return html.replace(/<[^>]*>|[^<]+/g, (token) => {
    if (!token.startsWith("<")) {
      return escapeHtml(token);
    }
    return sanitizeHtmlTag(token);
  });
}

export function buildEmailHtml(html) {
  const sanitized = sanitizeEmailHtml(html);
  if (sanitized.includes(ASSISTANT_SIGNATURE)) {
    return sanitized;
  }
  return `${sanitized}<p>${escapeHtml(ASSISTANT_SIGNATURE)}</p>`;
}

export function normalizeEmailText(value, maxChars = DEFAULT_SUMMARY_CHARS) {
  const noHtml = String(value ?? "")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<br\s*\/?>/gi, " ")
    .replace(/<\/p>/gi, " ")
    .replace(/<[^>]+>/g, " ");
  const decoded = decodeHtmlEntities(noHtml)
    .replace(/\s+/g, " ")
    .trim();
  if (decoded.length <= maxChars) {
    return decoded;
  }
  return `${decoded.slice(0, maxChars).trim()}...`;
}

export function buildUnreadEmailSummary(messages = []) {
  const emails = [...messages]
    .sort((a, b) => getMessageTime(b) - getMessageTime(a))
    .slice(0, DEFAULT_MAX_UNREAD_EMAILS)
    .map((message) => {
      const from = formatAddressList(message.envelope?.from);
      const subject = message.envelope?.subject || "(无主题)";
      const date = message.envelope?.date ? new Date(message.envelope.date).toISOString() : null;
      return {
        uid: message.uid,
        date,
        from,
        subject,
        summary: normalizeEmailText(message.body || message.text || message.html || "", DEFAULT_SUMMARY_CHARS),
      };
    });

  let ttsText = "没有未读邮件。";
  if (emails.length > 0) {
    ttsText = `有 ${emails.length} 封未读邮件。最新一封来自 ${emails[0].from}，主题是 ${emails[0].subject}。`;
  }
  return {
    count: emails.length,
    emails,
    tts_text: ttsText,
  };
}

export function createSmtpTransport(env = process.env) {
  const user = normalizeRequiredText(env.QQ_EMAIL_USER, "QQ_EMAIL_USER");
  const pass = normalizeRequiredText(env.QQ_EMAIL_AUTH_CODE, "QQ_EMAIL_AUTH_CODE");
  return nodemailer.createTransport({
    host: env.QQ_EMAIL_SMTP_HOST || "smtp.qq.com",
    port: Number(env.QQ_EMAIL_SMTP_PORT || 587),
    secure: String(env.QQ_EMAIL_SMTP_SECURE || "false") === "true",
    auth: { user, pass },
    connectionTimeout: Number(env.QQ_EMAIL_TIMEOUT_MS || 30000),
    greetingTimeout: Number(env.QQ_EMAIL_TIMEOUT_MS || 30000),
    socketTimeout: Number(env.QQ_EMAIL_TIMEOUT_MS || 30000),
  });
}

export function createImapClient(env = process.env) {
  const user = normalizeRequiredText(env.QQ_EMAIL_USER, "QQ_EMAIL_USER");
  const pass = normalizeRequiredText(env.QQ_EMAIL_AUTH_CODE, "QQ_EMAIL_AUTH_CODE");
  return new ImapFlow({
    host: env.QQ_EMAIL_IMAP_HOST || "imap.qq.com",
    port: Number(env.QQ_EMAIL_IMAP_PORT || 993),
    secure: String(env.QQ_EMAIL_IMAP_SECURE || "true") !== "false",
    auth: { user, pass },
    logger: false,
  });
}

export async function sendEmail(args = {}, options = {}) {
  const env = options.env || process.env;
  const recipients = options.recipients || loadRecipientConfig(env);
  const validated = validateSendEmailArgs(args, recipients);
  const transporter = options.transporter || createSmtpTransport(env);
  const from = normalizeRequiredText(env.QQ_EMAIL_USER, "QQ_EMAIL_USER");
  const body = buildEmailText(validated.text);
  const result = await transporter.sendMail({
    from,
    to: validated.to,
    subject: validated.subject,
    text: body,
  });

  return {
    recipient_alias: validated.recipientAlias,
    subject: validated.subject,
    message_id: result.messageId || null,
    tts_text: `邮件已发送给${validated.recipientAlias}，主题是${validated.subject}。`,
  };
}

export async function sendHtmlEmail(args = {}, options = {}) {
  const env = options.env || process.env;
  const recipients = options.recipients || loadRecipientConfig(env);
  const validated = validateSendHtmlEmailArgs(args, recipients);
  const transporter = options.transporter || createSmtpTransport(env);
  const from = normalizeRequiredText(env.QQ_EMAIL_USER, "QQ_EMAIL_USER");
  const sanitizedHtml = sanitizeEmailHtml(validated.html);
  const html = buildEmailHtml(sanitizedHtml);
  const text = buildEmailText(normalizeEmailText(sanitizedHtml, 5000));
  const result = await transporter.sendMail({
    from,
    to: validated.to,
    subject: validated.subject,
    text,
    html,
  });

  return {
    recipient_alias: validated.recipientAlias,
    subject: validated.subject,
    message_id: result.messageId || null,
    tts_text: `HTML 邮件已发送给${validated.recipientAlias}，主题是${validated.subject}。`,
  };
}

export async function listUnreadEmails(options = {}) {
  const client = options.client || createImapClient(options.env || process.env);
  try {
    await client.connect();
    const lock = await client.getMailboxLock("INBOX");
    try {
      const uidList = (await client.search({ seen: false }, { uid: true })) || [];
      if (uidList.length === 0) {
        return buildUnreadEmailSummary([]);
      }
      const latestUids = [...uidList].sort((a, b) => b - a).slice(0, DEFAULT_MAX_UNREAD_EMAILS);
      const messages = [];
      for await (const message of client.fetch(latestUids, {
        uid: true,
        envelope: true,
        source: true,
      }, { uid: true })) {
        const parsed = await simpleParser(message.source);
        messages.push({
          uid: message.uid,
          envelope: message.envelope,
          body: parsed.text || parsed.html || "",
        });
      }
      return buildUnreadEmailSummary(messages);
    } finally {
      lock.release();
    }
  } finally {
    await client.logout().catch(() => {});
  }
}

export async function getEmailText(args = {}, options = {}) {
  const uid = Number(args.uid);
  if (!Number.isInteger(uid) || uid <= 0) {
    throw new Error("uid 必须是正整数");
  }

  const client = options.client || createImapClient(options.env || process.env);
  try {
    await client.connect();
    const lock = await client.getMailboxLock("INBOX");
    try {
      let found;
      for await (const message of client.fetch([uid], {
        uid: true,
        envelope: true,
        source: true,
      }, { uid: true })) {
        found = message;
      }
      if (!found) {
        throw new Error(`未找到 UID ${uid} 的邮件`);
      }
      const parsed = await simpleParser(found.source);
      const text = normalizeEmailText(parsed.text || parsed.html || "", DEFAULT_BODY_CHARS);
      return {
        uid,
        from: formatAddressList(found.envelope?.from),
        subject: found.envelope?.subject || "(无主题)",
        text,
        tts_text: text ? `邮件正文是：${text}` : "这封邮件没有可读正文。",
      };
    } finally {
      lock.release();
    }
  } finally {
    await client.logout().catch(() => {});
  }
}

export async function handleRequest(request, options = {}) {
  if (request?.jsonrpc !== "2.0") {
    return errorResponse(request?.id ?? null, -32600, "Invalid JSON-RPC version.");
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
            name: "xiaozhi-qq-email-mcp",
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
      return callTool(request, options);
    default:
      return errorResponse(request.id, -32601, `Unsupported method: ${request.method}`);
  }
}

async function callTool(request, options) {
  const name = request.params?.name;
  const args = request.params?.arguments ?? {};
  try {
    let result;
    if (name === "list_recipients") {
      const recipients = loadRecipientConfig(options.env || process.env);
      result = {
        aliases: Object.keys(recipients),
        tts_text: `可以发送给：${Object.keys(recipients).join("、")}。`,
      };
    } else if (name === "send_email") {
      result = await sendEmail(args, options);
    } else if (name === "send_html_email") {
      result = await sendHtmlEmail(args, options);
    } else if (name === "list_unread_emails") {
      result = await listUnreadEmails(options);
    } else if (name === "get_email_text") {
      result = await getEmailText(args, options);
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
        content: [
          {
            type: "text",
            text: JSON.stringify({ error: error.message }, null, 2),
          },
        ],
        isError: true,
      },
    };
  }
}

function normalizeRequiredText(value, fieldName) {
  const normalized = String(value ?? "").trim();
  if (!normalized) {
    throw new Error(`${fieldName} 不能为空`);
  }
  return normalized;
}

function isEmailLike(value) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
}

function decodeHtmlEntities(value) {
  return value
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'");
}

function sanitizeHtmlTag(token) {
  const closing = token.match(/^<\s*\/\s*([a-z0-9]+)\s*>$/i);
  if (closing) {
    const tag = closing[1].toLowerCase();
    return ALLOWED_HTML_TAGS.has(tag) && !SELF_CLOSING_HTML_TAGS.has(tag) ? `</${tag}>` : "";
  }

  const opening = token.match(/^<\s*([a-z0-9]+)/i);
  if (!opening) {
    return "";
  }
  const tag = opening[1].toLowerCase();
  if (!ALLOWED_HTML_TAGS.has(tag)) {
    return "";
  }
  if (tag === "br" || tag === "hr") {
    return `<${tag}>`;
  }
  if (tag === "style") {
    return "<style>";
  }
  const className = sanitizeClassName(extractAttribute(token, "class"));
  const style = sanitizeInlineStyle(extractAttribute(token, "style"));
  if (tag === "a") {
    const href = sanitizeHref(extractAttribute(token, "href"));
    return buildOpeningTag(tag, { href, style, className });
  }
  if (tag === "img") {
    const src = sanitizeResourceUrl(extractAttribute(token, "src"));
    if (!src) {
      return "";
    }
    return buildOpeningTag(tag, {
      src,
      alt: sanitizePlainAttribute(extractAttribute(token, "alt"), 120),
      width: sanitizeDimension(extractAttribute(token, "width")),
      height: sanitizeDimension(extractAttribute(token, "height")),
      style,
      className,
    });
  }
  return buildOpeningTag(tag, { style, className });
}

function buildOpeningTag(tag, attributes = {}) {
  const parts = [`<${tag}`];
  if (attributes.href) {
    parts.push(` href="${escapeHtmlAttribute(attributes.href)}"`);
  }
  if (attributes.src) {
    parts.push(` src="${escapeHtmlAttribute(attributes.src)}"`);
  }
  if (attributes.alt) {
    parts.push(` alt="${escapeHtmlAttribute(attributes.alt)}"`);
  }
  if (attributes.width) {
    parts.push(` width="${escapeHtmlAttribute(attributes.width)}"`);
  }
  if (attributes.height) {
    parts.push(` height="${escapeHtmlAttribute(attributes.height)}"`);
  }
  if (attributes.style) {
    parts.push(` style="${escapeHtmlAttribute(attributes.style)}"`);
  }
  if (attributes.className) {
    parts.push(` class="${escapeHtmlAttribute(attributes.className)}"`);
  }
  parts.push(">");
  return parts.join("");
}

function extractAttribute(token, attributeName) {
  const quoted = token.match(new RegExp(`\\s${attributeName}\\s*=\\s*(["'])(.*?)\\1`, "i"));
  const unquoted = token.match(new RegExp(`\\s${attributeName}\\s*=\\s*([^\\s"'<>]+)`, "i"));
  return (quoted?.[2] || unquoted?.[1] || "").trim();
}

function sanitizeHref(href) {
  if (/^(https?:\/\/|mailto:)/i.test(href)) {
    return href;
  }
  return "";
}

function sanitizeResourceUrl(url) {
  if (/^https?:\/\//i.test(url)) {
    return url;
  }
  return "";
}

function sanitizeClassName(value) {
  const classes = String(value ?? "")
    .split(/\s+/)
    .map((name) => name.trim())
    .filter((name) => /^[a-z0-9_-]{1,48}$/i.test(name));
  return classes.slice(0, 12).join(" ");
}

function sanitizePlainAttribute(value, maxLength) {
  const normalized = String(value ?? "")
    .replace(/\s+/g, " ")
    .trim();
  if (!normalized) {
    return "";
  }
  return normalized.slice(0, maxLength);
}

function sanitizeDimension(value) {
  const normalized = String(value ?? "").trim();
  if (/^\d{1,4}$/.test(normalized)) {
    return normalized;
  }
  if (/^\d{1,4}px$/i.test(normalized)) {
    return normalized.toLowerCase();
  }
  if (/^\d{1,3}%$/.test(normalized)) {
    return normalized;
  }
  return "";
}

function sanitizeInlineStyle(style) {
  if (!style) {
    return "";
  }
  const safeRules = [];
  for (const rawRule of style.split(";")) {
    const separator = rawRule.indexOf(":");
    if (separator <= 0) {
      continue;
    }
    const property = rawRule.slice(0, separator).trim().toLowerCase();
    const value = rawRule.slice(separator + 1).trim().replace(/\s+/g, " ");
    if (property === "display" && value.toLowerCase() === "none") {
      continue;
    }
    if (!ALLOWED_CSS_PROPERTIES.has(property) || !isSafeCssValue(value)) {
      continue;
    }
    safeRules.push(`${property}:${value}`);
  }
  return safeRules.join(";");
}

function sanitizeStyleBlock(css) {
  const withoutComments = String(css ?? "")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/@import[^;}]*(;|})/gi, "");
  const safeRules = [];
  const rulePattern = /([^{}]+)\{([^{}]*)\}/g;
  let match;
  while ((match = rulePattern.exec(withoutComments)) !== null) {
    const selector = sanitizeCssSelector(match[1]);
    const declarations = sanitizeInlineStyle(match[2]);
    if (selector && declarations) {
      safeRules.push(`${selector}{${declarations}}`);
    }
  }
  return safeRules.join("");
}

function sanitizeCssSelector(selector) {
  const normalized = String(selector ?? "")
    .replace(/\s+/g, " ")
    .trim();
  if (!normalized || normalized.length > 160) {
    return "";
  }
  if (/[>{}[\]"'`]|javascript:|data:/i.test(normalized)) {
    return "";
  }
  if (!/^[#.:\-*,\w\s]+$/u.test(normalized)) {
    return "";
  }
  return normalized;
}

function isSafeCssValue(value) {
  if (!value || value.length > 120) {
    return false;
  }
  if (/url\s*\(|expression\s*\(|javascript:|data:|@import|position\s*:|display\s*:\s*none/i.test(value)) {
    return false;
  }
  return /^[#%(),."':\/\-+\w\s]+$/u.test(value);
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function escapeHtmlAttribute(value) {
  return escapeHtml(value).replace(/"/g, "&quot;");
}

function getMessageTime(message) {
  const date = message.envelope?.date;
  const time = date ? new Date(date).getTime() : 0;
  return Number.isFinite(time) ? time : 0;
}

function formatAddressList(addresses = []) {
  if (!Array.isArray(addresses) || addresses.length === 0) {
    return "未知发件人";
  }
  return addresses
    .map((address) => address.name || address.address || "")
    .filter(Boolean)
    .join("、") || "未知发件人";
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

async function processLine(line, options = {}) {
  if (!line.trim()) {
    return;
  }
  let request;
  try {
    request = JSON.parse(line);
    const response = await handleRequest(request, options);
    if (response !== null) {
      writeJson(response);
    }
  } catch (error) {
    writeJson({
      jsonrpc: "2.0",
      id: request?.id ?? null,
      error: {
        code: -32603,
        message: error.message || "QQ email MCP error",
      },
    });
  }
}

export function runServer(options = {}) {
  const rl = createInterface({
    input: process.stdin,
    crlfDelay: Infinity,
  });

  let queue = Promise.resolve();
  rl.on("line", (line) => {
    queue = queue.then(() => processLine(line, options));
  });
  process.stderr.write("xiaozhi QQ email MCP server started\n");
}

if (import.meta.url === `file://${process.argv[1]}`) {
  runServer();
}
